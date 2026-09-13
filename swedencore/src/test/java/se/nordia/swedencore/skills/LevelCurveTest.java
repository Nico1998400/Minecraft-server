package se.nordia.swedencore.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LevelCurveTest {

    private final LevelCurve curve = LevelCurve.defaults();

    @Test
    void startsAtLevelOne() {
        assertThat(curve.levelForXp(0)).isEqualTo(1);
        assertThat(curve.levelForXp(-5)).isEqualTo(1);
        assertThat(curve.totalXpForLevel(1)).isZero();
    }

    @Test
    void thresholdsAreExact() {
        long level2 = curve.totalXpForLevel(2);
        assertThat(level2).isEqualTo(50);
        assertThat(curve.levelForXp(level2 - 1)).isEqualTo(1);
        assertThat(curve.levelForXp(level2)).isEqualTo(2);
        for (int level = 2; level <= curve.maxLevel(); level++) {
            long t = curve.totalXpForLevel(level);
            assertThat(curve.levelForXp(t)).isEqualTo(level);
            assertThat(curve.levelForXp(t - 1)).isEqualTo(level - 1);
            assertThat(t).isGreaterThan(curve.totalXpForLevel(level - 1));
        }
    }

    @Test
    void capsAtMaxLevel() {
        assertThat(curve.levelForXp(curve.maxXp())).isEqualTo(100);
        assertThat(curve.levelForXp(Long.MAX_VALUE)).isEqualTo(100);
        assertThat(curve.progressWithinLevel(curve.maxXp())).containsExactly(0, 0);
    }

    @Test
    void defaultPacingIsReasonable() {
        // Early progress should be quick, mastery long-term.
        assertThat(curve.totalXpForLevel(10)).isBetween(4_000L, 8_000L);
        assertThat(curve.maxXp()).isBetween(1_500_000L, 3_000_000L);
    }

    @Test
    void progressWithinLevel() {
        long xp = curve.totalXpForLevel(5) + 10;
        long[] progress = curve.progressWithinLevel(xp);
        assertThat(progress[0]).isEqualTo(10);
        assertThat(progress[1]).isEqualTo(curve.totalXpForLevel(6) - curve.totalXpForLevel(5));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new LevelCurve(0, 1.5, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LevelCurve(50, 1.5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
