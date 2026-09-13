package se.nordia.swedencore.shops;

import se.nordia.swedencore.economy.Money;

/**
 * An offer bound to a stock container at a fixed location.
 *
 * @param item       serialized item template; stock must match it exactly
 * @param bundleSize items per purchase
 * @param price      price per bundle
 */
public record ShopListing(long id, long shopId, String shopName, boolean shopOpen, String world, int x, int y, int z,
                          String material, byte[] item, int bundleSize, Money price, String cityName) {
}
