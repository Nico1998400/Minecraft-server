package se.nordia.plugins.social;

import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.database.Database;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Nicknames, mutes and ignore lists. Pure database logic; chat and display are handled by the Paper layer. */
public final class SocialService {

    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_åäöÅÄÖ]{3,16}");

    /** An active mute. {@code until} is null for a permanent one. */
    public record Mute(UUID player, Instant until, String reason) {
    }

    private final Database database;
    private final Clock clock;

    public SocialService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    // ---- nicknames

    /** Sets a nickname: 3-16 letters, digits or underscore, unique in any case, and never another player's real name. */
    public String setNick(UUID player, String raw) {
        String nick = raw == null ? "" : raw.strip();
        if (!NICK.matcher(nick).matches()) {
            throw new DomainException("nick_invalid");
        }
        String lower = nick.toLowerCase(Locale.ROOT);
        database.inTransactionVoid(tx -> {
            boolean realName = tx.queryOne("SELECT 1 FROM players WHERE lower(name) = ? AND uuid <> ?", rs -> 1, lower, player)
                    .isPresent();
            boolean takenByOther = tx.queryOne("SELECT 1 FROM player_nicks WHERE nick_lower = ? AND player_uuid <> ?", rs -> 1,
                    lower, player).isPresent();
            if (realName || takenByOther) {
                throw new DomainException("nick_taken");
            }
            tx.update("""
                    INSERT INTO player_nicks (player_uuid, nick, nick_lower, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (player_uuid) DO UPDATE SET nick = EXCLUDED.nick, nick_lower = EXCLUDED.nick_lower,
                                                            updated_at = EXCLUDED.updated_at""", player, nick, lower, clock.instant());
        });
        return nick;
    }

    public void clearNick(UUID player) {
        database.inTransactionVoid(tx -> tx.update("DELETE FROM player_nicks WHERE player_uuid = ?", player));
    }

    public Optional<String> nick(UUID player) {
        return database.inTransaction(tx -> tx.queryOne("SELECT nick FROM player_nicks WHERE player_uuid = ?",
                rs -> rs.getString(1), player));
    }

    /** Finds a player by real name or nickname (any case). */
    public Optional<UUID> findByNameOrNick(String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return database.inTransaction(tx -> tx.queryList("""
                SELECT uuid FROM players WHERE lower(name) = ?
                UNION ALL
                SELECT player_uuid FROM player_nicks WHERE nick_lower = ?""", rs -> rs.getObject(1, UUID.class), lower, lower)
                .stream().findFirst());
    }

    // ---- mutes

    /** Mutes a player for {@code duration} (null = permanently). Muting again replaces the old mute. */
    public Mute mute(UUID player, UUID by, String reason, Duration duration) {
        if (duration != null && (duration.isNegative() || duration.isZero())) {
            throw new DomainException("mute_duration_invalid");
        }
        Instant now = clock.instant();
        Instant until = duration == null ? null : now.plus(duration);
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        database.inTransactionVoid(tx -> tx.update("""
                INSERT INTO player_mutes (player_uuid, until_at, reason, muted_by, created_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (player_uuid) DO UPDATE SET until_at = EXCLUDED.until_at, reason = EXCLUDED.reason,
                                                        muted_by = EXCLUDED.muted_by, created_at = EXCLUDED.created_at""",
                player, until, cleanReason, by, now));
        return new Mute(player, until, cleanReason);
    }

    public void unmute(UUID player) {
        int removed = database.inTransaction(tx -> tx.update("DELETE FROM player_mutes WHERE player_uuid = ?", player));
        if (removed == 0) {
            throw new DomainException("not_muted");
        }
    }

    /** The player's active mute, if any. An expired mute counts as none (and is removed). */
    public Optional<Mute> activeMute(UUID player) {
        Optional<Mute> mute = database.inTransaction(tx -> tx.queryOne(
                "SELECT until_at, reason FROM player_mutes WHERE player_uuid = ?",
                rs -> {
                    Timestamp until = rs.getTimestamp(1);
                    return new Mute(player, until == null ? null : until.toInstant(), rs.getString(2));
                }, player));
        if (mute.isPresent() && mute.get().until() != null && !mute.get().until().isAfter(clock.instant())) {
            database.inTransactionVoid(tx -> tx.update("DELETE FROM player_mutes WHERE player_uuid = ? AND until_at <= ?",
                    player, clock.instant()));
            return Optional.empty();
        }
        return mute;
    }

    // ---- ignore lists

    public void ignore(UUID player, UUID target) {
        if (player.equals(target)) {
            throw new DomainException("ignore_self");
        }
        database.inTransactionVoid(tx -> tx.update("""
                INSERT INTO player_ignores (player_uuid, ignored_uuid, created_at) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING""", player, target, clock.instant()));
    }

    public void unignore(UUID player, UUID target) {
        database.inTransactionVoid(tx -> tx.update("DELETE FROM player_ignores WHERE player_uuid = ? AND ignored_uuid = ?",
                player, target));
    }

    public Set<UUID> ignored(UUID player) {
        return new HashSet<>(database.inTransaction(tx -> tx.queryList(
                "SELECT ignored_uuid FROM player_ignores WHERE player_uuid = ?", rs -> rs.getObject(1, UUID.class), player)));
    }
}
