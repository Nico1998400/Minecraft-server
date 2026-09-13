package se.nordia.swedencore.inventory;

import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server-side custody of items for players and companies (e.g. delivered contract goods awaiting pickup).
 *
 * <p>Items are opaque serialized stacks: the domain never interprets them, which keeps it platform-independent.
 * Claiming marks rows claimed inside a transaction <em>before</em> the Paper layer hands out items, so a crash can
 * lose a claim but never duplicate items.
 */
public final class ItemStashService {

    public static final Pattern MATERIAL = Pattern.compile("[A-Z0-9_]{1,64}");
    public static final int MAX_STACK = 99;

    public enum OwnerType {
        PLAYER, COMPANY
    }

    public record Owner(OwnerType type, String id) {
        public static Owner player(UUID uuid) {
            return new Owner(OwnerType.PLAYER, uuid.toString());
        }

        public static Owner company(long companyId) {
            return new Owner(OwnerType.COMPANY, Long.toString(companyId));
        }
    }

    /** An item stack to store. */
    public record StashItem(String material, int amount, byte[] data) {
        public StashItem {
            Objects.requireNonNull(data);
            if (material == null || !MATERIAL.matcher(material).matches() || amount < 1 || amount > MAX_STACK
                    || data.length == 0 || data.length > 65_536) {
                throw new IllegalArgumentException("Invalid stash item");
            }
        }
    }

    public record StashEntry(long id, String material, int amount, byte[] data, String sourceType, String sourceId, Instant createdAt) {
    }

    private final Database database;
    private final CompanyService companies;

    public ItemStashService(Database database, CompanyService companies) {
        this.database = database;
        this.companies = companies;
    }

    public void deposit(Tx tx, Owner owner, List<StashItem> items, String sourceType, String sourceId) throws SQLException {
        for (StashItem item : items) {
            tx.update("""
                            INSERT INTO item_stash (owner_type, owner_id, item, material, amount, source_type, source_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    owner.type(), owner.id(), item.data(), item.material(), item.amount(), sourceType, sourceId);
        }
    }

    /** Stores items in their own transaction (e.g. returning items that could not be handed back to a player). */
    public void deposit(Owner owner, List<StashItem> items, String sourceType, String sourceId) {
        database.inTransactionVoid(tx -> deposit(tx, owner, items, sourceType, sourceId));
    }

    public long countUnclaimed(Owner owner) {
        return database.inTransaction(tx -> tx.queryLong(
                "SELECT count(*) FROM item_stash WHERE owner_type = ? AND owner_id = ? AND claimed_at IS NULL", owner.type(), owner.id()));
    }

    /** Summary per material of unclaimed items (for display). */
    public List<MaterialCount> summary(Owner owner) {
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT material, SUM(amount) AS total, count(*) AS stacks FROM item_stash
                        WHERE owner_type = ? AND owner_id = ? AND claimed_at IS NULL
                        GROUP BY material ORDER BY material""",
                rs -> new MaterialCount(rs.getString("material"), rs.getLong("total"), rs.getInt("stacks")),
                owner.type(), owner.id()));
    }

    public record MaterialCount(String material, long total, int stacks) {
    }

    /**
     * Claims up to {@code maxStacks} of the oldest unclaimed stacks. The actor must be the player owner, or an
     * owner/manager of the owning company.
     */
    public List<StashEntry> claim(UUID actor, Owner owner, int maxStacks) {
        if (maxStacks <= 0) {
            return List.of();
        }
        return database.inTransaction(tx -> {
            if (owner.type() == OwnerType.PLAYER) {
                if (!owner.id().equals(actor.toString())) {
                    throw new DomainException("stash.no_permission");
                }
            } else {
                companies.requireRole(tx, Long.parseLong(owner.id()), actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            }
            List<StashEntry> entries = tx.queryList("""
                            SELECT id, material, amount, item, source_type, source_id, created_at FROM item_stash
                            WHERE owner_type = ? AND owner_id = ? AND claimed_at IS NULL
                            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    rs -> new StashEntry(rs.getLong("id"), rs.getString("material"), rs.getInt("amount"), rs.getBytes("item"),
                            rs.getString("source_type"), rs.getString("source_id"), Tx.instant(rs, "created_at")),
                    owner.type(), owner.id(), Math.min(maxStacks, 36));
            for (StashEntry entry : entries) {
                tx.update("UPDATE item_stash SET claimed_at = now(), claimed_by = ? WHERE id = ?", actor, entry.id());
            }
            return entries;
        });
    }
}
