package se.nordia.swedencore.jobs;

import se.nordia.swedencore.companies.CompanyConfig;
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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pays employees for verified work minutes.
 *
 * <p>The Paper layer reports batches of minutes in which an on-duty employee demonstrably worked (gained XP in the
 * job's skill while not AFK). Each batch becomes one {@code payroll_entries} row, unique per (employee, period start),
 * so retries never pay twice. Wages are paid from the company account; if it cannot pay, the entry stays UNPAID
 * (arrears), the company loses reputation, and arrears are settled oldest-first whenever the company has money.
 */
public final class PayrollService {

    public record PayrollResult(long entryId, Money amount, boolean paid, boolean duplicate) {
    }

    public record PayrollEntry(long id, long companyId, String companyName, int workMinutes, Money amount, boolean paid,
                               Instant createdAt) {
    }

    private final Database database;
    private final EconomyService economy;
    private final ReputationService reputation;
    private final CompanyConfig config;
    private final Clock clock;

    public PayrollService(Database database, EconomyService economy, ReputationService reputation, CompanyConfig config, Clock clock) {
        this.database = database;
        this.economy = economy;
        this.reputation = reputation;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Records and pays a batch of work minutes.
     *
     * @param periodStart start of the first worked minute in this batch (unique per batch)
     * @return empty if the employee has no salary (nothing to pay)
     */
    public Optional<PayrollResult> recordWork(long employeeId, Instant periodStart, int minutes) {
        if (minutes < 1 || minutes > config.payrollIntervalMinutes() * 2 + 1) {
            throw new IllegalArgumentException("Work minutes out of range: " + minutes);
        }
        if (periodStart.isAfter(clock.instant().plus(Duration.ofMinutes(1)))) {
            throw new IllegalArgumentException("Work period in the future: " + periodStart);
        }
        return database.inTransaction(tx -> {
            Long companyId = tx.queryOne("SELECT company_id FROM company_employees WHERE id = ?", rs -> rs.getLong(1), employeeId)
                    .orElseThrow(() -> new DomainException("payroll.not_employed"));
            String status = tx.queryOne("SELECT status FROM companies WHERE id = ? FOR UPDATE", rs -> rs.getString(1), companyId)
                    .orElseThrow();
            record Emp(UUID player, long salary, boolean active) {
            }
            Emp emp = tx.queryOne("SELECT player_uuid, salary_per_hour, ended_at IS NULL AS active FROM company_employees WHERE id = ? FOR UPDATE",
                    rs -> new Emp(Tx.uuid(rs, "player_uuid"), rs.getLong("salary_per_hour"), rs.getBoolean("active")), employeeId).orElseThrow();
            if (!emp.active() || !"ACTIVE".equals(status)) {
                throw new DomainException("payroll.not_employed");
            }
            if (emp.salary() == 0) {
                return Optional.empty();
            }
            long amount = Math.multiplyExact(emp.salary(), minutes) / 60;
            if (amount == 0) {
                return Optional.empty();
            }
            Optional<Long> entryId = tx.queryOne("""
                            INSERT INTO payroll_entries (employee_id, company_id, player_uuid, period_start, work_minutes,
                                                         salary_per_hour, amount, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'UNPAID')
                            ON CONFLICT (employee_id, period_start) DO NOTHING
                            RETURNING id""",
                    rs -> rs.getLong(1), employeeId, companyId, emp.player(), periodStart, minutes, emp.salary(), amount);
            if (entryId.isEmpty()) {
                PayrollResult existing = tx.queryOne("""
                                SELECT id, amount, status FROM payroll_entries WHERE employee_id = ? AND period_start = ?""",
                        rs -> new PayrollResult(rs.getLong("id"), Money.ofOre(rs.getLong("amount")),
                                "PAID".equals(rs.getString("status")), true), employeeId, periodStart).orElseThrow();
                return Optional.of(existing);
            }
            settleArrears(tx, companyId);
            boolean paid = tx.queryOne("SELECT status FROM payroll_entries WHERE id = ?", rs -> "PAID".equals(rs.getString(1)),
                    entryId.get()).orElseThrow();
            if (!paid) {
                reputation.adjust(tx, ReputationService.Subject.company(companyId), config.wageDefaultReputation(),
                        "WAGE_DEFAULT", "PAYROLL", entryId.get().toString(), "wage-default:" + entryId.get());
            }
            return Optional.of(new PayrollResult(entryId.get(), Money.ofOre(amount), paid, false));
        });
    }

    /**
     * Pays unpaid wages oldest-first while the company account can afford them. The caller must hold the company
     * row lock. Returns the number of entries paid.
     */
    public int settleArrears(Tx tx, long companyId) throws SQLException {
        record Unpaid(long id, UUID player, long amount) {
        }
        List<Unpaid> unpaid = tx.queryList("""
                        SELECT id, player_uuid, amount FROM payroll_entries
                        WHERE company_id = ? AND status = 'UNPAID' ORDER BY id FOR UPDATE""",
                rs -> new Unpaid(rs.getLong("id"), Tx.uuid(rs, "player_uuid"), rs.getLong("amount")), companyId);
        if (unpaid.isEmpty()) {
            return 0;
        }
        Account companyAccount = economy.requireAccount(tx, AccountOwner.company(companyId));
        int paid = 0;
        for (Unpaid entry : unpaid) {
            Account playerAccount = economy.requireAccount(tx, AccountOwner.player(entry.player()));
            TransferReceipt receipt;
            try {
                receipt = economy.transfer(tx, new TransferRequest(companyAccount.id(), playerAccount.id(),
                        Money.ofOre(entry.amount()), TransactionType.SALARY, "payroll:" + entry.id(), null,
                        "PAYROLL", Long.toString(entry.id()), null));
            } catch (DomainException e) {
                // Insufficient funds (or frozen account): stop; older entries must be paid first.
                break;
            }
            tx.update("UPDATE payroll_entries SET status = 'PAID', paid_at = now(), transaction_id = ? WHERE id = ?",
                    receipt.transactionId(), entry.id());
            paid++;
        }
        return paid;
    }

    public Money arrears(Tx tx, long companyId) throws SQLException {
        return Money.ofOre(tx.queryLong(
                "SELECT COALESCE(SUM(amount), 0) FROM payroll_entries WHERE company_id = ? AND status = 'UNPAID'", companyId));
    }

    public Money arrears(long companyId) {
        return database.inTransaction(tx -> arrears(tx, companyId));
    }

    public List<PayrollEntry> recentForPlayer(UUID player, int limit) {
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT pe.id, pe.company_id, c.name, pe.work_minutes, pe.amount, pe.status, pe.created_at
                        FROM payroll_entries pe JOIN companies c ON c.id = pe.company_id
                        WHERE pe.player_uuid = ? ORDER BY pe.id DESC LIMIT ?""",
                rs -> new PayrollEntry(rs.getLong("id"), rs.getLong("company_id"), rs.getString("name"),
                        rs.getInt("work_minutes"), Money.ofOre(rs.getLong("amount")), "PAID".equals(rs.getString("status")),
                        Tx.instant(rs, "created_at")),
                player, Math.clamp(limit, 1, 50)));
    }
}
