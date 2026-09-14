package se.nordia.swedencore.companies;

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
import se.nordia.swedencore.skills.Skill;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Companies are first-class economic entities with their own account, members and reputation.
 *
 * <p>Permission checks live here (not only in commands): every mutating method verifies the actor's role inside the
 * same transaction that performs the change, with the company row locked.
 */
public final class CompanyService {

    /** Letters incl. Swedish characters, digits, space, ampersand, dot, hyphen. Latin only to avoid homoglyph impersonation. */
    private static final Pattern NAME = Pattern.compile("[A-Za-zÅÄÖåäöÉéÜü0-9](?:[A-Za-zÅÄÖåäöÉéÜü0-9&.\\-]| (?! )){1,30}[A-Za-zÅÄÖåäöÉéÜü0-9.]");
    private static final Pattern ID_REF = Pattern.compile("#?(\\d{1,18})");

    public record Membership(Company company, CompanyRole role, String positionTitle, Money salaryPerHour) {
    }

    public record Summary(Company company, String ownerName, Money balance, int employeeCount, Money arrears) {
    }

    private final Database database;
    private final EconomyService economy;
    private final PayrollService payroll;
    private final CompanyConfig config;
    private final DomainEvents events;

    public CompanyService(Database database, EconomyService economy, PayrollService payroll, CompanyConfig config, DomainEvents events) {
        this.database = database;
        this.economy = economy;
        this.payroll = payroll;
        this.config = config;
        this.events = events;
    }

    private void membershipChanged(long companyId) {
        events.publish(new DomainEvent.CompanyMembershipChanged(companyId));
    }

    public CompanyConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ founding

    public static String normalizeName(String raw) {
        if (raw == null) {
            throw new DomainException("company.invalid_name");
        }
        String name = raw.trim();
        if (!NAME.matcher(name).matches()) {
            throw new DomainException("company.invalid_name");
        }
        return name;
    }

    public Company found(UUID owner, String rawName) {
        String name = normalizeName(rawName);
        Company company = database.inTransaction(tx -> {
            // Serialise founding per player so the ownership limit cannot be raced.
            tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, owner)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            long owned = tx.queryLong("SELECT count(*) FROM companies WHERE owner_uuid = ? AND status = 'ACTIVE'", owner);
            if (owned >= config.maxOwnedCompanies()) {
                throw DomainException.of("company.too_many_owned", "max", config.maxOwnedCompanies());
            }
            if (tx.queryOne("SELECT 1 FROM companies WHERE lower(name) = lower(?) AND status = 'ACTIVE'", rs -> true, name).isPresent()) {
                throw DomainException.of("company.name_taken", "name", name);
            }
            Account ownerAccount = economy.requireAccount(tx, AccountOwner.player(owner));
            if (config.registrationFee().isPositive()) {
                economy.burn(tx, ownerAccount.id(), config.registrationFee(), TransactionType.COMPANY_REGISTRATION_FEE, null, owner);
            }
            long id;
            try {
                id = tx.queryLong("INSERT INTO companies (name, owner_uuid) VALUES (?, ?) RETURNING id", name, owner);
            } catch (SQLException e) {
                if (Database.isUniqueViolation(e)) {
                    // Concurrent founding with the same name won the race.
                    throw DomainException.of("company.name_taken", "name", name);
                }
                throw e;
            }
            economy.getOrCreateAccount(tx, AccountOwner.company(id), Account.MAIN);
            tx.update("INSERT INTO company_employees (company_id, player_uuid, role) VALUES (?, ?, 'OWNER')", id, owner);
            Company founded = find(tx, id).orElseThrow();
            for (FoundingHook hook : foundingHooks) {
                hook.founded(tx, founded);
            }
            return founded;
        });
        membershipChanged(company.id());
        return company;
    }

    // ------------------------------------------------------------------ queries

    public Optional<Company> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    public Optional<Company> find(Tx tx, long id) throws SQLException {
        return tx.queryOne("SELECT * FROM companies WHERE id = ?", CompanyService::map, id);
    }

    /** Resolves {@code #12}, {@code 12} or an active company name (case-insensitive). */
    public Optional<Company> findByRef(Tx tx, String ref) throws SQLException {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String trimmed = ref.trim();
        var idMatch = ID_REF.matcher(trimmed);
        if (idMatch.matches()) {
            Optional<Company> byId = find(tx, Long.parseLong(idMatch.group(1)));
            if (byId.isPresent()) {
                return byId;
            }
        }
        return tx.queryOne("SELECT * FROM companies WHERE lower(name) = lower(?) AND status = 'ACTIVE'", CompanyService::map, trimmed);
    }

    public Company requireByRef(String ref) {
        return database.inTransaction(tx -> findByRef(tx, ref))
                .orElseThrow(() -> DomainException.of("company.not_found", "company", ref == null ? "" : ref));
    }

    /**
     * Resolves which company a command refers to. With an explicit reference, that company. Without one, the single
     * company in which the actor has one of the given roles.
     */
    public Company resolveForActor(UUID actor, String refOrNull, CompanyRole... roles) {
        if (refOrNull != null && !refOrNull.isBlank()) {
            return requireByRef(refOrNull);
        }
        List<Membership> candidates = memberships(actor).stream()
                .filter(m -> roles.length == 0 || Arrays.asList(roles).contains(m.role()))
                .toList();
        if (candidates.isEmpty()) {
            throw new DomainException("company.no_membership");
        }
        if (candidates.size() > 1) {
            throw new DomainException("company.specify");
        }
        return candidates.getFirst().company();
    }

    public List<Membership> memberships(UUID player) {
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT c.*, e.role AS member_role, p.title AS position_title, e.salary_per_hour AS member_salary
                        FROM company_employees e
                        JOIN companies c ON c.id = e.company_id
                        LEFT JOIN job_positions p ON p.id = e.position_id
                        WHERE e.player_uuid = ? AND e.ended_at IS NULL AND c.status = 'ACTIVE'
                        ORDER BY c.id""",
                rs -> new Membership(map(rs), CompanyRole.valueOf(rs.getString("member_role")),
                        rs.getString("position_title"), Money.ofOre(rs.getLong("member_salary"))),
                player));
    }

    public List<Employee> employees(long companyId) {
        return database.inTransaction(tx -> employees(tx, companyId));
    }

    public List<Employee> employees(Tx tx, long companyId) throws SQLException {
        return tx.queryList(EMPLOYEE_SELECT + " WHERE e.company_id = ? AND e.ended_at IS NULL ORDER BY e.role, pl.name",
                CompanyService::mapEmployee, companyId);
    }

    public List<Employee> activeEmploymentsOf(UUID player) {
        return database.inTransaction(tx -> tx.queryList(
                EMPLOYEE_SELECT + " WHERE e.player_uuid = ? AND e.ended_at IS NULL AND c.status = 'ACTIVE' ORDER BY e.id",
                CompanyService::mapEmployee, player));
    }

    public Summary summary(long companyId) {
        return database.inTransaction(tx -> {
            Company company = find(tx, companyId).orElseThrow(() -> new DomainException("company.not_found"));
            String ownerName = tx.queryOne("SELECT name FROM players WHERE uuid = ?", rs -> rs.getString(1), company.ownerUuid()).orElse("?");
            Money balance = economy.findAccount(tx, AccountOwner.company(companyId), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
            int count = (int) tx.queryLong("SELECT count(*) FROM company_employees WHERE company_id = ? AND ended_at IS NULL", companyId);
            return new Summary(company, ownerName, balance, count, payroll.arrears(tx, companyId));
        });
    }

    // ------------------------------------------------------------------ permissions

    /** Locks the company row and returns it; fails unless active. */
    public Company lockActive(Tx tx, long companyId) throws SQLException {
        Company company = tx.queryOne("SELECT * FROM companies WHERE id = ? FOR UPDATE", CompanyService::map, companyId)
                .orElseThrow(() -> new DomainException("company.not_found"));
        if (!company.active()) {
            throw new DomainException("company.not_active");
        }
        return company;
    }

    public Optional<CompanyRole> roleOf(Tx tx, long companyId, UUID player) throws SQLException {
        return tx.queryOne("SELECT role FROM company_employees WHERE company_id = ? AND player_uuid = ? AND ended_at IS NULL",
                rs -> CompanyRole.valueOf(rs.getString(1)), companyId, player);
    }

    public CompanyRole requireRole(Tx tx, long companyId, UUID actor, CompanyRole... allowed) throws SQLException {
        CompanyRole role = roleOf(tx, companyId, actor).orElseThrow(() -> new DomainException("company.not_member"));
        if (!Arrays.asList(allowed).contains(role)) {
            throw new DomainException("company.no_permission");
        }
        return role;
    }

    // ------------------------------------------------------------------ money

    /** Any active member may put money into the company. Arrears are settled immediately. */
    public TransferReceipt deposit(UUID actor, long companyId, Money amount) {
        return database.inTransaction(tx -> {
            lockActive(tx, companyId);
            requireRole(tx, companyId, actor, CompanyRole.values());
            Account from = economy.requireAccount(tx, AccountOwner.player(actor));
            Account to = economy.requireAccount(tx, AccountOwner.company(companyId));
            TransferReceipt receipt = economy.transfer(tx, TransferRequest.of(from.id(), to.id(), amount, TransactionType.COMPANY_DEPOSIT)
                    .withActor(actor).withReference("COMPANY", Long.toString(companyId)));
            payroll.settleArrears(tx, companyId);
            return receipt;
        });
    }

    /** Only the owner may withdraw, and never while employees are owed wages. */
    public TransferReceipt withdraw(UUID actor, long companyId, Money amount) {
        return database.inTransaction(tx -> {
            lockActive(tx, companyId);
            requireRole(tx, companyId, actor, CompanyRole.OWNER);
            if (payroll.arrears(tx, companyId).isPositive()) {
                throw DomainException.of("company.withdraw_blocked_by_arrears", "arrears", payroll.arrears(tx, companyId));
            }
            for (DissolutionCheck check : withdrawalChecks) {
                check.verify(tx, companyId);
            }
            Account from = economy.requireAccount(tx, AccountOwner.company(companyId));
            Account to = economy.requireAccount(tx, AccountOwner.player(actor));
            return economy.transfer(tx, TransferRequest.of(from.id(), to.id(), amount, TransactionType.COMPANY_WITHDRAWAL)
                    .withActor(actor).withReference("COMPANY", Long.toString(companyId)));
        });
    }

    public Money balance(long companyId) {
        return economy.balance(AccountOwner.company(companyId));
    }

    // ------------------------------------------------------------------ staff

    public void setRole(UUID actor, long companyId, UUID target, CompanyRole newRole) {
        if (newRole == CompanyRole.OWNER) {
            throw new DomainException("company.no_permission");
        }
        database.inTransactionVoid(tx -> {
            lockActive(tx, companyId);
            requireRole(tx, companyId, actor, CompanyRole.OWNER);
            CompanyRole current = roleOf(tx, companyId, target).orElseThrow(() -> new DomainException("company.target_not_member"));
            if (current == CompanyRole.OWNER) {
                throw new DomainException("company.no_permission");
            }
            tx.update("UPDATE company_employees SET role = ? WHERE company_id = ? AND player_uuid = ? AND ended_at IS NULL",
                    newRole, companyId, target);
        });
    }

    public void setSalary(UUID actor, long companyId, UUID target, Money salaryPerHour) {
        validateSalary(salaryPerHour);
        database.inTransactionVoid(tx -> {
            lockActive(tx, companyId);
            CompanyRole actorRole = requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            CompanyRole targetRole = roleOf(tx, companyId, target).orElseThrow(() -> new DomainException("company.target_not_member"));
            if (targetRole == CompanyRole.OWNER || (actorRole == CompanyRole.MANAGER && targetRole != CompanyRole.EMPLOYEE)) {
                throw new DomainException("company.no_permission");
            }
            tx.update("UPDATE company_employees SET salary_per_hour = ? WHERE company_id = ? AND player_uuid = ? AND ended_at IS NULL",
                    salaryPerHour.ore(), companyId, target);
        });
    }

    public void validateSalary(Money salaryPerHour) {
        if (salaryPerHour.isNegative() || salaryPerHour.isGreaterThan(config.maxSalaryPerHour())) {
            throw DomainException.of("company.invalid_salary", "max", config.maxSalaryPerHour());
        }
    }

    /** Owner may terminate anyone but themselves; managers may terminate employees. */
    public void terminate(UUID actor, long companyId, UUID target) {
        database.inTransactionVoid(tx -> {
            lockActive(tx, companyId);
            CompanyRole actorRole = requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            CompanyRole targetRole = roleOf(tx, companyId, target).orElseThrow(() -> new DomainException("company.target_not_member"));
            if (targetRole == CompanyRole.OWNER || (actorRole == CompanyRole.MANAGER && targetRole != CompanyRole.EMPLOYEE)) {
                throw new DomainException("company.no_permission");
            }
            endMembership(tx, companyId, target, "TERMINATED");
        });
        membershipChanged(companyId);
    }

    public void leave(UUID player, long companyId) {
        database.inTransactionVoid(tx -> {
            lockActive(tx, companyId);
            CompanyRole role = requireRole(tx, companyId, player, CompanyRole.values());
            if (role == CompanyRole.OWNER) {
                throw new DomainException("company.owner_cannot_leave");
            }
            endMembership(tx, companyId, player, "LEFT");
        });
        membershipChanged(companyId);
    }

    private static void endMembership(Tx tx, long companyId, UUID player, String reason) throws SQLException {
        tx.update("UPDATE company_employees SET ended_at = now(), end_reason = ? WHERE company_id = ? AND player_uuid = ? AND ended_at IS NULL",
                reason, companyId, player);
    }

    /**
     * Dissolves a company: requires no other members, no unpaid wages and no open contracts. The remaining balance is
     * paid out to the owner.
     */
    public Money dissolve(UUID actor, long companyId) {
        Money payout = database.inTransaction(tx -> {
            lockActive(tx, companyId);
            requireRole(tx, companyId, actor, CompanyRole.OWNER);
            long others = tx.queryLong("SELECT count(*) FROM company_employees WHERE company_id = ? AND ended_at IS NULL AND role <> 'OWNER'", companyId);
            if (others > 0) {
                throw new DomainException("company.dissolve_has_employees");
            }
            if (payroll.arrears(tx, companyId).isPositive()) {
                throw new DomainException("company.dissolve_has_arrears");
            }
            for (DissolutionCheck check : dissolutionChecks) {
                check.verify(tx, companyId);
            }
            Account companyAccount = economy.requireAccount(tx, AccountOwner.company(companyId));
            Money remaining = companyAccount.balance();
            closeEquity(tx, find(tx, companyId).orElseThrow(), remaining, actor);
            tx.update("UPDATE job_applications SET status = 'CLOSED', decided_at = now() WHERE status = 'PENDING' AND position_id IN (SELECT id FROM job_positions WHERE company_id = ?)", companyId);
            tx.update("UPDATE job_positions SET status = 'CLOSED', closed_at = now() WHERE company_id = ? AND status = 'OPEN'", companyId);
            endMembership(tx, companyId, actor, "DISSOLVED");
            tx.update("UPDATE companies SET status = 'DISSOLVED', dissolved_at = now() WHERE id = ?", companyId);
            return remaining;
        });
        membershipChanged(companyId);
        return payout;
    }

    /** Bankruptcy: closes hiring, releases all staff and marks the company BANKRUPT. The caller holds the company lock. */
    public void markBankrupt(Tx tx, long companyId) throws SQLException {
        tx.update("UPDATE job_applications SET status = 'CLOSED', decided_at = now() WHERE status = 'PENDING' AND position_id IN (SELECT id FROM job_positions WHERE company_id = ?)", companyId);
        tx.update("UPDATE job_positions SET status = 'CLOSED', closed_at = now() WHERE company_id = ? AND status = 'OPEN'", companyId);
        tx.update("UPDATE company_employees SET ended_at = now(), end_reason = 'BANKRUPTCY' WHERE company_id = ? AND ended_at IS NULL", companyId);
        tx.update("UPDATE companies SET status = 'BANKRUPT', dissolved_at = now() WHERE id = ?", companyId);
    }

    /** Lets other services (e.g. hiring) announce membership changes they performed. */
    public void announceMembershipChange(long companyId) {
        membershipChanged(companyId);
    }

    /** Extension point so later modules (contracts, properties, shops) can veto dissolution. */
    @FunctionalInterface
    public interface DissolutionCheck {
        void verify(Tx tx, long companyId) throws SQLException;
    }

    private final List<DissolutionCheck> dissolutionChecks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<DissolutionCheck> withdrawalChecks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<FoundingHook> foundingHooks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile EquityCloser equityCloser = CompanyService::payResidualToOwner;

    public void addDissolutionCheck(DissolutionCheck check) {
        dissolutionChecks.add(check);
    }

    /** Vetoes owner withdrawals (e.g. while outside shareholders exist). Runs with the company locked. */
    public void addWithdrawalCheck(DissolutionCheck check) {
        withdrawalChecks.add(check);
    }

    /** Runs inside the founding transaction (e.g. to issue the initial shares). */
    @FunctionalInterface
    public interface FoundingHook {
        void founded(Tx tx, Company company) throws SQLException;
    }

    public void addFoundingHook(FoundingHook hook) {
        foundingHooks.add(hook);
    }

    /**
     * Decides who receives a closing company's remaining money (dissolution, or bankruptcy after all creditors were
     * paid). Called with the company locked; {@code residual} may be zero, in which case only bookkeeping happens.
     */
    @FunctionalInterface
    public interface EquityCloser {
        void close(CompanyService companies, Tx tx, Company company, Money residual, UUID actor) throws SQLException;
    }

    public void setEquityCloser(EquityCloser closer) {
        this.equityCloser = java.util.Objects.requireNonNull(closer);
    }

    public void closeEquity(Tx tx, Company company, Money residual, UUID actor) throws SQLException {
        equityCloser.close(this, tx, company, residual, actor);
    }

    private static void payResidualToOwner(CompanyService companies, Tx tx, Company company, Money residual, UUID actor) throws SQLException {
        companies.payFromCompany(tx, company.id(), AccountOwner.player(company.ownerUuid()), residual, actor, "dissolution:" + company.id());
    }

    /** Pays out closing equity from the company account (type DISSOLUTION_PAYOUT). No-op for non-positive amounts. */
    public void payFromCompany(Tx tx, long companyId, AccountOwner to, Money amount, UUID actor, String idempotencyKey) throws SQLException {
        if (!amount.isPositive()) {
            return;
        }
        Account from = economy.requireAccount(tx, AccountOwner.company(companyId));
        Account target = economy.requireAccount(tx, to);
        economy.transfer(tx, new TransferRequest(from.id(), target.id(), amount, TransactionType.DISSOLUTION_PAYOUT, idempotencyKey,
                actor, "COMPANY", Long.toString(companyId), null));
    }

    // ------------------------------------------------------------------ mapping

    static final String EMPLOYEE_SELECT = """
            SELECT e.id, e.company_id, c.name AS company_name, e.player_uuid, pl.name AS player_name, e.role,
                   e.position_id, p.title, p.job_id, j.skill_id, e.salary_per_hour, e.hired_at
            FROM company_employees e
            JOIN companies c ON c.id = e.company_id
            JOIN players pl ON pl.uuid = e.player_uuid
            LEFT JOIN job_positions p ON p.id = e.position_id
            LEFT JOIN jobs j ON j.id = p.job_id
            """;

    static Employee mapEmployee(ResultSet rs) throws SQLException {
        String skill = rs.getString("skill_id");
        return new Employee(rs.getLong("id"), rs.getLong("company_id"), rs.getString("company_name"),
                Tx.uuid(rs, "player_uuid"), rs.getString("player_name"), CompanyRole.valueOf(rs.getString("role")),
                Tx.nullableLong(rs, "position_id"), rs.getString("title"), rs.getString("job_id"),
                skill == null ? null : Skill.valueOf(skill), Money.ofOre(rs.getLong("salary_per_hour")),
                Tx.instant(rs, "hired_at"));
    }

    static Company map(ResultSet rs) throws SQLException {
        return new Company(rs.getLong("id"), rs.getString("name"), Tx.uuid(rs, "owner_uuid"),
                Company.Status.valueOf(rs.getString("status")), rs.getInt("reputation"), Tx.instant(rs, "founded_at"));
    }
}
