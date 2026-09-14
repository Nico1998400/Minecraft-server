package se.nordia.swedencore.properties;

import se.nordia.swedencore.economy.Money;

/**
 * A property. While leased, the tenant is the <em>occupant</em>: the one who builds, runs shops and production there.
 *
 * @param tenantType null unless an active (or overdue) lease exists
 */
public record Property(
        long id,
        String name,
        Type type,
        Region region,
        Long cityId,
        String cityName,
        OwnerType ownerType,
        String ownerId,
        String ownerName,
        Status status,
        Money price,
        Money marketValue,
        OwnerType tenantType,
        String tenantId,
        String tenantName
) {
    public enum Type {
        APARTMENT, HOUSE, SHOP, OFFICE, FACTORY, WAREHOUSE, INDUSTRIAL_LAND, FARM, MINE
    }

    public enum OwnerType {
        PLAYER, COMPANY
    }

    public enum Status {
        /** Unowned; sold by the city (or the server outside cities). */
        AVAILABLE,
        OWNED,
        /** Owned and listed for sale by its owner. */
        FOR_SALE
    }

    public boolean owned() {
        return status != Status.AVAILABLE;
    }

    public boolean leased() {
        return tenantType != null;
    }

    /** Who uses the property: the tenant if leased, otherwise the owner. */
    public OwnerType occupantType() {
        return leased() ? tenantType : ownerType;
    }

    public String occupantId() {
        return leased() ? tenantId : ownerId;
    }

    public String occupantName() {
        return leased() ? tenantName : ownerName;
    }
}
