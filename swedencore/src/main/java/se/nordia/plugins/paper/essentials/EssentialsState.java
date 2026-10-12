package se.nordia.plugins.paper.essentials;

import se.nordia.plugins.social.SocialService;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, per-session state for the essentials commands: who is vanished, who blocks private messages, and the cached
 * mutes and ignore lists (loaded when a player joins so chat never waits for the database).
 */
public final class EssentialsState {

    public final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    public final Set<UUID> msgBlocked = ConcurrentHashMap.newKeySet();
    public final Set<UUID> socialSpies = ConcurrentHashMap.newKeySet();
    public final Set<UUID> tpBlocked = ConcurrentHashMap.newKeySet();
    public final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    public final Map<UUID, UUID> lastMessenger = new ConcurrentHashMap<>();
    public final Map<UUID, SocialService.Mute> mutes = new ConcurrentHashMap<>();
    public final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();

    public boolean ignores(UUID player, UUID other) {
        return ignores.getOrDefault(player, Set.of()).contains(other);
    }

    public void forget(UUID player) {
        vanished.remove(player);
        msgBlocked.remove(player);
        socialSpies.remove(player);
        tpBlocked.remove(player);
        afk.remove(player);
        lastMessenger.remove(player);
        mutes.remove(player);
        ignores.remove(player);
    }
}
