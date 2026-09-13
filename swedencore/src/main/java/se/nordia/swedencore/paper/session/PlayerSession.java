package se.nordia.swedencore.paper.session;

import se.nordia.swedencore.localization.SupportedLocale;

import java.util.UUID;

/** Volatile per-online-player state. The database remains the source of truth. */
public final class PlayerSession {

    private final UUID uuid;
    private volatile SupportedLocale locale;

    public PlayerSession(UUID uuid, SupportedLocale locale) {
        this.uuid = uuid;
        this.locale = locale;
    }

    public UUID uuid() {
        return uuid;
    }

    /** Preferred locale, or {@code null} to use the server default. */
    public SupportedLocale locale() {
        return locale;
    }

    public void locale(SupportedLocale locale) {
        this.locale = locale;
    }
}
