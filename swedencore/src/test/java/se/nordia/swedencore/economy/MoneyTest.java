package se.nordia.swedencore.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import se.nordia.swedencore.core.DomainException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "150    | 15000",
            "12,50  | 1250",
            "12.5   | 1250",
            "0.01   | 1",
            "5k     | 500000",
            "1,5k   | 150000",
            "2M     | 200000000",
            "' 42 ' | 4200"
    })
    void parsesValidInput(String input, long expectedOre) {
        assertThat(Money.parsePositive(input).ore()).isEqualTo(expectedOre);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0.00", "-5", "+5", "1e5", "1.234", "NaN", "Infinity", "1 000", "abc", "12,5,0",
            "0x10", "999999999999999m", "1234567890123456", "٣", "5kk", ".5", "5."})
    void rejectsInvalidOrNonPositiveInput(String input) {
        assertThatThrownBy(() -> Money.parsePositive(input))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("economy.invalid_amount");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Money.parsePositive(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void arithmeticOverflowThrows() {
        Money max = Money.ofOre(Long.MAX_VALUE);
        assertThatThrownBy(() -> max.plus(Money.ofOre(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofOre(Long.MIN_VALUE).minus(Money.ofOre(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofSek(Long.MAX_VALUE / 10)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofOre(Long.MIN_VALUE).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void formatsPerLocale() {
        assertThat(Money.ofOre(123_450).format(Locale.of("sv", "SE"))).isEqualTo("1 234,50 SEK");
        assertThat(Money.ofOre(123_450).format(Locale.of("en", "US"))).isEqualTo("1,234.50 SEK");
        assertThat(Money.ofSek(450).format(Locale.of("sv", "SE"))).isEqualTo("450 SEK");
        assertThat(Money.ofOre(-5).format(Locale.of("en", "US"))).isEqualTo("-0.05 SEK");
    }
}
