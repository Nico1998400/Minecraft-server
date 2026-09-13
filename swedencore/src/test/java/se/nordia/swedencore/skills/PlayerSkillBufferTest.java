package se.nordia.swedencore.skills;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSkillBufferTest {

    private final LevelCurve curve = LevelCurve.defaults();

    private static Map<Skill, SkillProgress> persisted(Skill skill, long xp, LevelCurve curve) {
        Map<Skill, SkillProgress> map = new EnumMap<>(Skill.class);
        map.put(skill, new SkillProgress(skill, xp, curve.levelForXp(xp)));
        return map;
    }

    @Test
    void levelUpDetectedAfterLoad() {
        PlayerSkillBuffer buffer = new PlayerSkillBuffer(curve);
        buffer.load(persisted(Skill.MINING, 45, curve));
        LevelChange change = buffer.add(Skill.MINING, 10);
        assertThat(change.leveledUp()).isTrue();
        assertThat(buffer.total(Skill.MINING)).isEqualTo(55);
    }

    @Test
    void noLevelUpReportedBeforeLoadAndNoFlush() {
        PlayerSkillBuffer buffer = new PlayerSkillBuffer(curve);
        assertThat(buffer.add(Skill.MINING, 1_000).leveledUp()).isFalse();
        assertThat(buffer.drainForFlush()).isEmpty();
        buffer.load(persisted(Skill.MINING, 200, curve));
        assertThat(buffer.total(Skill.MINING)).isEqualTo(1_200);
        assertThat(buffer.drainForFlush()).containsEntry(Skill.MINING, 1_000L);
        assertThat(buffer.hasPending()).isFalse();
        assertThat(buffer.total(Skill.MINING)).isEqualTo(1_200);
    }

    @Test
    void failedFlushRestoresPendingWithoutDoubleCounting() {
        PlayerSkillBuffer buffer = new PlayerSkillBuffer(curve);
        buffer.load(Map.of());
        buffer.add(Skill.FARMING, 30);
        Map<Skill, Long> drained = buffer.drainForFlush();
        buffer.add(Skill.FARMING, 5);
        buffer.restore(drained);
        assertThat(buffer.total(Skill.FARMING)).isEqualTo(35);
        assertThat(buffer.drainForFlush()).containsEntry(Skill.FARMING, 35L);
    }

    @Test
    void capsAtMax() {
        PlayerSkillBuffer buffer = new PlayerSkillBuffer(curve);
        buffer.load(persisted(Skill.FISHING, curve.maxXp() - 5, curve));
        assertThat(buffer.add(Skill.FISHING, 100).xpApplied()).isEqualTo(5);
        assertThat(buffer.add(Skill.FISHING, 100).xpApplied()).isZero();
        assertThat(buffer.level(Skill.FISHING)).isEqualTo(curve.maxLevel());
    }

    @Test
    void rateLimiterAppliesDiminishingReturnsWithRemainders() {
        SkillsConfig config = SkillsConfig.defaults();
        XpRateLimiter limiter = new XpRateLimiter(config);
        long cap = config.softCap(Skill.HERBALISM);
        long now = 1_000_000;
        assertThat(limiter.apply(Skill.HERBALISM, cap - 2, now)).isEqualTo(cap - 2);
        // Crossing the cap: 2 full + 8 at 25% = 2 + 2
        assertThat(limiter.apply(Skill.HERBALISM, 10, now)).isEqualTo(4);
        // Four 1-XP grants at 25% accumulate to 1 XP instead of rounding to zero.
        long sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += limiter.apply(Skill.HERBALISM, 1, now);
        }
        assertThat(sum).isEqualTo(1);
        // Other skills are independent; window resets after an hour.
        assertThat(limiter.apply(Skill.MINING, 10, now)).isEqualTo(10);
        assertThat(limiter.apply(Skill.HERBALISM, 10, now + 60 * 60 * 1000L)).isEqualTo(10);
    }
}
