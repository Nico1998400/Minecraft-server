package se.nordia.plugins.util;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses short durations typed in commands: {@code 30s}, {@code 10m}, {@code 2h}, {@code 1d}, {@code 1w} or combined {@code 1d2h30m}. */
public final class Durations {

    private static final Pattern WHOLE = Pattern.compile("(\\d{1,6}[smhdw])+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART = Pattern.compile("(\\d{1,6})([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final Duration MAX = Duration.ofDays(3650);

    private Durations() {
    }

    /** The duration, or empty if the text is not a duration (or is zero or absurdly long). */
    public static Optional<Duration> parse(String text) {
        if (text == null || !WHOLE.matcher(text).matches()) {
            return Optional.empty();
        }
        Duration total = Duration.ZERO;
        Matcher matcher = PART.matcher(text);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            total = total.plus(switch (Character.toLowerCase(matcher.group(2).charAt(0))) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> Duration.ofDays(7 * amount);
            });
            if (total.compareTo(MAX) > 0) {
                return Optional.empty();
            }
        }
        return total.isZero() ? Optional.empty() : Optional.of(total);
    }

    /** A short human form such as {@code 1d 2h 30m}. */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long rest = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (rest > 0 || out.isEmpty()) {
            out.append(rest).append('s');
        }
        return out.toString().strip();
    }
}
