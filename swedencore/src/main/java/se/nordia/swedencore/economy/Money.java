package se.nordia.swedencore.economy;

import se.nordia.swedencore.core.DomainException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An amount of SEK stored as whole öre (1 SEK = 100 öre).
 *
 * <p>Immutable. All arithmetic is overflow-checked; an overflow is a bug or an attack and throws.
 * Money may be negative only as an intermediate/system value — transfer amounts must be positive.
 */
public record Money(long ore) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);
    public static final long ORE_PER_SEK = 100;

    private static final int MAX_INPUT_LENGTH = 24;
    private static final Pattern INPUT = Pattern.compile("(\\d{1,15})(?:[.,](\\d{1,2}))?([kKmM])?");

    public static Money ofOre(long ore) {
        return new Money(ore);
    }

    public static Money ofSek(long sek) {
        return new Money(Math.multiplyExact(sek, ORE_PER_SEK));
    }

    /**
     * Parses player input such as {@code 150}, {@code 12,50}, {@code 12.5}, {@code 5k}, {@code 1,5m}.
     * Rejects signs, exponents, more than two decimals, thousands separators and anything that is not strictly positive.
     */
    public static Money parsePositive(String input) {
        if (input == null) {
            throw new DomainException("economy.invalid_amount");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INPUT_LENGTH) {
            throw DomainException.of("economy.invalid_amount", "input", truncate(trimmed));
        }
        Matcher m = INPUT.matcher(trimmed);
        if (!m.matches()) {
            throw DomainException.of("economy.invalid_amount", "input", truncate(trimmed));
        }
        BigDecimal value = new BigDecimal(m.group(1) + (m.group(2) != null ? "." + m.group(2) : ""));
        if (m.group(3) != null) {
            value = value.multiply(switch (Character.toLowerCase(m.group(3).charAt(0))) {
                case 'k' -> BigDecimal.valueOf(1_000);
                case 'm' -> BigDecimal.valueOf(1_000_000);
                default -> throw new IllegalStateException();
            });
        }
        BigDecimal ore = value.multiply(BigDecimal.valueOf(ORE_PER_SEK));
        long oreValue;
        try {
            oreValue = ore.longValueExact();
        } catch (ArithmeticException e) {
            throw DomainException.of("economy.invalid_amount", "input", truncate(trimmed));
        }
        if (oreValue <= 0) {
            throw DomainException.of("economy.invalid_amount", "input", truncate(trimmed));
        }
        return new Money(oreValue);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(ore, other.ore));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(ore, other.ore));
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(ore, factor));
    }

    public Money negate() {
        return new Money(Math.negateExact(ore));
    }

    public boolean isPositive() {
        return ore > 0;
    }

    public boolean isNegative() {
        return ore < 0;
    }

    public boolean isZero() {
        return ore == 0;
    }

    public boolean isGreaterThan(Money other) {
        return ore > other.ore;
    }

    public boolean isLessThan(Money other) {
        return ore < other.ore;
    }

    @Override
    public int compareTo(Money o) {
        return Long.compare(ore, o.ore);
    }

    /** Formats as e.g. {@code 1 234,50 SEK} (sv_SE) or {@code 1,234.50 SEK} (en_US). Whole amounts omit decimals. */
    public String format(Locale locale) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        if (locale.getLanguage().equals("sv")) {
            // Use a regular space instead of NBSP so it renders in the Minecraft font.
            symbols.setGroupingSeparator(' ');
        }
        boolean whole = ore % ORE_PER_SEK == 0;
        DecimalFormat format = new DecimalFormat(whole ? "#,##0" : "#,##0.00", symbols);
        BigDecimal sek = BigDecimal.valueOf(ore).movePointLeft(2);
        return format.format(sek) + " SEK";
    }

    @Override
    public String toString() {
        return format(Locale.ROOT);
    }

    private static String truncate(String s) {
        return s.length() > MAX_INPUT_LENGTH ? s.substring(0, MAX_INPUT_LENGTH) + "…" : s;
    }
}
