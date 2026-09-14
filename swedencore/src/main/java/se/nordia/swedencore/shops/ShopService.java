package se.nordia.swedencore.shops;

import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.economy.TransferReceipt;
import se.nordia.swedencore.economy.TransferRequest;
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.events.DomainEvents;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.PropertyService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Physical shops. NORDIA has no auction house: listings are chests in SHOP properties and purchases require the buyer
 * to stand at the chest (enforced by the Paper layer). The domain handles ownership, prices, payment and sales records.
 *
 * <p>Purchase protocol (items live in a world container, money in the database):
 * <ol>
 *   <li>Main thread removes the bundle(s) from the chest.</li>
 *   <li>{@link #recordPurchase} moves money and records the sale with a unique token.</li>
 *   <li>On success the items go to the buyer; on a definite failure back to the chest; on an ambiguous failure
 *       {@link #saleRecorded} decides.</li>
 * </ol>
 */
public final class ShopService {

    private static final Pattern NAME = Pattern.compile("[\\p{IsLatin}\\p{N} &.,'\\-]{3,32}");

    public record PurchaseReceipt(ShopListing listing, int bundles, int items, Money total, long transactionId) {
    }

    public record Config(int maxListingsPerShop, int maxBundlesPerPurchase) {
        public static Config defaults() {
            return new Config(54, 16);
        }
    }

    private final Database database;
    private final EconomyService economy;
    private final PropertyService properties;
    private final DomainEvents events;
    private final Config config;
    private final java.time.Clock clock;

    public ShopService(Database database, EconomyService economy, PropertyService properties, DomainEvents events, Config config,
                       java.time.Clock clock) {
        this.database = database;
        this.economy = economy;
        this.properties = properties;
        this.events = events;
        this.config = config;
        this.clock = clock;
        properties.addOccupancyChangeHook((tx, propertyId) -> {
            // A new occupant (buyer or tenant) does not inherit the previous occupant's shop or its listings.
            tx.update("DELETE FROM shop_listings WHERE shop_id IN (SELECT id FROM shops WHERE property_id = ?)", propertyId);
            tx.update("UPDATE shops SET status = 'CLOSED' WHERE property_id = ?", propertyId);
        });
    }

    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ shops

    public Shop create(UUID actor, long propertyId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!NAME.matcher(name).matches()) {
            throw new DomainException("shop.invalid_name");
        }
        Shop shop = database.inTransaction(tx -> {
            Property property = properties.lockProperty(tx, propertyId);
            if (property.type() != Property.Type.SHOP) {
                throw new DomainException("shop.not_shop_property");
            }
            properties.requireOccupant(tx, property, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            Optional<Long> existing = tx.queryOne("SELECT id FROM shops WHERE property_id = ?", rs -> rs.getLong(1), propertyId);
            long id;
            if (existing.isPresent()) {
                // Reopen a shop that was closed by a previous ownership change (listings were already removed).
                if (tx.queryOne("SELECT status FROM shops WHERE id = ?", rs -> rs.getString(1), existing.get()).orElseThrow().equals("OPEN")) {
                    throw new DomainException("shop.already_exists");
                }
                id = existing.get();
                tx.update("UPDATE shops SET name = ?, status = 'OPEN', created_by = ? WHERE id = ?", name, actor, id);
            } else {
                id = tx.queryLong("INSERT INTO shops (property_id, name, created_by) VALUES (?, ?, ?) RETURNING id", propertyId, name, actor);
            }
            return find(tx, id).orElseThrow();
        });
        events.publish(new DomainEvent.ShopChanged(shop.id()));
        return shop;
    }

    public Shop setOpen(UUID actor, long shopId, boolean open) {
        Shop shop = database.inTransaction(tx -> {
            Shop current = lockShop(tx, shopId);
            requireManager(tx, current, actor);
            tx.update("UPDATE shops SET status = ? WHERE id = ?", open ? "OPEN" : "CLOSED", shopId);
            return find(tx, shopId).orElseThrow();
        });
        events.publish(new DomainEvent.ShopChanged(shopId));
        return shop;
    }

    // ------------------------------------------------------------------ listings

    /** Creates or replaces the listing for the container at the given location. */
    public ShopListing list(UUID actor, long shopId, String world, int x, int y, int z, String material, byte[] item,
                            int bundleSize, Money price) {
        if (!ItemStashService.MATERIAL.matcher(material).matches() || item == null || item.length == 0 || item.length > 65_536) {
            throw new DomainException("shop.invalid_item");
        }
        if (bundleSize < 1 || bundleSize > 64) {
            throw new DomainException("shop.invalid_bundle");
        }
        if (!price.isPositive() || price.isGreaterThan(economy.config().maxTransferAmount())) {
            throw new DomainException("economy.invalid_amount");
        }
        ShopListing listing = database.inTransaction(tx -> {
            Shop shop = lockShop(tx, shopId);
            requireManager(tx, shop, actor);
            Property property = properties.find(tx, shop.propertyId()).orElseThrow();
            if (!property.region().contains(world, x, y, z)) {
                throw new DomainException("shop.outside_property");
            }
            Optional<Long> existing = tx.queryOne("SELECT id FROM shop_listings WHERE world = ? AND x = ? AND y = ? AND z = ?",
                    rs -> rs.getLong(1), world, x, y, z);
            long id;
            if (existing.isPresent()) {
                id = existing.get();
                long owningShop = tx.queryLong("SELECT shop_id FROM shop_listings WHERE id = ?", id);
                if (owningShop != shopId) {
                    throw new DomainException("shop.location_taken");
                }
                tx.update("UPDATE shop_listings SET material = ?, item = ?, bundle_size = ?, price = ?, updated_at = now() WHERE id = ?",
                        material, item, bundleSize, price.ore(), id);
            } else {
                if (tx.queryLong("SELECT count(*) FROM shop_listings WHERE shop_id = ?", shopId) >= config.maxListingsPerShop()) {
                    throw DomainException.of("shop.too_many_listings", "max", config.maxListingsPerShop());
                }
                id = tx.queryLong("""
                                INSERT INTO shop_listings (shop_id, world, x, y, z, material, item, bundle_size, price)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                        shopId, world, x, y, z, material, item, bundleSize, price.ore());
            }
            return findListing(tx, id).orElseThrow();
        });
        events.publish(new DomainEvent.ShopChanged(shopId));
        return listing;
    }

    public void unlist(UUID actor, long listingId) {
        long shopId = database.inTransaction(tx -> {
            ShopListing listing = findListing(tx, listingId).orElseThrow(() -> new DomainException("shop.listing_not_found"));
            Shop shop = lockShop(tx, listing.shopId());
            requireManager(tx, shop, actor);
            tx.update("DELETE FROM shop_listings WHERE id = ?", listingId);
            return shop.id();
        });
        events.publish(new DomainEvent.ShopChanged(shopId));
    }

    /** Removes a listing whose container no longer exists (called by the Paper layer; no permission check). */
    public void removeListingAt(String world, int x, int y, int z) {
        Optional<Long> shopId = database.inTransaction(tx -> {
            Optional<Long> sid = tx.queryOne("SELECT shop_id FROM shop_listings WHERE world = ? AND x = ? AND y = ? AND z = ?",
                    rs -> rs.getLong(1), world, x, y, z);
            tx.update("DELETE FROM shop_listings WHERE world = ? AND x = ? AND y = ? AND z = ?", world, x, y, z);
            return sid;
        });
        shopId.ifPresent(id -> events.publish(new DomainEvent.ShopChanged(id)));
    }

    // ------------------------------------------------------------------ purchasing

    /**
     * Charges the buyer and records the sale. The caller has already taken the items out of the container.
     *
     * @param expectedPrice the price the buyer saw; a changed price aborts (no bait-and-switch)
     * @param token         unique per purchase attempt, generated before taking items
     */
    public PurchaseReceipt recordPurchase(UUID buyer, long listingId, int bundles, Money expectedPrice, UUID token) {
        if (bundles < 1 || bundles > config.maxBundlesPerPurchase()) {
            throw DomainException.of("shop.invalid_quantity", "max", config.maxBundlesPerPurchase());
        }
        return database.inTransaction(tx -> {
            tx.queryOne("SELECT id FROM shop_listings WHERE id = ? FOR UPDATE", rs -> true, listingId)
                    .orElseThrow(() -> new DomainException("shop.listing_not_found"));
            ShopListing listing = findListing(tx, listingId).orElseThrow();
            if (tx.queryOne("SELECT 1 FROM shop_sales WHERE token = ?", rs -> true, token).isPresent()) {
                throw new DomainException("shop.duplicate_purchase");
            }
            if (!listing.shopOpen()) {
                throw new DomainException("shop.closed");
            }
            if (!listing.price().equals(expectedPrice)) {
                throw DomainException.of("shop.price_changed", "price", listing.price());
            }
            Shop shop = find(tx, listing.shopId()).orElseThrow();
            AccountOwner seller = shop.ownerType() == Property.OwnerType.PLAYER
                    ? AccountOwner.player(UUID.fromString(shop.ownerId()))
                    : AccountOwner.company(Long.parseLong(shop.ownerId()));
            if (shop.ownerType() == Property.OwnerType.PLAYER && shop.ownerId().equals(buyer.toString())) {
                throw new DomainException("shop.own_shop");
            }
            Money total;
            try {
                total = listing.price().times(bundles);
            } catch (ArithmeticException e) {
                throw new DomainException("economy.balance_overflow");
            }
            Account buyerAccount = economy.requireAccount(tx, AccountOwner.player(buyer));
            Account sellerAccount = economy.requireAccount(tx, seller);
            TransferReceipt receipt = economy.transfer(tx, new TransferRequest(buyerAccount.id(), sellerAccount.id(), total,
                    TransactionType.SHOP_PURCHASE, "shop-sale:" + token, buyer, "SHOP", Long.toString(shop.id()), null));
            int items = Math.multiplyExact(bundles, listing.bundleSize());
            tx.update("""
                            INSERT INTO shop_sales (shop_id, listing_id, buyer_uuid, material, bundles, items, unit_price, total, token,
                                                    transaction_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    shop.id(), listingId, buyer, listing.material(), bundles, items, listing.price().ore(), total.ore(), token,
                    receipt.transactionId(), clock.instant());
            return new PurchaseReceipt(listing, bundles, items, total, receipt.transactionId());
        });
    }

    public boolean saleRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM shop_sales WHERE token = ?", rs -> true, token)).isPresent();
    }

    // ------------------------------------------------------------------ queries

    public Optional<Shop> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    public Optional<Shop> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SHOP_SELECT + " WHERE s.id = ?", ShopService::mapShop, id);
    }

    public Optional<Shop> atProperty(long propertyId) {
        return database.inTransaction(tx -> tx.queryOne(SHOP_SELECT + " WHERE s.property_id = ?", ShopService::mapShop, propertyId));
    }

    public List<ShopListing> allListings() {
        return database.inTransaction(tx -> tx.queryList(LISTING_SELECT + " ORDER BY l.id", ShopService::mapListing));
    }

    public List<ShopListing> listingsOf(long shopId) {
        return database.inTransaction(tx -> tx.queryList(LISTING_SELECT + " WHERE l.shop_id = ? ORDER BY l.id", ShopService::mapListing, shopId));
    }

    public Optional<ShopListing> findListing(long id) {
        return database.inTransaction(tx -> findListing(tx, id));
    }

    Optional<ShopListing> findListing(Tx tx, long id) throws SQLException {
        return tx.queryOne(LISTING_SELECT + " WHERE l.id = ?", ShopService::mapListing, id);
    }

    /** Where to buy a material: open shops, cheapest first per item. Buyers must still travel there. */
    public List<ShopListing> whereToBuy(String material, int limit) {
        return database.inTransaction(tx -> tx.queryList(LISTING_SELECT + """
                         WHERE l.material = ? AND s.status = 'OPEN'
                         ORDER BY (l.price::numeric / l.bundle_size), l.id LIMIT ?""",
                ShopService::mapListing, material, Math.clamp(limit, 1, 20)));
    }

    public List<Shop> shopsOwnedBy(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SHOP_SELECT + """
                         WHERE (COALESCE(le.tenant_type, p.owner_type) = 'PLAYER' AND COALESCE(le.tenant_id, p.owner_id) = ?)
                            OR (COALESCE(le.tenant_type, p.owner_type) = 'COMPANY' AND COALESCE(le.tenant_id, p.owner_id) IN (
                                SELECT company_id::text FROM company_employees
                                WHERE player_uuid = ? AND ended_at IS NULL AND role IN ('OWNER', 'MANAGER')))
                         ORDER BY s.id""",
                ShopService::mapShop, player.toString(), player));
    }

    public record SalesSummary(long sales, long items, Money revenue) {
    }

    public SalesSummary salesSummary(long shopId) {
        return database.inTransaction(tx -> tx.queryOne(
                "SELECT count(*), COALESCE(SUM(items), 0), COALESCE(SUM(total), 0) FROM shop_sales WHERE shop_id = ?",
                rs -> new SalesSummary(rs.getLong(1), rs.getLong(2), Money.ofOre(rs.getLong(3))), shopId).orElseThrow());
    }

    // ------------------------------------------------------------------ helpers

    private Shop lockShop(Tx tx, long shopId) throws SQLException {
        tx.queryOne("SELECT id FROM shops WHERE id = ? FOR UPDATE", rs -> true, shopId)
                .orElseThrow(() -> new DomainException("shop.not_found"));
        return find(tx, shopId).orElseThrow();
    }

    private void requireManager(Tx tx, Shop shop, UUID actor) throws SQLException {
        Property property = properties.find(tx, shop.propertyId()).orElseThrow();
        properties.requireOccupant(tx, property, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
    }

    /** The shop's owner is the property's occupant: the tenant while leased, otherwise the property owner. */
    private static final String SHOP_SELECT = """
            SELECT s.*, p.name AS property_name,
                   COALESCE(le.tenant_type, p.owner_type) AS owner_type, COALESCE(le.tenant_id, p.owner_id) AS owner_id,
                   COALESCE(tp.name, tc.name, pl.name, co.name) AS owner_name, ci.name AS city_name
            FROM shops s
            JOIN properties p ON p.id = s.property_id
            LEFT JOIN property_leases le ON le.property_id = p.id AND le.status IN ('ACTIVE', 'OVERDUE')
            LEFT JOIN players tp ON le.tenant_type = 'PLAYER' AND tp.uuid::text = le.tenant_id
            LEFT JOIN companies tc ON le.tenant_type = 'COMPANY' AND tc.id::text = le.tenant_id
            LEFT JOIN players pl ON p.owner_type = 'PLAYER' AND pl.uuid::text = p.owner_id
            LEFT JOIN companies co ON p.owner_type = 'COMPANY' AND co.id::text = p.owner_id
            LEFT JOIN cities ci ON ci.id = p.city_id
            """;

    private static Shop mapShop(ResultSet rs) throws SQLException {
        String ownerType = rs.getString("owner_type");
        return new Shop(rs.getLong("id"), rs.getLong("property_id"), rs.getString("property_name"), rs.getString("name"),
                "OPEN".equals(rs.getString("status")), ownerType == null ? null : Property.OwnerType.valueOf(ownerType),
                rs.getString("owner_id"), rs.getString("owner_name"), rs.getString("city_name"));
    }

    private static final String LISTING_SELECT = """
            SELECT l.*, s.name AS shop_name, s.status AS shop_status, ci.name AS city_name
            FROM shop_listings l
            JOIN shops s ON s.id = l.shop_id
            JOIN properties p ON p.id = s.property_id
            LEFT JOIN cities ci ON ci.id = p.city_id
            """;

    private static ShopListing mapListing(ResultSet rs) throws SQLException {
        return new ShopListing(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("shop_name"),
                "OPEN".equals(rs.getString("shop_status")), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("material"), rs.getBytes("item"), rs.getInt("bundle_size"), Money.ofOre(rs.getLong("price")),
                rs.getString("city_name"));
    }
}
