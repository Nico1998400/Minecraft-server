package se.nordia.swedencore.companies;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.jobs.PayrollService;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Financial statements derived from the ledger and company records — the basis for future company valuation (P4).
 *
 * <p>Operating result separates running the business from financing: owner deposits/withdrawals and loan principal
 * are cash flows, not profit.
 */
public final class CompanyFinanceService {

    private static final Set<TransactionType> REFUNDS = Set.of(TransactionType.CONTRACT_REFUND, TransactionType.ORDER_REFUND);
    private static final Set<TransactionType> FINANCING = Set.of(
            TransactionType.COMPANY_DEPOSIT, TransactionType.COMPANY_WITHDRAWAL, TransactionType.LOAN_PRINCIPAL,
            TransactionType.DISSOLUTION_PAYOUT);

    public record IncomeStatement(int days, Money revenue, Money wages, Money purchasing, Money fees, Money otherCosts,
                                  Money operatingResult, Money financingIn, Money financingOut) {
    }

    public record BalanceSheet(Money cash, Money properties, Money receivables, Money debt, Money wageArrears, Money equity,
                               int employees) {
    }

    public record Report(Company company, IncomeStatement income, BalanceSheet balance) {
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final PayrollService payroll;

    public CompanyFinanceService(Database database, EconomyService economy, CompanyService companies, PayrollService payroll) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.payroll = payroll;
    }

    /** Owners and managers may see the books. */
    public Report report(UUID actor, long companyId, int days) {
        int window = Math.clamp(days, 1, 365);
        return database.inTransaction(tx -> {
            Company company = companies.find(tx, companyId).orElseThrow(() -> new DomainException("company.not_found"));
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            return new Report(company, income(tx, companyId, window), balance(tx, companyId));
        });
    }

    private IncomeStatement income(Tx tx, long companyId, int days) throws SQLException {
        Account account = economy.requireAccount(tx, AccountOwner.company(companyId));
        Map<TransactionType, long[]> flows = new EnumMap<>(TransactionType.class);
        // Ledger timestamps are audit data written by the database, so the reporting window uses database time.
        for (var row : tx.queryList("""
                        SELECT type,
                               COALESCE(SUM(CASE WHEN to_account_id = ? THEN amount END), 0) AS incoming,
                               COALESCE(SUM(CASE WHEN from_account_id = ? THEN amount END), 0) AS outgoing
                        FROM transactions
                        WHERE (to_account_id = ? OR from_account_id = ?) AND created_at >= now() - make_interval(days => ?)
                        GROUP BY type""",
                rs -> Map.entry(TransactionType.valueOf(rs.getString("type")), new long[]{rs.getLong("incoming"), rs.getLong("outgoing")}),
                account.id(), account.id(), account.id(), account.id(), days)) {
            flows.put(row.getKey(), row.getValue());
        }
        long revenue = 0;
        long wages = 0;
        long purchasing = 0;
        long fees = 0;
        long other = 0;
        long financingIn = 0;
        long financingOut = 0;
        for (Map.Entry<TransactionType, long[]> e : flows.entrySet()) {
            long in = e.getValue()[0];
            long out = e.getValue()[1];
            TransactionType type = e.getKey();
            if (FINANCING.contains(type)) {
                financingIn += in;
                financingOut += out;
            } else if (type == TransactionType.SALARY) {
                wages += out;
            } else if (type == TransactionType.CONTRACT_ESCROW || type == TransactionType.ORDER_ESCROW) {
                purchasing += out - in;
            } else if (REFUNDS.contains(type)) {
                purchasing -= in;
            } else if (type == TransactionType.CONTRACT_FEE || type == TransactionType.ORDER_FEE
                    || type == TransactionType.COMPANY_REGISTRATION_FEE) {
                fees += out;
            } else {
                // Sales, property deals, loan repayments received/paid, trades and anything else operational.
                revenue += in;
                other += out;
            }
        }
        long result = revenue - wages - purchasing - fees - other;
        return new IncomeStatement(days, Money.ofOre(revenue), Money.ofOre(wages), Money.ofOre(purchasing), Money.ofOre(fees),
                Money.ofOre(other), Money.ofOre(result), Money.ofOre(financingIn), Money.ofOre(financingOut));
    }

    private BalanceSheet balance(Tx tx, long companyId) throws SQLException {
        String id = Long.toString(companyId);
        Money cash = economy.findAccount(tx, AccountOwner.company(companyId), Account.MAIN).map(Account::balance).orElse(Money.ZERO);
        Money propertyValue = Money.ofOre(tx.queryLong(
                "SELECT COALESCE(SUM(market_value), 0) FROM properties WHERE owner_type = 'COMPANY' AND owner_id = ?", id));
        Money receivables = Money.ofOre(tx.queryLong("""
                SELECT COALESCE(SUM(total_repayment - repaid), 0) FROM loans
                WHERE lender_type = 'COMPANY' AND lender_id = ? AND status IN ('ACTIVE', 'DEFAULTED')""", id));
        Money debt = Money.ofOre(tx.queryLong("""
                SELECT COALESCE(SUM(total_repayment - repaid), 0) FROM loans
                WHERE borrower_type = 'COMPANY' AND borrower_id = ? AND status IN ('ACTIVE', 'DEFAULTED')""", id));
        Money arrears = payroll.arrears(tx, companyId);
        int employees = (int) tx.queryLong("SELECT count(*) FROM company_employees WHERE company_id = ? AND ended_at IS NULL", companyId);
        Money equity = cash.plus(propertyValue).plus(receivables).minus(debt).minus(arrears);
        return new BalanceSheet(cash, propertyValue, receivables, debt, arrears, equity, employees);
    }
}
