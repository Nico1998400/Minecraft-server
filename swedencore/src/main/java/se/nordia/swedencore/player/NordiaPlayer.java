package se.nordia.swedencore.player;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent identity of a player in NORDIA.
 *
 * @param locale preferred locale tag ({@code sv_SE}, {@code en_US}) or {@code null} for the server default
 */
public record NordiaPlayer(UUID uuid, String name, String locale, int reputation, Instant firstSeen, Instant lastSeen) {
}
