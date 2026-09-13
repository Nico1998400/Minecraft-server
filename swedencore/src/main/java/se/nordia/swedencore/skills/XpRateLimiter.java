package se.nordia.swedencore.skills;

import java.util.EnumMap;

/**
 * Diminishing returns per player and skill: XP beyond the hourly soft cap is scaled down.
 *
 * <p>Purpose: repetitive loops (bone-mealing flowers, place/break cycles) stop being efficient, while normal play
 * never hits the cap. Fractions are carried over in hundredths so small grants (e.g. 1 XP) are not rounded to zero.
 *
 * <p>Not thread-safe: confine to the main thread (one instance per online player).
 */
public final class XpRateLimiter {

    private static final long WINDOW_MILLIS = 60 * 60 * 1000L;

    private final SkillsConfig config;
    private final EnumMap<Skill, Long> windowStart = new EnumMap<>(Skill.class);
    private final EnumMap<Skill, Long> xpInWindow = new EnumMap<>(Skill.class);
    private final EnumMap<Skill, Long> remainderCenti = new EnumMap<>(Skill.class);

    public XpRateLimiter(SkillsConfig config) {
        this.config = config;
    }

    public long apply(Skill skill, long amount, long nowMillis) {
        if (amount <= 0) {
            return 0;
        }
        long cap = config.softCap(skill);
        if (cap <= 0) {
            return amount;
        }
        long start = windowStart.getOrDefault(skill, Long.MIN_VALUE);
        if (start == Long.MIN_VALUE || nowMillis - start >= WINDOW_MILLIS) {
            windowStart.put(skill, nowMillis);
            xpInWindow.put(skill, 0L);
        }
        long used = xpInWindow.getOrDefault(skill, 0L);
        long full = Math.max(0, Math.min(amount, cap - used));
        long excess = amount - full;
        long granted = full;
        if (excess > 0) {
            long centi = excess * config.softCapPercent() + remainderCenti.getOrDefault(skill, 0L);
            granted += centi / 100;
            remainderCenti.put(skill, centi % 100);
        }
        xpInWindow.put(skill, used + amount);
        return granted;
    }
}
