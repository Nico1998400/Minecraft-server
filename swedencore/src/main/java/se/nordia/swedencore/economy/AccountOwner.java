package se.nordia.swedencore.economy;

import java.util.Objects;
import java.util.UUID;

/** Identifies who owns an account. Ids are stored as text so every entity type can own accounts. */
public record AccountOwner(OwnerType type, String id) {

    public enum OwnerType {
        SYSTEM, PLAYER, COMPANY, CITY, SETTLEMENT, CONTRACT, ORDER, TRANSPORT
    }

    public static AccountOwner transport(long transportId) {
        return new AccountOwner(OwnerType.TRANSPORT, Long.toString(transportId));
    }

    public static final AccountOwner MINT = new AccountOwner(OwnerType.SYSTEM, "MINT");
    public static final AccountOwner SINK = new AccountOwner(OwnerType.SYSTEM, "SINK");

    public AccountOwner {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Invalid owner id: " + id);
        }
    }

    public static AccountOwner player(UUID uuid) {
        return new AccountOwner(OwnerType.PLAYER, uuid.toString());
    }

    public static AccountOwner company(long companyId) {
        return new AccountOwner(OwnerType.COMPANY, Long.toString(companyId));
    }

    public static AccountOwner city(long cityId) {
        return new AccountOwner(OwnerType.CITY, Long.toString(cityId));
    }

    public static AccountOwner settlement(long settlementId) {
        return new AccountOwner(OwnerType.SETTLEMENT, Long.toString(settlementId));
    }

    public static AccountOwner contract(long contractId) {
        return new AccountOwner(OwnerType.CONTRACT, Long.toString(contractId));
    }

    public static AccountOwner buyOrder(long orderId) {
        return new AccountOwner(OwnerType.ORDER, Long.toString(orderId));
    }
}
