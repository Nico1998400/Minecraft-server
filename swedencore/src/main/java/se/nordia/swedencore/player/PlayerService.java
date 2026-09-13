package se.nordia.swedencore.player;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.localization.SupportedLocale;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PlayerService {

    /** Minecraft usernames: 3–16 of [A-Za-z0-9_]. Some legacy accounts are shorter; allow 1. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public record Registration(NordiaPlayer player, boolean firstJoin) {
    }

    private final Database database;
    private final EconomyService economy;

    public PlayerService(Database database, EconomyService economy) {
        this.database = database;
        this.economy = economy;
    }

    /**
     * Registers or refreshes a player on login. Idempotent: the personal account and starter grant are created
     * exactly once, even under concurrent logins or retries.
     */
    public Registration register(UUID uuid, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid player name: " + name);
        }
        return database.inTransaction(tx -> {
            boolean inserted = tx.queryOne("""
                            INSERT INTO players (uuid, name) VALUES (?, ?)
                            ON CONFLICT (uuid) DO UPDATE SET name = EXCLUDED.name, last_seen = now()
                            RETURNING (xmax = 0) AS inserted""",
                    rs -> rs.getBoolean("inserted"), uuid, name).orElse(false);
            Account account = economy.getOrCreateAccount(tx, AccountOwner.player(uuid), Account.MAIN);
            String grantKey = "starter-grant:" + uuid;
            boolean alreadyGranted = tx.queryOne("SELECT 1 FROM transactions WHERE idempotency_key = ?", rs -> true, grantKey)
                    .isPresent();
            // The existence check keeps logins working if the configured grant changes later; the idempotency key
            // still protects against concurrent duplicate grants.
            if (!alreadyGranted && economy.config().starterGrant().isPositive()) {
                economy.mint(tx, account.id(), economy.config().starterGrant(), TransactionType.STARTER_GRANT, grantKey, null);
            }
            NordiaPlayer player = find(tx, uuid).orElseThrow();
            return new Registration(player, inserted);
        });
    }

    public Optional<NordiaPlayer> find(UUID uuid) {
        return database.inTransaction(tx -> find(tx, uuid));
    }

    public Optional<NordiaPlayer> find(Tx tx, UUID uuid) throws SQLException {
        return tx.queryOne("SELECT * FROM players WHERE uuid = ?", PlayerService::map, uuid);
    }

    /** Finds the player who most recently used this name (names can change hands). */
    public Optional<NordiaPlayer> findByName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return database.inTransaction(tx -> tx.queryOne(
                "SELECT * FROM players WHERE lower(name) = lower(?) ORDER BY last_seen DESC LIMIT 1",
                PlayerService::map, name));
    }

    public NordiaPlayer requireByName(String name) {
        return findByName(name).orElseThrow(() -> DomainException.of("player.unknown", "player", name));
    }

    public void setLocale(UUID uuid, SupportedLocale locale) {
        database.inTransactionVoid(tx -> {
            int updated = tx.update("UPDATE players SET locale = ? WHERE uuid = ?", locale == null ? null : locale.tag(), uuid);
            if (updated == 0) {
                throw new DomainException("player.unknown");
            }
        });
    }

    public void recordQuit(UUID uuid) {
        database.inTransactionVoid(tx -> tx.update("UPDATE players SET last_seen = now() WHERE uuid = ?", uuid));
    }

    static NordiaPlayer map(ResultSet rs) throws SQLException {
        return new NordiaPlayer(
                Tx.uuid(rs, "uuid"),
                rs.getString("name"),
                rs.getString("locale"),
                rs.getInt("reputation"),
                Tx.instant(rs, "first_seen"),
                Tx.instant(rs, "last_seen"));
    }
}
