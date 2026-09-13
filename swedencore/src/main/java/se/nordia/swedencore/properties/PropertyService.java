package se.nordia.swedencore.properties;

import se.nordia.swedencore.cities.City;
import se.nordia.swedencore.cities.CityService;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Property ownership and the property market.
 *
 * <p>Administrators define properties (regions with a type and list price). Players and companies buy them: unowned
 * city property pays the city treasury; resale pays the previous owner. Only owners (and the owning company's staff,
 * and explicitly trusted players) may build inside — enforced by the Paper protection listener using {@link #accessList()}.
 */
public final class PropertyService {

    private static final Pattern NAME = Pattern.compile("[\\p{IsLatin}\\p{N} &.,'/#\\-]{3,40}");

    public record Access(Property property, Set<UUID> allowed) {
    }

    public record Config(int maxOwnedPerPlayer, long maxVolume, int maxTrusted) {
        public static Config defaults() {
            return new Config(10, 2_000_000, 20);
        }
    }

    private final Database database;
    private final EconomyService economy;
    private final CompanyService companies;
    private final CityService cities;
    private final DomainEvents events;
    private final Config config;

    public PropertyService(Database database, EconomyService economy, CompanyService companies, CityService cities,
                           DomainEvents events, Config config) {
        this.database = database;
        this.economy = economy;
        this.companies = companies;
        this.cities = cities;
        this.events = events;
        this.config = config;
        companies.addDissolutionCheck((tx, companyId) -> {
            if (tx.queryLong("SELECT count(*) FROM properties WHERE owner_type = 'COMPANY' AND owner_id = ?", Long.toString(companyId)) > 0) {
                throw new DomainException("company.dissolve_has_properties");
            }
        });
    }

    /** Runs inside the purchase transaction before ownership changes (e.g. shops at the property are removed). */
    @FunctionalInterface
    public interface OwnershipChangeHook {
        void beforeOwnerChange(Tx tx, long propertyId) throws SQLException;
    }

    private final List<OwnershipChangeHook> ownershipHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addOwnershipChangeHook(OwnershipChangeHook hook) {
        ownershipHooks.add(hook);
    }

    /** Locks and returns a property (for services acting on property-bound entities in their own transactions). */
    public Property lockProperty(Tx tx, long id) throws SQLException {
        return lock(tx, id);
    }

    /** Throws unless the actor owns the property personally or has one of the roles in the owning company. */
    public void requirePropertyOwner(Tx tx, Property property, UUID actor, CompanyRole... companyRoles) throws SQLException {
        requireOwner(tx, property, actor, companyRoles);
    }

    // ------------------------------------------------------------------ administration

    public Property create(String rawName, Property.Type type, Region region, Money price) {
        String name = rawName == null ? "" : rawName.trim();
        if (!NAME.matcher(name).matches()) {
            throw new DomainException("property.invalid_name");
        }
        if (price.isNegative()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (region.volume() > config.maxVolume()) {
            throw DomainException.of("property.too_large", "max", config.maxVolume());
        }
        Property created = database.inTransaction(tx -> {
            // Serialise creation per world so two overlapping properties cannot be created concurrently.
            tx.queryOne("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> true, "properties:" + region.world());
            Optional<String> overlapping = tx.queryOne("""
                            SELECT name FROM properties
                            WHERE world = ? AND min_x <= ? AND max_x >= ? AND min_y <= ? AND max_y >= ? AND min_z <= ? AND max_z >= ?
                            LIMIT 1""",
                    rs -> rs.getString(1), region.world(), region.maxX(), region.minX(), region.maxY(), region.minY(),
                    region.maxZ(), region.minZ());
            if (overlapping.isPresent()) {
                throw DomainException.of("property.overlaps", "property", overlapping.get());
            }
            int centerX = (region.minX() + region.maxX()) / 2;
            int centerZ = (region.minZ() + region.maxZ()) / 2;
            Long cityId = cities.cityAt(tx, region.world(), centerX, centerZ).map(City::id).orElse(null);
            long id = tx.queryLong("""
                            INSERT INTO properties (name, type, world, min_x, min_y, min_z, max_x, max_y, max_z, city_id, price, market_value)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    name, type, region.world(), region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(),
                    region.maxZ(), cityId, price.ore(), price.ore());
            return find(tx, id).orElseThrow();
        });
        events.publish(new DomainEvent.PropertyChanged(created.id()));
        return created;
    }

    /** Deletes an unowned property (admin). Owned property must never silently disappear. */
    public void delete(long propertyId) {
        database.inTransactionVoid(tx -> {
            Property property = lock(tx, propertyId);
            if (property.owned()) {
                throw new DomainException("property.owned");
            }
            if (tx.queryLong("SELECT count(*) FROM property_sales WHERE property_id = ?", propertyId) > 0) {
                throw new DomainException("property.has_history");
            }
            tx.update("DELETE FROM properties WHERE id = ?", propertyId);
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
    }

    // ------------------------------------------------------------------ market

    /**
     * Buys a property that is AVAILABLE (pays the city, or the sink outside cities) or FOR_SALE (pays the owner).
     *
     * @param companyId if non-null the company buys with its own account; the actor must be its owner
     */
    public Property buy(UUID actor, long propertyId, Long companyId, Money expectedPrice) {
        Property bought = database.inTransaction(tx -> {
            if (companyId != null) {
                companies.lockActive(tx, companyId);
                companies.requireRole(tx, companyId, actor, CompanyRole.OWNER);
            } else {
                tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> true, actor)
                        .orElseThrow(() -> new DomainException("player.unknown"));
            }
            Property property = lock(tx, propertyId);
            if (property.status() == Property.Status.OWNED) {
                throw new DomainException("property.not_for_sale");
            }
            // Protects against a price change between viewing and buying (bait-and-switch).
            if (expectedPrice != null && !expectedPrice.equals(property.price())) {
                throw DomainException.of("property.price_changed", "price", property.price());
            }
            Property.OwnerType buyerType = companyId != null ? Property.OwnerType.COMPANY : Property.OwnerType.PLAYER;
            String buyerId = companyId != null ? companyId.toString() : actor.toString();
            if (property.owned() && property.ownerType() == buyerType && property.ownerId().equals(buyerId)) {
                throw new DomainException("property.already_owner");
            }
            if (buyerType == Property.OwnerType.PLAYER) {
                long owned = tx.queryLong("SELECT count(*) FROM properties WHERE owner_type = 'PLAYER' AND owner_id = ?", buyerId);
                if (owned >= config.maxOwnedPerPlayer()) {
                    throw DomainException.of("property.too_many_owned", "max", config.maxOwnedPerPlayer());
                }
            }
            Account buyerAccount = economy.requireAccount(tx, companyId != null ? AccountOwner.company(companyId) : AccountOwner.player(actor));
            Long transactionId = null;
            if (property.price().isPositive()) {
                TransferReceipt receipt;
                String key = "property-sale:" + propertyId + ":" + tx.queryLong("SELECT count(*) FROM property_sales WHERE property_id = ?", propertyId);
                if (property.owned()) {
                    AccountOwner seller = property.ownerType() == Property.OwnerType.PLAYER
                            ? AccountOwner.player(UUID.fromString(property.ownerId()))
                            : AccountOwner.company(Long.parseLong(property.ownerId()));
                    Account sellerAccount = economy.requireAccount(tx, seller);
                    receipt = economy.transfer(tx, new TransferRequest(buyerAccount.id(), sellerAccount.id(), property.price(),
                            TransactionType.PROPERTY_PURCHASE, key, actor, "PROPERTY", Long.toString(propertyId), null));
                } else if (property.cityId() != null) {
                    Account treasury = economy.getOrCreateAccount(tx, AccountOwner.city(property.cityId()), Account.MAIN);
                    receipt = economy.transfer(tx, new TransferRequest(buyerAccount.id(), treasury.id(), property.price(),
                            TransactionType.PROPERTY_PURCHASE, key, actor, "PROPERTY", Long.toString(propertyId), null));
                } else {
                    receipt = economy.burn(tx, buyerAccount.id(), property.price(), TransactionType.PROPERTY_PURCHASE, key, actor);
                }
                transactionId = receipt.transactionId();
            }
            tx.update("""
                            INSERT INTO property_sales (property_id, seller_type, seller_id, buyer_type, buyer_id, price, transaction_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    propertyId, property.ownerType(), property.ownerId(), buyerType, buyerId, property.price().ore(), transactionId);
            tx.update("DELETE FROM property_trusted WHERE property_id = ?", propertyId);
            for (OwnershipChangeHook hook : ownershipHooks) {
                hook.beforeOwnerChange(tx, propertyId);
            }
            tx.update("""
                            UPDATE properties SET owner_type = ?, owner_id = ?, status = 'OWNED', market_value = price, updated_at = now()
                            WHERE id = ?""",
                    buyerType, buyerId, propertyId);
            return find(tx, propertyId).orElseThrow();
        });
        events.publish(new DomainEvent.PropertySold(propertyId, bought.ownerType().name(), bought.ownerId(), bought.price().ore()));
        events.publish(new DomainEvent.PropertyChanged(propertyId));
        return bought;
    }

    public Property listForSale(UUID actor, long propertyId, Money price) {
        if (!price.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        Property result = database.inTransaction(tx -> {
            Property property = lock(tx, propertyId);
            requireOwner(tx, property, actor, CompanyRole.OWNER);
            tx.update("UPDATE properties SET status = 'FOR_SALE', price = ?, updated_at = now() WHERE id = ?", price.ore(), propertyId);
            return find(tx, propertyId).orElseThrow();
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
        return result;
    }

    public Property unlist(UUID actor, long propertyId) {
        Property result = database.inTransaction(tx -> {
            Property property = lock(tx, propertyId);
            requireOwner(tx, property, actor, CompanyRole.OWNER);
            if (property.status() != Property.Status.FOR_SALE) {
                throw new DomainException("property.not_for_sale");
            }
            tx.update("UPDATE properties SET status = 'OWNED', updated_at = now() WHERE id = ?", propertyId);
            return find(tx, propertyId).orElseThrow();
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
        return result;
    }

    public void trust(UUID actor, long propertyId, UUID target) {
        database.inTransactionVoid(tx -> {
            Property property = lock(tx, propertyId);
            requireOwner(tx, property, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            if (tx.queryLong("SELECT count(*) FROM property_trusted WHERE property_id = ?", propertyId) >= config.maxTrusted()) {
                throw DomainException.of("property.too_many_trusted", "max", config.maxTrusted());
            }
            tx.update("INSERT INTO property_trusted (property_id, player_uuid) VALUES (?, ?) ON CONFLICT DO NOTHING", propertyId, target);
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
    }

    public void untrust(UUID actor, long propertyId, UUID target) {
        database.inTransactionVoid(tx -> {
            Property property = lock(tx, propertyId);
            requireOwner(tx, property, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            tx.update("DELETE FROM property_trusted WHERE property_id = ? AND player_uuid = ?", propertyId, target);
        });
        events.publish(new DomainEvent.PropertyChanged(propertyId));
    }

    // ------------------------------------------------------------------ queries

    public Optional<Property> find(long id) {
        return database.inTransaction(tx -> find(tx, id));
    }

    public Optional<Property> find(Tx tx, long id) throws SQLException {
        return tx.queryOne(SELECT + " WHERE p.id = ?", PropertyService::map, id);
    }

    public List<Property> market(int limit, int offset) {
        return database.inTransaction(tx -> tx.queryList(SELECT + " WHERE p.status IN ('AVAILABLE', 'FOR_SALE') ORDER BY p.price, p.id LIMIT ? OFFSET ?",
                PropertyService::map, Math.clamp(limit, 1, 50), Math.max(0, offset)));
    }

    /** Properties owned by the player personally or by companies where the player is owner or manager. */
    public List<Property> ownedBy(UUID player) {
        return database.inTransaction(tx -> tx.queryList(SELECT + """
                         WHERE (p.owner_type = 'PLAYER' AND p.owner_id = ?)
                            OR (p.owner_type = 'COMPANY' AND p.owner_id IN (
                                SELECT company_id::text FROM company_employees
                                WHERE player_uuid = ? AND ended_at IS NULL AND role IN ('OWNER', 'MANAGER')))
                         ORDER BY p.id""",
                PropertyService::map, player.toString(), player));
    }

    /** Every property with the set of players allowed to build there. Used to (re)build the protection index. */
    public List<Access> accessList() {
        return database.inTransaction(tx -> accessFor(tx, tx.queryList(SELECT + " ORDER BY p.id", PropertyService::map)));
    }

    public Optional<Access> access(long propertyId) {
        return database.inTransaction(tx -> find(tx, propertyId).map(p -> {
            try {
                return accessFor(tx, List.of(p)).getFirst();
            } catch (SQLException e) {
                throw new se.nordia.swedencore.database.DatabaseException("access lookup failed", e);
            }
        }));
    }

    /** Access entries for properties owned by a company (refresh after membership changes). */
    public List<Access> accessForCompany(long companyId) {
        return database.inTransaction(tx -> accessFor(tx, tx.queryList(SELECT + " WHERE p.owner_type = 'COMPANY' AND p.owner_id = ?",
                PropertyService::map, Long.toString(companyId))));
    }

    private List<Access> accessFor(Tx tx, List<Property> properties) throws SQLException {
        Map<Long, Set<UUID>> trusted = new HashMap<>();
        for (var row : tx.queryList("SELECT property_id, player_uuid FROM property_trusted",
                rs -> Map.entry(rs.getLong(1), Tx.uuid(rs, "player_uuid")))) {
            trusted.computeIfAbsent(row.getKey(), k -> new HashSet<>()).add(row.getValue());
        }
        Map<String, Set<UUID>> companyStaff = new HashMap<>();
        for (var row : tx.queryList("SELECT company_id, player_uuid FROM company_employees WHERE ended_at IS NULL",
                rs -> Map.entry(Long.toString(rs.getLong(1)), Tx.uuid(rs, "player_uuid")))) {
            companyStaff.computeIfAbsent(row.getKey(), k -> new HashSet<>()).add(row.getValue());
        }
        return properties.stream().map(p -> {
            Set<UUID> allowed = new HashSet<>(trusted.getOrDefault(p.id(), Set.of()));
            if (p.ownerType() == Property.OwnerType.PLAYER) {
                allowed.add(UUID.fromString(p.ownerId()));
            } else if (p.ownerType() == Property.OwnerType.COMPANY) {
                allowed.addAll(companyStaff.getOrDefault(p.ownerId(), Set.of()));
            }
            return new Access(p, Set.copyOf(allowed));
        }).toList();
    }

    // ------------------------------------------------------------------ helpers

    private Property lock(Tx tx, long id) throws SQLException {
        tx.queryOne("SELECT id FROM properties WHERE id = ? FOR UPDATE", rs -> true, id)
                .orElseThrow(() -> new DomainException("property.not_found"));
        return find(tx, id).orElseThrow();
    }

    private void requireOwner(Tx tx, Property property, UUID actor, CompanyRole... companyRoles) throws SQLException {
        if (property.ownerType() == Property.OwnerType.PLAYER && property.ownerId().equals(actor.toString())) {
            return;
        }
        if (property.ownerType() == Property.OwnerType.COMPANY) {
            companies.requireRole(tx, Long.parseLong(property.ownerId()), actor, companyRoles);
            return;
        }
        throw new DomainException("property.not_owner");
    }

    private static final String SELECT = """
            SELECT p.*, c.name AS city_name, COALESCE(pl.name, co.name) AS owner_name
            FROM properties p
            LEFT JOIN cities c ON c.id = p.city_id
            LEFT JOIN players pl ON p.owner_type = 'PLAYER' AND pl.uuid::text = p.owner_id
            LEFT JOIN companies co ON p.owner_type = 'COMPANY' AND co.id::text = p.owner_id
            """;

    static Property map(ResultSet rs) throws SQLException {
        String ownerType = rs.getString("owner_type");
        return new Property(rs.getLong("id"), rs.getString("name"), Property.Type.valueOf(rs.getString("type")),
                new Region(rs.getString("world"), rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                        rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z")),
                Tx.nullableLong(rs, "city_id"), rs.getString("city_name"),
                ownerType == null ? null : Property.OwnerType.valueOf(ownerType), rs.getString("owner_id"),
                rs.getString("owner_name"), Property.Status.valueOf(rs.getString("status")),
                Money.ofOre(rs.getLong("price")), Money.ofOre(rs.getLong("market_value")));
    }
}
