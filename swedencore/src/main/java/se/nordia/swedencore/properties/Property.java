package se.nordia.swedencore.properties;

import se.nordia.swedencore.economy.Money;

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
        Money marketValue
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
}
