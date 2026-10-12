package se.nordia.plugins.discord;

import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.database.Database;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links a Minecraft account to a Discord account. The player asks for a short code in game ({@link #createCode}) and
 * sends it to the Discord bot, which calls {@link #completeLink}. A code works once and only for a few minutes, and a
 * player has one code at a time. A Discord account and a player can each be linked only once.
 */
public final class DiscordLinkService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;

    private record Pending(UUID player, Instant expiresAt) {
    }

    private final Database database;
    private final Clock clock;
    private final Duration codeLifetime;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> codes = new ConcurrentHashMap<>();

    public DiscordLinkService(Database database, Clock clock, Duration codeLifetime) {
        this.database = database;
        this.clock = clock;
        this.codeLifetime = codeLifetime;
    }

    /** A fresh code for the player (replacing any earlier one). Refused if the player is already linked. */
    public String createCode(UUID player) {
        if (linkedDiscordId(player).isPresent()) {
            throw new DomainException("discord_already_linked");
        }
        Instant now = clock.instant();
        codes.values().removeIf(p -> !p.expiresAt().isAfter(now) || p.player().equals(player));
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (codes.putIfAbsent(code.toString(), new Pending(player, now.plus(codeLifetime))) == null) {
                return code.toString();
            }
        }
        throw new IllegalStateException("Could not create a unique link code");
    }

    /** Completes a link from the Discord side and returns the linked player. */
    public UUID completeLink(String discordId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
        Pending pending = codes.remove(code);
        if (pending == null || !pending.expiresAt().isAfter(clock.instant())) {
            throw new DomainException("discord_code_invalid");
        }
        database.inTransactionVoid(tx -> {
            if (tx.queryOne("SELECT 1 FROM discord_links WHERE player_uuid = ?", rs -> 1, pending.player()).isPresent()) {
                throw new DomainException("discord_already_linked");
            }
            if (tx.queryOne("SELECT 1 FROM discord_links WHERE discord_id = ?", rs -> 1, discordId).isPresent()) {
                throw new DomainException("discord_account_taken");
            }
            tx.update("INSERT INTO discord_links (player_uuid, discord_id, linked_at) VALUES (?, ?, ?)",
                    pending.player(), discordId, clock.instant());
        });
        return pending.player();
    }

    public void unlink(UUID player) {
        int removed = database.inTransaction(tx -> tx.update("DELETE FROM discord_links WHERE player_uuid = ?", player));
        if (removed == 0) {
            throw new DomainException("discord_not_linked");
        }
    }

    public Optional<String> linkedDiscordId(UUID player) {
        return database.inTransaction(tx -> tx.queryOne("SELECT discord_id FROM discord_links WHERE player_uuid = ?",
                rs -> rs.getString(1), player));
    }
}
