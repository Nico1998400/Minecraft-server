package se.nordia.swedencore.settlements;

import se.nordia.swedencore.economy.Money;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tier requirements. Reaching a tier needs members, treasury, age and leader reputation; the upgrade cost is paid from
 * the treasury into the sink (public works). The OUTPOST entry describes founding.
 *
 * @param spacingBuffer extra blocks kept free between the largest possible settlement area and cities/other settlements
 * @param maxInvites    pending invites per settlement
 */
public record SettlementConfig(Map<Settlement.Tier, Requirements> tiers, int spacingBuffer, int maxInvites) {

    /**
     * @param cost             paid when reaching this tier (founding cost for OUTPOST, paid by the founder)
     * @param minTreasury      treasury balance required before paying the cost
     */
    public record Requirements(int radius, int minMembers, Money minTreasury, int minAgeDays, int minLeaderReputation, Money cost) {
    }

    public SettlementConfig {
        tiers = Map.copyOf(tiers);
        for (Settlement.Tier tier : Settlement.Tier.values()) {
            if (!tiers.containsKey(tier)) {
                throw new IllegalArgumentException("Missing settlement tier config: " + tier);
            }
        }
        int previous = 0;
        for (Settlement.Tier tier : Settlement.Tier.values()) {
            Requirements r = tiers.get(tier);
            if (r.radius() < 8 || r.radius() < previous || r.minMembers() < 1 || r.cost().isNegative() || r.minTreasury().isNegative()) {
                throw new IllegalArgumentException("Invalid settlement tier config: " + tier);
            }
            previous = r.radius();
        }
        if (spacingBuffer < 0 || maxInvites < 1) {
            throw new IllegalArgumentException("Invalid settlement configuration");
        }
    }

    public Requirements requirements(Settlement.Tier tier) {
        return tiers.get(tier);
    }

    public int maxRadius() {
        return tiers.get(Settlement.Tier.CITY).radius();
    }

    public static SettlementConfig defaults() {
        Map<Settlement.Tier, Requirements> tiers = new EnumMap<>(Settlement.Tier.class);
        tiers.put(Settlement.Tier.OUTPOST, new Requirements(32, 1, Money.ZERO, 0, 0, Money.ofSek(10_000)));
        tiers.put(Settlement.Tier.SETTLEMENT, new Requirements(64, 3, Money.ofSek(25_000), 3, 0, Money.ofSek(10_000)));
        tiers.put(Settlement.Tier.VILLAGE, new Requirements(128, 8, Money.ofSek(100_000), 14, 10, Money.ofSek(50_000)));
        tiers.put(Settlement.Tier.TOWN, new Requirements(192, 15, Money.ofSek(500_000), 30, 25, Money.ofSek(200_000)));
        tiers.put(Settlement.Tier.CITY, new Requirements(256, 30, Money.ofSek(2_000_000), 60, 50, Money.ofSek(1_000_000)));
        return new SettlementConfig(tiers, 64, 20);
    }
}
