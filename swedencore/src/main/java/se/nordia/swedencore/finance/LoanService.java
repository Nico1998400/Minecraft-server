package se.nordia.swedencore.finance;

import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.economy.TransferReceipt;
import se.nordia.swedencore.economy.TransferRequest;
import se.nordia.swedencore.reputation.ReputationService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit between players and companies. The system enforces the agreement: repayments are collected automatically,
 * late installments cost reputation, and a borrower who falls too far behind defaults. A defaulting company goes
 * bankrupt ({@link BankruptcyService}); a defaulting player keeps paying from future income with a damaged reputation.
 *
 * <p>No money is created: principal flows lender → borrower, repayments borrower → lender.
 */
public final class LoanService {

    public record Config(int maxInstallments, int minIntervalHours, int maxIntervalHours, int maxInterestPercent,
                         int offerTtlHours, int graceHours, int defaultAfterOverdue, int lateReputation,
                         int defaultReputation, int repaidReputation, int maxOpenOffers) {
        public static Config defaults() {
            return new Config(52, 24, 720, 100, 72, 24, 3, -3, -20, 2, 10);
        }
    }

    public record Party(Loan.PartyType type, String id) {
        public static Party player(UUID uuid) {
            return new Party(Loan.PartyType.PLAYER, uuid.toString());
        }

        public static Party company(long id) {
            return new Party(Loan.PartyType.COMPANY, Long.toString(id));
        }

        AccountOwner account() {
            return type == Loan.PartyType.PLAYER ? AccountOwner.player(UUID.fromString(id)) : AccountOwner.company(Long.parseLong(id));
        }

        ReputationService.Subject subject() {
            return type == Loan.PartyType.PLAYER ? ReputationService.Subject.player(UUID.fromString(id))
                    : ReputationService.Subject.company(Long.parseLong(id));
        }
    }

    /** Outcome of a collection pass for one loan (for notifications). */
    public record Collection(Loan loan, Money collected, int overdueInstallments, boolean defaulted, boolean repaid) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final ReputationService reputation;
    private final BankruptcyService bankruptcy;
    private final Config config;
    private final Clock clock;

    public LoanService(Database database, EconomyService economy, CompanyService companies, ReputationService reputation,
                       BankruptcyService bankruptcy, Config config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.reputation = reputation;
        this.bankruptcy = bankruptcy;
        this.config = config;
        this.clock = clock;
    }

    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ offers

    public Loan offer(UUID actor, Party lender, Party borrower, Money principal, int interestPercent, int installments, int intervalHours) {
        if (!principal.isPositive() || principal.isGreaterThan(economy.config().maxTransferAmount())) {
            throw new DomainException("economy.invalid_amount");
        }
        if (interestPercent < 0 || interestPercent > config.maxInterestPercent()) {
            throw DomainException.of("loan.invalid_interest", "max", config.maxInterestPercent());
        }
        if (installments < 1 || installments > config.maxInstallments()
                || intervalHours < config.minIntervalHours() || intervalHours > config.maxIntervalHours()) {
            throw new DomainException("loan.invalid_schedule");
        }
        if (lender.equals(borrower)) {
            throw new DomainException("loan.self");
        }
        Money total = Money.ofOre(Math.addExact(principal.ore(), Math.multiplyExact(principal.ore(), interestPercent) / 100));
        return database.inTransaction(tx -> {
            requireControl(tx, lender, actor);
            requireExists(tx, borrower);
            long open = tx.queryLong("SELECT count(*) FROM loans WHERE lender_type = ? AND lender_id = ? AND status = 'OFFERED'",
                    lender.type(), lender.id());
            if (open >= config.maxOpenOffers()) {
                throw DomainException.of("loan.too_many_offers", "max", config.maxOpenOffers());
            }
            long id = tx.queryLong("""
                            INSERT INTO loans (lender_type, lender_id, borrower_type, borrower_id, created_by, principal, total_repayment,
                                               installments, interval_hours, offered_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    lender.type(), lender.id(), borrower.type(), borrower.id(), actor, principal.ore(), total.ore(),
                    installments, intervalHours, clock.instant());
            return find(tx, id).orElseThrow();
        });
    }

    /** The borrower accepts: principal moves to the borrower and the schedule starts. */
    public Loan accept(UUID actor, long loanId) {
        return database.inTransaction(tx -> {
            Loan loan = lock(tx, loanId);
            if (loan.status() != Loan.Status.OFFERED) {
                throw new DomainException("loan.not_offered");
            }
            if (clock.instant().isAfter(loan.offeredAt().plus(Duration.ofHours(config.offerTtlHours())))) {
                tx.update("UPDATE loans SET status = 'WITHDRAWN', closed_at = now() WHERE id = ?", loanId);
                return find(tx, loanId).orElseThrow();
            }
            Party borrower = new Party(loan.borrowerType(), loan.borrowerId());
            Party lender = new Party(loan.lenderType(), loan.lenderId());
            requireControl(tx, borrower, actor);
            if (lender.type() == Loan.PartyType.COMPANY) {
                companies.lockActive(tx, Long.parseLong(lender.id()));
            }
            Account from = economy.requireAccount(tx, lender.account());
            Account to = economy.requireAccount(tx, borrower.account());
            try {
                economy.transfer(tx, new TransferRequest(from.id(), to.id(), loan.principal(), TransactionType.LOAN_PRINCIPAL,
                        "loan-principal:" + loanId, actor, "LOAN", Long.toString(loanId), null));
            } catch (DomainException e) {
                if (e.code().equals("economy.insufficient_funds")) {
                    throw new DomainException("loan.lender_cannot_pay");
                }
                throw e;
            }
            tx.update("UPDATE loans SET status = 'ACTIVE', accepted_at = ? WHERE id = ?", clock.instant(), loanId);
            return find(tx, loanId).orElseThrow();
        });
    }

    public Loan decline(UUID actor, long loanId) {
        return closeOffer(actor, loanId, false);
    }

    public Loan withdraw(UUID actor, long loanId) {
        return closeOffer(actor, loanId, true);
    }

    private Loan closeOffer(UUID actor, long loanId, boolean byLender) {
        return database.inTransaction(tx -> {
            Loan loan = lock(tx, loanId);
            if (loan.status() != Loan.Status.OFFERED) {
                throw new DomainException("loan.not_offered");
            }
            Party party = byLender ? new Party(loan.lenderType(), loan.lenderId()) : new Party(loan.borrowerType(), loan.borrowerId());
            requireControl(tx, party, actor);
            tx.update("UPDATE loans SET status = ?, closed_at = now() WHERE id = ?", byLender ? "WITHDRAWN" : "DECLINED", loanId);
            return find(tx, loanId).orElseThrow();
        });
    }

    /** Borrower repays everything outstanding now. */
    public Loan repayInFull(UUID actor, long loanId) {
        return database.inTransaction(tx -> {
            Loan loan = lock(tx, loanId);
            if (loan.status() != Loan.Status.ACTIVE && loan.status() != Loan.Status.DEFAULTED) {
                throw new DomainException("loan.not_active");
            }
            Party borrower = new Party(loan.borrowerType(), loan.borrowerId());
            requireControl(tx, borrower, actor);
            pay(tx, loan, loan.outstanding(), "EARLY_REPAYMENT", actor);
            finishRepaid(tx, loan);
            return find(tx, loanId).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ collection

    /** Collects due installments of all active loans. Company defaults trigger bankruptcy. */
    public List<Collection> collectDue() {
        List<Long> ids = database.inTransaction(tx -> tx.queryList(
                "SELECT id FROM loans WHERE status IN ('ACTIVE', 'DEFAULTED') ORDER BY id", rs -> rs.getLong(1)));
        List<Collection> results = new ArrayList<>();
        for (long id : ids) {
            Collection result = database.inTransaction(tx -> collect(tx, id));
            if (result == null) {
                continue;
            }
            results.add(result);
            if (result.defaulted() && result.loan().borrowerType() == Loan.PartyType.COMPANY) {
                try {
                    bankruptcy.declare(Long.parseLong(result.loan().borrowerId()), BankruptcyService.Reason.LOAN_DEFAULT, null);
                } catch (DomainException alreadyClosed) {
                    // Company already dissolved or bankrupt.
                }
            }
        }
        return results;
    }

    private Collection collect(Tx tx, long id) throws SQLException {
        Loan loan = lock(tx, id);
        if (loan.status() != Loan.Status.ACTIVE && loan.status() != Loan.Status.DEFAULTED) {
            return null;
        }
        Party borrower = new Party(loan.borrowerType(), loan.borrowerId());
        long due = loan.cumulativeDue(loan.installmentsDue(clock.instant())) - loan.repaid().ore();
        Money collected = Money.ZERO;
        if (due > 0) {
            Money balance = economy.requireAccount(tx, borrower.account()).balance();
            Money amount = Money.ofOre(Math.min(due, balance.ore()));
            if (amount.isPositive()) {
                pay(tx, loan, amount, "INSTALLMENT", null);
                collected = amount;
                loan = find(tx, id).orElseThrow();
            }
        }
        if (loan.repaid().equals(loan.totalRepayment())) {
            finishRepaid(tx, loan);
            return new Collection(find(tx, id).orElseThrow(), collected, 0, false, true);
        }
        int overdue = Math.max(0, loan.installmentsDue(clock.instant().minus(Duration.ofHours(config.graceHours()))) - loan.installmentsCovered());
        // One reputation penalty per late installment, ever (idempotency key per installment index).
        for (int i = loan.installmentsCovered() + 1; i <= loan.installmentsCovered() + overdue; i++) {
            reputation.adjust(tx, borrower.subject(), config.lateReputation(), "LOAN_LATE", "LOAN", Long.toString(id),
                    "loan-late:" + id + ":" + i);
        }
        boolean defaulted = false;
        // Short loans (fewer installments than the threshold) default once everything is overdue.
        if (loan.status() == Loan.Status.ACTIVE && overdue >= Math.min(config.defaultAfterOverdue(), loan.installments())) {
            tx.update("UPDATE loans SET status = 'DEFAULTED' WHERE id = ?", id);
            reputation.adjust(tx, borrower.subject(), config.defaultReputation(), "LOAN_DEFAULT", "LOAN", Long.toString(id), "loan-default:" + id);
            defaulted = true;
        }
        return new Collection(find(tx, id).orElseThrow(), collected, overdue, defaulted, false);
    }

    private void pay(Tx tx, Loan loan, Money amount, String kind, UUID actor) throws SQLException {
        Party borrower = new Party(loan.borrowerType(), loan.borrowerId());
        Party lender = new Party(loan.lenderType(), loan.lenderId());
        Account from = economy.requireAccount(tx, borrower.account());
        Account to = economy.requireAccount(tx, lender.account());
        TransferReceipt receipt = economy.transfer(tx, new TransferRequest(from.id(), to.id(), amount, TransactionType.LOAN_REPAYMENT,
                null, actor, "LOAN", Long.toString(loan.id()), null));
        tx.update("INSERT INTO loan_payments (loan_id, amount, kind, transaction_id, created_at) VALUES (?, ?, ?, ?, ?)",
                loan.id(), amount.ore(), kind, receipt.transactionId(), clock.instant());
        tx.update("UPDATE loans SET repaid = repaid + ? WHERE id = ?", amount.ore(), loan.id());
    }

    private void finishRepaid(Tx tx, Loan loan) throws SQLException {
        tx.update("UPDATE loans SET status = 'REPAID', closed_at = now() WHERE id = ?", loan.id());
        reputation.adjust(tx, new Party(loan.borrowerType(), loan.borrowerId()).subject(), config.repaidReputation(), "LOAN_REPAID",
                "LOAN", Long.toString(loan.id()), "loan-repaid:" + loan.id());
    }

    // ------------------------------------------------------------------ queries

    public Optional<Loan> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    Optional<Loan> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE l.id = ?", LoanService::map, id);
    }

    /** Loans where the player is a party personally or through a company they own. */
    public List<Loan> involving(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE l.status IN ('OFFERED', 'ACTIVE', 'DEFAULTED')
                           AND ((l.lender_type = 'PLAYER' AND l.lender_id = ?::text) OR (l.borrower_type = 'PLAYER' AND l.borrower_id = ?::text)
                             OR (l.lender_type = 'COMPANY' AND l.lender_id IN (SELECT id::text FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE'))
                             OR (l.borrower_type = 'COMPANY' AND l.borrower_id IN (SELECT id::text FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE')))
                         ORDER BY l.id""",
                LoanService::map, player, player, player, player));
    }

    // ------------------------------------------------------------------ helpers

    /** The actor controls a party: is that player, or owns that active company. */
    private void requireControl(Tx tx, Party party, UUID actor) throws SQLException {
        if (party.type() == Loan.PartyType.PLAYER) {
            if (!party.id().equals(actor.toString())) {
                throw new DomainException("loan.not_party");
            }
            return;
        }
        long companyId = Long.parseLong(party.id());
        companies.lockActive(tx, companyId);
        companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
    }

    private void requireExists(Tx tx, Party party) throws SQLException {
        if (party.type() == Loan.PartyType.PLAYER) {
            tx.queryOne("SELECT 1 FROM players WHERE uuid = ?", rs -> true, UUID.fromString(party.id()))
                    .orElseThrow(() -> new DomainException("player.unknown"));
        } else {
            Company company = companies.find(tx, Long.parseLong(party.id())).orElseThrow(() -> new DomainException("company.not_found"));
            if (!company.active()) {
                throw new DomainException("company.not_active");
            }
        }
    }

    private Loan lock(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM loans WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("loan.not_found"));
        return find(tx, id).orElseThrow();
    }

    private static final String SELECT = """
            SELECT l.*, COALESCE(lp.name, lc.name) AS lender_name, COALESCE(bp.name, bc.name) AS borrower_name
            FROM loans l
            LEFT JOIN players lp ON l.lender_type = 'PLAYER' AND lp.uuid::text = l.lender_id
            LEFT JOIN companies lc ON l.lender_type = 'COMPANY' AND lc.id::text = l.lender_id
            LEFT JOIN players bp ON l.borrower_type = 'PLAYER' AND bp.uuid::text = l.borrower_id
            LEFT JOIN companies bc ON l.borrower_type = 'COMPANY' AND bc.id::text = l.borrower_id
            """;

    private static Loan map(ResultSet rs) throws SQLException {
        return new Loan(rs.getLong("id"), Loan.PartyType.valueOf(rs.getString("lender_type")), rs.getString("lender_id"),
                rs.getString("lender_name"), Loan.PartyType.valueOf(rs.getString("borrower_type")), rs.getString("borrower_id"),
                rs.getString("borrower_name"), Money.ofOre(rs.getLong("principal")), Money.ofOre(rs.getLong("total_repayment")),
                Money.ofOre(rs.getLong("repaid")), rs.getInt("installments"), rs.getInt("interval_hours"),
                Loan.Status.valueOf(rs.getString("status")), Tx.instant(rs, "offered_at"), Tx.instant(rs, "accepted_at"));
    }
}
