package se.nordia.swedencore.skills;

import java.util.Arrays;

/**
 * Maps total XP to a level. Levels start at 1.
 *
 * <p>XP needed to advance from level {@code L} to {@code L+1} is {@code round(base × L^exponent)}.
 * With the defaults (base 50, exponent 1.5, max 100): level 10 ≈ 5.6k XP, level 50 ≈ 340k XP, level 100 ≈ 2M XP.
 * The cumulative table is precomputed, so lookups are O(log maxLevel).
 */
public final class LevelCurve {

    private final int maxLevel;
    /** totalXp[L] = XP required to reach level L (index 0 unused, totalXp[1] = 0). */
    private final long[] totalXp;

    public LevelCurve(long base, double exponent, int maxLevel) {
        if (base < 1 || exponent < 0 || maxLevel < 1 || maxLevel > 10_000) {
            throw new IllegalArgumentException("Invalid level curve: base=" + base + " exponent=" + exponent + " max=" + maxLevel);
        }
        this.maxLevel = maxLevel;
        this.totalXp = new long[maxLevel + 1];
        totalXp[1] = 0;
        for (int level = 1; level < maxLevel; level++) {
            long step = Math.max(1, Math.round(base * Math.pow(level, exponent)));
            totalXp[level + 1] = Math.addExact(totalXp[level], step);
        }
    }

    public static LevelCurve defaults() {
        return new LevelCurve(50, 1.5, 100);
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** Total XP at which the max level is reached; XP is capped here. */
    public long maxXp() {
        return totalXp[maxLevel];
    }

    public long totalXpForLevel(int level) {
        if (level < 1 || level > maxLevel) {
            throw new IllegalArgumentException("Level out of range: " + level);
        }
        return totalXp[level];
    }

    public int levelForXp(long xp) {
        if (xp <= 0) {
            return 1;
        }
        if (xp >= totalXp[maxLevel]) {
            return maxLevel;
        }
        int index = Arrays.binarySearch(totalXp, 1, maxLevel + 1, xp);
        // Exact hit: that level reached. Otherwise insertion point - 1 is the highest level whose threshold <= xp.
        return index >= 0 ? index : -index - 2;
    }

    /** XP gained within the current level and XP required for the next, for progress bars. */
    public long[] progressWithinLevel(long xp) {
        int level = levelForXp(xp);
        if (level >= maxLevel) {
            return new long[]{0, 0};
        }
        long start = totalXp[level];
        return new long[]{xp - start, totalXp[level + 1] - start};
    }
}
