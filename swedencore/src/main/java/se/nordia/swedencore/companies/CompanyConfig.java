package se.nordia.swedencore.companies;

import se.nordia.swedencore.economy.Money;

/**
 * @param registrationFee        paid by the founder; destroyed (sink) until cities receive fees
 * @param maxOwnedCompanies      active companies a single player may own
 * @param maxOpenPositions       open positions per company
 * @param maxSalaryPerHour       upper bound for salaries (typo/exploit guard)
 * @param maxPendingApplications pending applications per player
 * @param payrollIntervalMinutes how often verified work minutes are paid
 * @param wageDefaultReputation  reputation change for a company per unpaid payroll entry (negative)
 */
public record CompanyConfig(
        Money registrationFee,
        int maxOwnedCompanies,
        int maxOpenPositions,
        Money maxSalaryPerHour,
        int maxPendingApplications,
        int payrollIntervalMinutes,
        int wageDefaultReputation
) {
    public CompanyConfig {
        if (registrationFee.isNegative() || maxOwnedCompanies < 1 || maxOpenPositions < 1 || !maxSalaryPerHour.isPositive()
                || maxPendingApplications < 1 || payrollIntervalMinutes < 1 || payrollIntervalMinutes > 60
                || wageDefaultReputation > 0) {
            throw new IllegalArgumentException("Invalid company configuration");
        }
    }

    public static CompanyConfig defaults() {
        return new CompanyConfig(Money.ofSek(5_000), 3, 20, Money.ofSek(100_000), 10, 5, -1);
    }
}
