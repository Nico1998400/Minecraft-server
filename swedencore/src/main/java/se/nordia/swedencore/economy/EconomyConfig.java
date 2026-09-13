package se.nordia.swedencore.economy;

/**
 * @param starterGrant       money minted once for each new player
 * @param maxTransferAmount  upper bound for any single player-initiated transfer (limits damage from exploits/typos)
 * @param minPaymentAmount   smallest player-to-player payment (prevents spam)
 */
public record EconomyConfig(Money starterGrant, Money maxTransferAmount, Money minPaymentAmount) {

    public static EconomyConfig defaults() {
        return new EconomyConfig(Money.ofSek(1_000), Money.ofSek(100_000_000), Money.ofOre(1));
    }
}
