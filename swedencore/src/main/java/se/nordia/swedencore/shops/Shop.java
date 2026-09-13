package se.nordia.swedencore.shops;

import se.nordia.swedencore.properties.Property;

/**
 * A shop: a business at a SHOP property. Its owner is always the property's owner, so selling the property ends the
 * shop — a business cannot outlive its premises.
 */
public record Shop(long id, long propertyId, String propertyName, String name, boolean open,
                   Property.OwnerType ownerType, String ownerId, String ownerName, String cityName) {
}
