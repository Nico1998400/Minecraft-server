package se.nordia.plugins.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DurationsTest {

    @Test
    void parsesSingleAndCombinedDurations() {
        assertThat(Durations.parse("30s")).contains(Duration.ofSeconds(30));
        assertThat(Durations.parse("10m")).contains(Duration.ofMinutes(10));
        assertThat(Durations.parse("2H")).contains(Duration.ofHours(2));
        assertThat(Durations.parse("1w")).contains(Duration.ofDays(7));
        assertThat(Durations.parse("1d2h30m")).contains(Duration.ofHours(26).plusMinutes(30));
    }

    @Test
    void refusesEverythingElse() {
        for (String bad : new String[]{null, "", "abc", "10", "m", "0s", "-5m", "1.5h", "10 m", "1d2", "999999w", "5y"}) {
            assertThat(Durations.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void formatsShortly() {
        assertThat(Durations.format(Duration.ofSeconds(0))).isEqualTo("0s");
        assertThat(Durations.format(Duration.ofSeconds(90))).isEqualTo("1m 30s");
        assertThat(Durations.format(Duration.ofHours(26).plusMinutes(30))).isEqualTo("1d 2h 30m");
    }
}
