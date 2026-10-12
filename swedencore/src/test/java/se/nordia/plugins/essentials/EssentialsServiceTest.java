package se.nordia.plugins.essentials;

import org.junit.jupiter.api.Test;
import se.nordia.plugins.testing.CoreTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsServiceTest extends CoreTest {

    private static Spot spot(double x) {
        return new Spot("world", x, 64, 10, 90f, 5f);
    }

    @Test
    void savesAndReadsAHomeAndMovesItWhenSavedAgain() {
        UUID anna = player("Anna");
        core.essentials().setHome(anna, "Hem", spot(1), 3);
        assertThat(core.essentials().home(anna, "hem")).isEqualTo(spot(1));
        core.essentials().setHome(anna, "hem", spot(2), 3);
        assertThat(core.essentials().home(anna, "HEM")).isEqualTo(spot(2));
        assertThat(core.essentials().homes(anna)).extracting(EssentialsService.Home::name).containsExactly("hem");
    }

    @Test
    void theHomeLimitStopsNewHomesButNeverBlocksMovingAnExistingOne() {
        UUID anna = player("Anna");
        for (String name : List.of("a", "b", "c")) {
            core.essentials().setHome(anna, name, spot(1), 3);
        }
        assertDomainError(() -> core.essentials().setHome(anna, "d", spot(1), 3), "home_limit");
        core.essentials().setHome(anna, "b", spot(7), 3);
        assertThat(core.essentials().home(anna, "b")).isEqualTo(spot(7));
    }

    @Test
    void homesAreOwnedByTheirPlayer() {
        UUID anna = player("Anna");
        UUID bo = player("Bo");
        core.essentials().setHome(anna, "hem", spot(1), 3);
        assertDomainError(() -> core.essentials().home(bo, "hem"), "home_not_found");
        assertDomainError(() -> core.essentials().deleteHome(bo, "hem"), "home_not_found");
        assertThat(core.essentials().homes(anna)).hasSize(1);
    }

    @Test
    void deletingAHomeRemovesItAndDeletingTwiceFails() {
        UUID anna = player("Anna");
        core.essentials().setHome(anna, "hem", spot(1), 3);
        core.essentials().deleteHome(anna, "hem");
        assertDomainError(() -> core.essentials().deleteHome(anna, "hem"), "home_not_found");
    }

    @Test
    void rejectsBadNames() {
        UUID anna = player("Anna");
        for (String bad : List.of("", "  ", "has space", "ä", "x".repeat(17), "a;drop", "../x")) {
            assertDomainError(() -> core.essentials().setHome(anna, bad, spot(1), 3), "home_name_invalid");
        }
        assertDomainError(() -> core.essentials().setWarp(anna, "x".repeat(25), spot(1)), "warp_name_invalid");
    }

    @Test
    void anUnknownPlayerCannotSaveAHome() {
        assertDomainError(() -> core.essentials().setHome(UUID.randomUUID(), "hem", spot(1), 3), "player.unknown");
    }

    @Test
    void concurrentSavesCannotGoOverTheHomeLimit() throws Exception {
        UUID anna = player("Anna");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String name = "h" + i;
                jobs.add(() -> {
                    try {
                        core.essentials().setHome(anna, name, spot(1), 3);
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                });
            }
            int saved = 0;
            for (Future<Boolean> result : pool.invokeAll(jobs)) {
                if (result.get()) {
                    saved++;
                }
            }
            assertThat(saved).isEqualTo(3);
            assertThat(core.essentials().homes(anna)).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void renamingAHomeKeepsItsPlaceAndRefusesTakenOrMissingNames() {
        UUID anna = player("Anna");
        core.essentials().setHome(anna, "a", spot(1), 3);
        core.essentials().setHome(anna, "b", spot(2), 3);
        core.essentials().renameHome(anna, "a", "kojan");
        assertThat(core.essentials().home(anna, "kojan")).isEqualTo(spot(1));
        assertDomainError(() -> core.essentials().home(anna, "a"), "home_not_found");
        assertDomainError(() -> core.essentials().renameHome(anna, "kojan", "b"), "home_exists");
        assertDomainError(() -> core.essentials().renameHome(anna, "saknas", "ny"), "home_not_found");
        assertDomainError(() -> core.essentials().renameHome(anna, "kojan", "Bad Name"), "home_name_invalid");
    }

    @Test
    void warpsAreSharedAndCanBeReplacedAndDeleted() {
        UUID admin = player("Admin");
        core.essentials().setWarp(admin, "Torget", spot(1));
        core.essentials().setWarp(admin, "torget", spot(5));
        assertThat(core.essentials().warp("TORGET")).isEqualTo(spot(5));
        assertThat(core.essentials().warps()).extracting(EssentialsService.Warp::name).containsExactly("torget");
        core.essentials().deleteWarp("torget");
        assertDomainError(() -> core.essentials().warp("torget"), "warp_not_found");
        assertDomainError(() -> core.essentials().deleteWarp("torget"), "warp_not_found");
    }

    @Test
    void aSpotMustHaveFiniteCoordinates() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Spot("w", Double.NaN, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Spot(" ", 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configValuesAreClampedToSaneRanges() {
        var rule = new EssentialsConfig.Rule(9_999, -5);
        assertThat(rule.warmupSeconds()).isEqualTo(60);
        assertThat(rule.cooldownSeconds()).isZero();
        EssentialsConfig cfg = new EssentialsConfig(java.util.Map.of(), 0, 1, true, true, true, 0, 0, 0, java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertThat(cfg.maxHomes()).isEqualTo(1);
        assertThat(cfg.tpaTimeoutSeconds()).isEqualTo(5);
        assertThat(cfg.rule(EssentialsConfig.Kind.HOME)).isNotNull();
    }
}
