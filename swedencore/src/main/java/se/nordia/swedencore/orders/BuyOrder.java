package se.nordia.swedencore.orders;

import se.nordia.swedencore.economy.Money;

import java.time.Instant;
import java.util.UUID;

/** A standing offer to buy a quantity of a material at a unit price, funded by escrow. */
public record BuyOrder(long id, IssuerType issuerType, UUID issuerPlayer, Long issuerCompanyId, String issuerName,
                       String material, int quantity, int filled, Money unitPrice, Status status, Instant deadline) {

    public enum IssuerType {
        PLAYER, COMPANY
    }

    public enum Status {
        OPEN, FILLED, CANCELLED, EXPIRED
    }

    public int remaining() {
        return quantity - filled;
    }
}
