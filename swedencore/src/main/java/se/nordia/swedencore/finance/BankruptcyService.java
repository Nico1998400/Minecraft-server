package se.nordia.swedencore.finance;

import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.contracts.ContractService;
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
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.events.DomainEvents;
import se.nordia.swedencore.jobs.PayrollService;
import se.nordia.swedencore.orders.BuyOrderService;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.reputation.ReputationService;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Company bankruptcy: the orderly end of a company that cannot pay its debts.
 *
 * <p>Procedure, in one transaction:
 * <ol>
 *   <li>Cancel open contracts and buy orders — their escrow returns to the company account.</li>
 *   <li>Seize company properties back to the market (shops close).</li>
 *   <li>Pay wage arrears first (employees are senior creditors).</li>
 *   <li>Distribute what remains pro rata to loan creditors by outstanding amount.</li>
 *   <li>Anything left after all creditors are paid in full goes to the owner.</li>
 *   <li>Release all staff, mark the company BANKRUPT, record the bankruptcy, damage the owner's reputation.</li>
 * </ol>
 */
public final class BankruptcyService {

    public enum Reason {
        LOAN_DEFAULT, VOLUNTARY
    }

    public record Result(long companyId, Money assets, Money paidWages, Money paidCreditors, Money unpaidDebt, int propertiesSeized) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final PayrollService payroll;
    private final ContractService contracts;
    private final BuyOrderService orders;
    private final PropertyService properties;
    private final ReputationService reputation;
    private final DomainEvents events;
    private final Clock clock;
    private final int ownerReputationPenalty;

    public BankruptcyService(Database database, EconomyService economy, CompanyService companies, PayrollService payroll,
                             ContractService contracts, BuyOrderService orders, PropertyService properties,
                             ReputationService reputation, DomainEvents events, Clock clock, int ownerReputationPenalty) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.payroll = payroll;
        this.contracts = contracts;
        this.orders = orders;
        this.properties = properties;
        this.reputation = reputation;
        this.events = events;
        this.clock = clock;
        this.ownerReputationPenalty = ownerReputationPenalty;
    }

    /**
     * Declares bankruptcy.
     *
     * @param actor the owner for voluntary bankruptcy; null when triggered by the system (loan default)
     */
    public Result declare(long companyId, Reason reason, UUID actor) {
        record Outcome(Result result, List<Long> seized) {
        }
        Outcome outcome = database.inTransaction(tx -> {
            Company company = companies.lockActive(tx, companyId);
            if (reason == Reason.VOLUNTARY) {
                if (actor == null) {
                    throw new DomainException("company.no_permission");
                }
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            }
            // Lock the company's loans early so collection cannot interleave with the distribution.
            record Debt(long loanId, String lenderType, String lenderId, long outstanding) {
            }
            List<Debt> debts = tx.queryList("""
                            SELECT id, lender_type, lender_id, total_repayment - repaid AS outstanding FROM loans
                            WHERE borrower_type = 'COMPANY' AND borrower_id = ? AND status IN ('ACTIVE', 'DEFAULTED')
                            ORDER BY id FOR UPDATE""",
                    rs -> new Debt(rs.getLong("id"), rs.getString("lender_type"), rs.getString("lender_id"), rs.getLong("outstanding")),
                    Long.toString(companyId));
            if (reason == Reason.VOLUNTARY && debts.isEmpty() && !payroll.arrears(tx, companyId).isPositive()) {
                throw new DomainException("bankruptcy.no_debts");
            }

            contracts.closeAllForCompany(tx, companyId);
            orders.closeAllForCompany(tx, companyId);
            List<Long> seized = properties.seizeAllForCompany(tx, companyId);

            Account account = economy.requireAccount(tx, AccountOwner.company(companyId));
            Money assets = account.balance();
            Money arrearsBefore = payroll.arrears(tx, companyId);
            payroll.settleArrears(tx, companyId);
            Money arrearsAfter = payroll.arrears(tx, companyId);
            Money paidWages = arrearsBefore.minus(arrearsAfter);

            long available = economy.requireAccount(tx, AccountOwner.company(companyId)).balance().ore();
            long totalDebt = debts.stream().mapToLong(Debt::outstanding).sum();
            long paidCreditors = 0;
            for (Debt debt : debts) {
                long share = totalDebt <= available ? debt.outstanding()
                        : Math.multiplyExact(available, debt.outstanding()) / Math.max(1, totalDebt);
                share = Math.min(share, debt.outstanding());
                if (share > 0) {
                    AccountOwner lender = "PLAYER".equals(debt.lenderType()) ? AccountOwner.player(UUID.fromString(debt.lenderId()))
                            : AccountOwner.company(Long.parseLong(debt.lenderId()));
                    Account lenderAccount = economy.requireAccount(tx, lender);
                    TransferReceipt receipt = economy.transfer(tx, new TransferRequest(account.id(), lenderAccount.id(), Money.ofOre(share),
                            TransactionType.BANKRUPTCY_DISTRIBUTION, "bankruptcy:" + companyId + ":loan:" + debt.loanId(), null,
                            "LOAN", Long.toString(debt.loanId()), null));
                    tx.update("INSERT INTO loan_payments (loan_id, amount, kind, transaction_id, created_at) VALUES (?, ?, 'BANKRUPTCY_DISTRIBUTION', ?, ?)",
                            debt.loanId(), share, receipt.transactionId(), clock.instant());
                    tx.update("UPDATE loans SET repaid = repaid + ? WHERE id = ?", share, debt.loanId());
                    paidCreditors += share;
                }
                tx.update("UPDATE loans SET status = 'SETTLED_IN_BANKRUPTCY', closed_at = now() WHERE id = ?", debt.loanId());
            }

            Money remaining = economy.requireAccount(tx, AccountOwner.company(companyId)).balance();
            if (remaining.isPositive()) {
                if (totalDebt <= available && !arrearsAfter.isPositive()) {
                    // Everyone was paid in full: the residual belongs to the owner.
                    Account owner = economy.requireAccount(tx, AccountOwner.player(company.ownerUuid()));
                    economy.transfer(tx, TransferRequest.of(account.id(), owner.id(), remaining, TransactionType.DISSOLUTION_PAYOUT)
                            .withReference("COMPANY", Long.toString(companyId)));
                } else {
                    // Rounding dust from pro-rata shares.
                    economy.burn(tx, account.id(), remaining, TransactionType.BANKRUPTCY_DISTRIBUTION, "bankruptcy-dust:" + companyId, null);
                }
            }
            long unpaid = Math.max(0, totalDebt - paidCreditors) + arrearsAfter.ore();

            companies.markBankrupt(tx, companyId);
            if (ownerReputationPenalty != 0) {
                reputation.adjust(tx, ReputationService.Subject.player(company.ownerUuid()), ownerReputationPenalty, "COMPANY_BANKRUPT",
                        "COMPANY", Long.toString(companyId), "bankruptcy-owner:" + companyId);
            }
            tx.update("""
                            INSERT INTO bankruptcies (company_id, reason, assets, paid_wages, paid_creditors, unpaid_debt, properties_seized, declared_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    companyId, reason, assets.ore(), paidWages.ore(), paidCreditors, unpaid, seized.size(), clock.instant());
            return new Outcome(new Result(companyId, assets, paidWages, Money.ofOre(paidCreditors), Money.ofOre(unpaid), seized.size()), seized);
        });
        properties.announceChanged(outcome.seized());
        companies.announceMembershipChange(companyId);
        events.publish(new DomainEvent.CompanyBankrupt(companyId, reason.name()));
        return outcome.result();
    }
}
