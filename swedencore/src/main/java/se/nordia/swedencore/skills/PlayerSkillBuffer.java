package se.nordia.swedencore.skills;

import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory skill state for one online player. XP is granted instantly (for immediate level-up feedback) and
 * persisted in batches, avoiding a database write per mined block.
 *
 * <p>Not thread-safe: confine to the server main thread.
 *
 * <p>Pending XP is only drained for flushing after the persisted state has been loaded; this avoids double counting
 * when a load query and a flush race. At most one flush interval of XP can be lost on a hard crash — acceptable for XP,
 * never used for money.
 */
public final class PlayerSkillBuffer {

    private final LevelCurve curve;
    private final EnumMap<Skill, Long> persisted = new EnumMap<>(Skill.class);
    private final EnumMap<Skill, Long> pending = new EnumMap<>(Skill.class);
    private boolean loaded;

    public PlayerSkillBuffer(LevelCurve curve) {
        this.curve = curve;
    }

    public boolean loaded() {
        return loaded;
    }

    public void load(Map<Skill, SkillProgress> progress) {
        persisted.clear();
        for (Map.Entry<Skill, SkillProgress> e : progress.entrySet()) {
            persisted.put(e.getKey(), e.getValue().xp());
        }
        loaded = true;
    }

    /**
     * Adds XP. Returns the resulting level change; {@link LevelChange#leveledUp()} is only reported once loaded so
     * players never see a spurious level-up based on incomplete data.
     */
    public LevelChange add(Skill skill, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative XP");
        }
        long before = total(skill);
        long after = Math.min(curve.maxXp(), Math.addExact(before, amount));
        long applied = after - before;
        if (applied > 0) {
            pending.merge(skill, applied, Math::addExact);
        }
        int oldLevel = curve.levelForXp(before);
        int newLevel = loaded ? curve.levelForXp(after) : oldLevel;
        return new LevelChange(skill, oldLevel, newLevel, applied, after);
    }

    public long total(Skill skill) {
        long base = persisted.getOrDefault(skill, 0L);
        return Math.min(curve.maxXp(), base + pending.getOrDefault(skill, 0L));
    }

    public SkillProgress progress(Skill skill) {
        long xp = total(skill);
        return new SkillProgress(skill, xp, curve.levelForXp(xp));
    }

    public int level(Skill skill) {
        return curve.levelForXp(total(skill));
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Removes pending XP for writing. Moves it into persisted optimistically; call {@link #restore} on failure. */
    public Map<Skill, Long> drainForFlush() {
        if (!loaded || pending.isEmpty()) {
            return Map.of();
        }
        Map<Skill, Long> drained = new EnumMap<>(pending);
        for (Map.Entry<Skill, Long> e : drained.entrySet()) {
            persisted.merge(e.getKey(), e.getValue(), Math::addExact);
        }
        pending.clear();
        return drained;
    }

    /** Puts XP back after a failed flush. */
    public void restore(Map<Skill, Long> drained) {
        for (Map.Entry<Skill, Long> e : drained.entrySet()) {
            persisted.merge(e.getKey(), -e.getValue(), Math::addExact);
            pending.merge(e.getKey(), e.getValue(), Math::addExact);
        }
    }
}
