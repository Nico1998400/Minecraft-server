package se.nordia.plugins.guide;

import java.time.Duration;
import java.util.function.Function;

/**
 * Settings for the AI guide. The API key comes from the environment ({@code NORDIA_AI_API_KEY}, or
 * {@code ANTHROPIC_API_KEY} as a fallback) and is never written to a file or a log: {@link #toString()} hides it.
 */
public record GuideConfig(
        boolean enabled,
        String name,
        String apiKey,
        String model,
        String apiUrl,
        int maxOutputTokens,
        int maxHistoryTurns,
        int maxMessageLength,
        Duration minInterval,
        int dailyMessageLimit,
        Duration requestTimeout
) {
    public static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
    public static final String DEFAULT_URL = "";
    public static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    public static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    public GuideConfig {
        apiKey = apiKey == null ? "" : apiKey.strip();
        name = name == null || name.isBlank() ? "Brage" : name.strip();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        apiUrl = apiUrl == null ? "" : apiUrl.strip();
        maxOutputTokens = Math.max(64, Math.min(maxOutputTokens, 2_000));
        maxHistoryTurns = Math.max(0, Math.min(maxHistoryTurns, 40));
        maxMessageLength = Math.max(20, Math.min(maxMessageLength, 1_000));
        dailyMessageLimit = Math.max(1, dailyMessageLimit);
    }

    /** Which service the model name belongs to. */
    public enum Provider { ANTHROPIC, GEMINI }

    public Provider provider() {
        return model.toLowerCase(java.util.Locale.ROOT).startsWith("claude") ? Provider.ANTHROPIC : Provider.GEMINI;
    }

    /** The URL to call: the configured one (tests), else the provider's own. */
    public String endpoint() {
        if (!apiUrl.isEmpty()) {
            return apiUrl;
        }
        return provider() == Provider.ANTHROPIC ? ANTHROPIC_URL : GEMINI_URL.formatted(model);
    }

    public static GuideConfig defaults() {
        return new GuideConfig(true, "Brage", "", DEFAULT_MODEL, DEFAULT_URL, 400, 10, 300,
                Duration.ofSeconds(3), 100, Duration.ofSeconds(20));
    }

    /** The key from the environment, or an empty string when none is set. */
    public static String keyFromEnvironment(Function<String, String> env) {
        String key = env.apply("NORDIA_AI_API_KEY");
        if (key == null || key.isBlank()) {
            key = env.apply("ANTHROPIC_API_KEY");
        }
        return key == null ? "" : key.strip();
    }

    /** True when the guide can talk: enabled and a key is present. */
    public boolean aiAvailable() {
        return enabled && !apiKey.isEmpty();
    }

    @Override
    public String toString() {
        return "GuideConfig[enabled=" + enabled + ", name=" + name + ", model=" + model
                + ", apiKey=" + (apiKey.isEmpty() ? "<not set>" : "<hidden>") + "]";
    }
}
