package se.nordia.plugins.essentials;

import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.database.Database;
import se.nordia.plugins.database.RowMapper;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Homes (per player) and warps (server wide). Names are lower-case; the database enforces the same pattern as a last line
 * of defence. Teleporting itself (delay, cooldown, cancelling) is the Paper layer's job.
 */
public final class EssentialsService {

    private static final Pattern HOME_NAME = Pattern.compile("[a-z0-9_-]{1,16}");
    private static final Pattern WARP_NAME = Pattern.compile("[a-z0-9_-]{1,24}");

    public record Home(String name, Spot spot) {
    }

    public record Warp(String name, Spot spot) {
    }

    private static final RowMapper<Spot> SPOT = rs -> new Spot(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"),
            rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));

    private final Database database;
    private final Clock clock;

    public EssentialsService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    // ---- homes

    /**
     * Saves (or moves) a home. A new home beyond {@code maxHomes} is refused; moving an existing one never is.
     * The player row is locked so two commands at once cannot both pass the limit.
     */
    public void setHome(UUID player, String rawName, Spot spot, int maxHomes) {
        String name = homeName(rawName);
        database.inTransactionVoid(tx -> {
            tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> rs.getString(1), player)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            boolean exists = tx.queryOne("SELECT 1 FROM homes WHERE player_uuid = ? AND name = ?", rs -> 1, player, name)
                    .isPresent();
            if (!exists) {
                long count = tx.queryOne("SELECT count(*) FROM homes WHERE player_uuid = ?", rs -> rs.getLong(1), player)
                        .orElse(0L);
                if (count >= maxHomes) {
                    throw DomainException.of("home_limit", "max", maxHomes);
                }
            }
            tx.update("""
                            INSERT INTO homes (player_uuid, name, world, x, y, z, yaw, pitch, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (player_uuid, name) DO UPDATE
                            SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                                yaw = EXCLUDED.yaw, pitch = EXCLUDED.pitch""",
                    player, name, spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch(), clock.instant());
        });
    }

    public void deleteHome(UUID player, String rawName) {
        String name = homeName(rawName);
        int removed = database.inTransaction(tx -> tx.update("DELETE FROM homes WHERE player_uuid = ? AND name = ?", player, name));
        if (removed == 0) {
            throw DomainException.of("home_not_found", "name", name);
        }
    }

    /** Renames a home. The new name must be free; the old one must exist. */
    public void renameHome(UUID player, String rawOld, String rawNew) {
        String oldName = homeName(rawOld);
        String newName = homeName(rawNew);
        database.inTransactionVoid(tx -> {
            tx.queryOne("SELECT uuid FROM players WHERE uuid = ? FOR UPDATE", rs -> rs.getString(1), player)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            if (tx.queryOne("SELECT 1 FROM homes WHERE player_uuid = ? AND name = ?", rs -> 1, player, newName).isPresent()) {
                throw DomainException.of("home_exists", "name", newName);
            }
            int changed = tx.update("UPDATE homes SET name = ? WHERE player_uuid = ? AND name = ?", newName, player, oldName);
            if (changed == 0) {
                throw DomainException.of("home_not_found", "name", oldName);
            }
        });
    }

    public Spot home(UUID player, String rawName) {
        String name = homeName(rawName);
        return database.inTransaction(tx -> tx.queryOne("""
                        SELECT world, x, y, z, yaw, pitch FROM homes WHERE player_uuid = ? AND name = ?""", SPOT, player, name))
                .orElseThrow(() -> DomainException.of("home_not_found", "name", name));
    }

    public List<Home> homes(UUID player) {
        return database.inTransaction(tx -> tx.queryList("""
                SELECT name, world, x, y, z, yaw, pitch FROM homes WHERE player_uuid = ? ORDER BY name""",
                rs -> new Home(rs.getString("name"), SPOT.map(rs)), player));
    }

    // ---- warps

    public void setWarp(UUID creator, String rawName, Spot spot) {
        String name = warpName(rawName);
        database.inTransactionVoid(tx -> tx.update("""
                        INSERT INTO warps (name, world, x, y, z, yaw, pitch, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (name) DO UPDATE
                        SET world = EXCLUDED.world, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
                            yaw = EXCLUDED.yaw, pitch = EXCLUDED.pitch""",
                name, spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch(), creator, clock.instant()));
    }

    public void deleteWarp(String rawName) {
        String name = warpName(rawName);
        int removed = database.inTransaction(tx -> tx.update("DELETE FROM warps WHERE name = ?", name));
        if (removed == 0) {
            throw DomainException.of("warp_not_found", "name", name);
        }
    }

    public Spot warp(String rawName) {
        String name = warpName(rawName);
        return database.inTransaction(tx -> tx.queryOne("SELECT world, x, y, z, yaw, pitch FROM warps WHERE name = ?", SPOT, name))
                .orElseThrow(() -> DomainException.of("warp_not_found", "name", name));
    }

    public List<Warp> warps() {
        return database.inTransaction(tx -> tx.queryList("""
                SELECT name, world, x, y, z, yaw, pitch FROM warps ORDER BY name""",
                rs -> new Warp(rs.getString("name"), SPOT.map(rs))));
    }

    // ---- names

    private static String homeName(String raw) {
        String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!HOME_NAME.matcher(name).matches()) {
            throw new DomainException("home_name_invalid");
        }
        return name;
    }

    private static String warpName(String raw) {
        String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!WARP_NAME.matcher(name).matches()) {
            throw new DomainException("warp_name_invalid");
        }
        return name;
    }
}
