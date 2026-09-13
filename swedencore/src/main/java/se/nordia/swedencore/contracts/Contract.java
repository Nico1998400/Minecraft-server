package se.nordia.swedencore.contracts;

import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.skills.Skill;

import java.time.Instant;
import java.util.UUID;

/**
 * A contract between an issuer (player or company) and a contractor.
 *
 * @param issuerName display name of the issuing player or company
 * @param paidOut    reward already paid from escrow to the contractor(s)
 */
public record Contract(
        long id,
        IssuerType issuerType,
        UUID issuerPlayer,
        Long issuerCompanyId,
        String issuerName,
        Type type,
        String title,
        String material,
        Integer quantity,
        int delivered,
        Money reward,
        Money paidOut,
        Skill requiredSkill,
        int requiredLevel,
        long xpReward,
        Status status,
        UUID contractor,
        String contractorName,
        Instant createdAt,
        Instant deadline
) {
    public enum IssuerType {
        PLAYER, COMPANY
    }

    public enum Type {
        ITEM_DELIVERY, SERVICE
    }

    public enum Status {
        OPEN, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED;

        public boolean active() {
            return this == OPEN || this == IN_PROGRESS;
        }
    }

    public int remaining() {
        return quantity == null ? 0 : quantity - delivered;
    }

    public Money escrowRemaining() {
        return reward.minus(paidOut);
    }
}
