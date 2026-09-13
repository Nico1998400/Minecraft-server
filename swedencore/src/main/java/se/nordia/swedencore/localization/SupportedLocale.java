package se.nordia.swedencore.localization;

import java.util.Locale;
import java.util.Optional;

public enum SupportedLocale {
    SV_SE("sv_SE", Locale.of("sv", "SE")),
    EN_US("en_US", Locale.of("en", "US"));

    public static final SupportedLocale DEFAULT = SV_SE;

    private final String tag;
    private final Locale javaLocale;

    SupportedLocale(String tag, Locale javaLocale) {
        this.tag = tag;
        this.javaLocale = javaLocale;
    }

    public String tag() {
        return tag;
    }

    public Locale javaLocale() {
        return javaLocale;
    }

    /** Accepts {@code sv_SE}, {@code sv-se}, {@code sv}, {@code svenska}, {@code en}, {@code english} … */
    public static Optional<SupportedLocale> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "sv_se", "sv", "svenska", "swedish" -> Optional.of(SV_SE);
            case "en_us", "en", "english", "engelska" -> Optional.of(EN_US);
            default -> Optional.empty();
        };
    }
}
