package se.nordia.swedencore.skills;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * @param hourlySoftCap         XP per skill per rolling hour before diminishing returns apply (0 = no cap)
 * @param softCapPercent        percentage of XP still granted above the soft cap
 * @param employmentBonusPercent extra XP for work in the skill of the player's on-duty job
 * @param afkThreshold          no XP (and no paid work minutes) without real input for this long
 * @param flushInterval         how often buffered XP is written to the database
 * @param buildingMaturity      how long a placed building block must remain before Building XP is granted
 */
public record SkillsConfig(
        LevelCurve curve,
        Map<Skill, Long> hourlySoftCap,
        int softCapPercent,
        int employmentBonusPercent,
        Duration afkThreshold,
        Duration flushInterval,
        Duration buildingMaturity
) {
    public SkillsConfig {
        if (softCapPercent < 0 || softCapPercent > 100) {
            throw new IllegalArgumentException("softCapPercent must be 0..100");
        }
        if (employmentBonusPercent < 0 || employmentBonusPercent > 500) {
            throw new IllegalArgumentException("employmentBonusPercent must be 0..500");
        }
        hourlySoftCap = Map.copyOf(hourlySoftCap);
    }

    public static SkillsConfig defaults() {
        Map<Skill, Long> caps = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            caps.put(skill, 6_000L);
        }
        caps.put(Skill.HERBALISM, 3_000L);
        caps.put(Skill.BUILDING, 3_000L);
        return new SkillsConfig(LevelCurve.defaults(), caps, 25, 10,
                Duration.ofMinutes(3), Duration.ofSeconds(30), Duration.ofMinutes(5));
    }

    public long softCap(Skill skill) {
        return hourlySoftCap.getOrDefault(skill, 0L);
    }
}
