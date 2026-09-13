package se.nordia.swedencore.contracts;

import se.nordia.swedencore.economy.Money;

/**
 * @param feePercent                 creation fee as % of the reward, destroyed (sink); makes collusive money
 *                                   round-trips (e.g. to farm XP or reputation) cost something
 * @param maxActivePerIssuer         OPEN/IN_PROGRESS contracts per issuing player or company
 * @param maxActivePerContractor     IN_PROGRESS contracts per contractor
 * @param maxDurationHours           longest allowed deadline
 * @param sekPerXp                   contract XP = reward SEK / sekPerXp (only with a required skill)
 * @param maxXpPerContract           cap on contract XP
 * @param minRewardForReputation     smaller contracts give no reputation (anti-farming)
 */
public record ContractConfig(
        int feePercent,
        int maxActivePerIssuer,
        int maxActivePerContractor,
        int maxDurationHours,
        long sekPerXp,
        long maxXpPerContract,
        Money minRewardForReputation,
        int contractorCompletionReputation,
        int issuerCompletionReputation,
        int abandonReputation,
        int expiryReputation
) {
    public ContractConfig {
        if (feePercent < 0 || feePercent > 50 || maxActivePerIssuer < 1 || maxActivePerContractor < 1
                || maxDurationHours < 1 || sekPerXp < 1 || maxXpPerContract < 0) {
            throw new IllegalArgumentException("Invalid contract configuration");
        }
    }

    public static ContractConfig defaults() {
        return new ContractConfig(2, 10, 5, 24 * 30, 20, 5_000, Money.ofSek(500), 2, 1, -3, -2);
    }
}
