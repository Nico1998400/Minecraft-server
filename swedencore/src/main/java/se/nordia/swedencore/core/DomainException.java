package se.nordia.swedencore.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rule violation that should be shown to the player (insufficient funds, not the owner, ...).
 *
 * <p>Domain code never contains player-facing text. It names an error code; the Paper layer renders the localization
 * key {@code error.<code>} with the given placeholder arguments. Arguments are always inserted unparsed.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final Map<String, String> args;

    public DomainException(String code) {
        this(code, Map.of());
    }

    public DomainException(String code, Map<String, String> args) {
        super(code + (args.isEmpty() ? "" : " " + args));
        this.code = code;
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public static DomainException of(String code, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be pairs");
        }
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put(keyValues[i], keyValues[i + 1]);
        }
        return new DomainException(code, args);
    }

    public String code() {
        return code;
    }

    public Map<String, String> args() {
        return args;
    }

    /** Localization key rendered for this error. */
    public String messageKey() {
        return "error." + code;
    }
}
