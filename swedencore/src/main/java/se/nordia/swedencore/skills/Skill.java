package se.nordia.swedencore.skills;

import java.util.Locale;
import java.util.Optional;

/** Professional skills. Must match rows in the {@code skills} table. */
public enum Skill {
    MINING,
    FARMING,
    HERBALISM,
    BUILDING,
    FORESTRY,
    FISHING,
    ENGINEERING,
    LOGISTICS;

    public static Optional<Skill> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String normalized = input.trim().toUpperCase(Locale.ROOT);
        for (Skill skill : values()) {
            if (skill.name().equals(normalized)) {
                return Optional.of(skill);
            }
        }
        return switch (normalized) {
            case "GRUVDRIFT", "BRYTNING" -> Optional.of(MINING);
            case "JORDBRUK" -> Optional.of(FARMING);
            case "ÖRTKUNSKAP", "ORTKUNSKAP" -> Optional.of(HERBALISM);
            case "BYGGNATION", "BYGG" -> Optional.of(BUILDING);
            case "SKOGSBRUK" -> Optional.of(FORESTRY);
            case "FISKE" -> Optional.of(FISHING);
            case "TEKNIK", "INGENJÖR", "INGENJOR" -> Optional.of(ENGINEERING);
            case "LOGISTIK" -> Optional.of(LOGISTICS);
            default -> Optional.empty();
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
