package se.nordia.swedencore.skills;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.player.PlayerService;
import se.nordia.swedencore.testing.DatabaseTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillServiceTest extends DatabaseTest {

    private SkillService skills;
    private UUID player;

    @BeforeEach
    void setUp() {
        skills = new SkillService(database, SkillsConfig.defaults());
        PlayerService players = new PlayerService(database, new EconomyService(database, EconomyConfig.defaults()));
        player = UUID.randomUUID();
        players.register(player, "Miner");
    }

    @Test
    void newPlayersStartAtLevelOneInEverySkill() {
        Map<Skill, SkillProgress> progress = skills.load(player);
        assertThat(progress).hasSize(Skill.values().length);
        assertThat(progress.values()).allSatisfy(p -> {
            assertThat(p.level()).isEqualTo(1);
            assertThat(p.xp()).isZero();
        });
    }

    @Test
    void addingXpLevelsUp() {
        LevelChange change = skills.addXp(player, Skill.MINING, 60);
        assertThat(change.oldLevel()).isEqualTo(1);
        assertThat(change.newLevel()).isEqualTo(2);
        assertThat(change.leveledUp()).isTrue();
        assertThat(skills.level(player, Skill.MINING)).isEqualTo(2);
        assertThat(skills.load(player).get(Skill.MINING).xp()).isEqualTo(60);
    }

    @Test
    void xpIsCappedAtMaxLevel() {
        long max = skills.curve().maxXp();
        for (int i = 0; i < 3; i++) {
            skills.addXp(player, Skill.FISHING, SkillService.MAX_SINGLE_GRANT);
        }
        LevelChange last = skills.addXp(player, Skill.FISHING, SkillService.MAX_SINGLE_GRANT);
        assertThat(last.totalXp()).isEqualTo(max);
        assertThat(last.newLevel()).isEqualTo(100);
        assertThat(skills.addXp(player, Skill.FISHING, 5).xpApplied()).isZero();
    }

    @Test
    void rejectsNegativeAndAbsurdGrants() {
        assertThatThrownBy(() -> skills.addXp(player, Skill.MINING, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> skills.addXp(player, Skill.MINING, SkillService.MAX_SINGLE_GRANT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentGrantsAreNotLost() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> skills.addXp(player, Skill.MINING, 7)));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(skills.load(player).get(Skill.MINING).xp()).isEqualTo(700);
    }

    @Test
    void batchApplyAndLeaderboard() {
        UUID other = UUID.randomUUID();
        new PlayerService(database, new EconomyService(database, EconomyConfig.defaults())).register(other, "Digger");
        skills.addXp(player, Map.of(Skill.MINING, 500L, Skill.FORESTRY, 20L));
        skills.addXp(other, Map.of(Skill.MINING, 900L));
        List<SkillService.LeaderboardEntry> top = skills.top(Skill.MINING, 10);
        assertThat(top).extracting(SkillService.LeaderboardEntry::name).containsExactly("Digger", "Miner");
        assertThat(skills.load(player).get(Skill.FORESTRY).xp()).isEqualTo(20);
    }
}
