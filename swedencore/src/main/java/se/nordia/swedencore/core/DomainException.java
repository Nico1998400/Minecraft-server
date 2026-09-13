package se.nordia.swedencore.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rule violation that should be shown to the player (insufficient funds, not the owner, ...).
 *
 * <p>Domain code never contains player-facing text. It names an error code; the Paper layer renders the localization
 * key {@code error.<code>} with the given placeholder arguments. Argument values may be plain objects (inserted as
 * unparsed text) or {@link se.nordia.swedencore.economy.Money} (formatted for the viewer's locale).
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final Map<String, Object> args;

    public DomainException(String code) {
        this(code, Map.of());
    }

    public DomainException(String code, Map<String, Object> args) {
        super(code + (args.isEmpty() ? "" : " " + args));
        this.code = code;
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public static DomainException of(String code, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be pairs");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new DomainException(code, args);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> args() {
        return args;
    }

    /** Localization key rendered for this error. */
    public String messageKey() {
        return "error." + code;
    }
}
