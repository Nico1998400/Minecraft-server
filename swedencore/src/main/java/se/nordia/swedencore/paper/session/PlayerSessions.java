package se.nordia.swedencore.paper.session;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSessions {

    private final ConcurrentHashMap<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    public PlayerSession put(PlayerSession session) {
        sessions.put(session.uuid(), session);
        return session;
    }

    public Optional<PlayerSession> get(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    public Optional<PlayerSession> remove(UUID uuid) {
        return Optional.ofNullable(sessions.remove(uuid));
    }

    public Collection<PlayerSession> all() {
        return sessions.values();
    }
}
