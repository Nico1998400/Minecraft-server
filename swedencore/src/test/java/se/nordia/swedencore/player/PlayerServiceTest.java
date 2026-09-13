package se.nordia.swedencore.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.testing.DatabaseTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerServiceTest extends DatabaseTest {

    private EconomyService economy;
    private PlayerService players;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(database, EconomyConfig.defaults());
        players = new PlayerService(database, economy);
    }

    @Test
    void registersNewPlayerWithAccount() {
        UUID id = UUID.randomUUID();
        PlayerService.Registration reg = players.register(id, "Sven_1");
        assertThat(reg.firstJoin()).isTrue();
        assertThat(reg.player().name()).isEqualTo("Sven_1");
        assertThat(reg.player().locale()).isNull();
        assertThat(economy.balance(AccountOwner.player(id))).isEqualTo(EconomyConfig.defaults().starterGrant());

        PlayerService.Registration again = players.register(id, "Sven_2");
        assertThat(again.firstJoin()).isFalse();
        assertThat(again.player().name()).isEqualTo("Sven_2");
    }

    @Test
    void concurrentRegistrationGrantsOnce() throws Exception {
        UUID id = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> players.register(id, "Racer")));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(economy.balance(AccountOwner.player(id))).isEqualTo(EconomyConfig.defaults().starterGrant());
        assertThat(economy.audit().healthy()).isTrue();
    }

    @Test
    void changedStarterGrantConfigDoesNotBreakExistingPlayersLogin() {
        UUID id = UUID.randomUUID();
        players.register(id, "Old");
        EconomyService richer = new EconomyService(database, new EconomyConfig(Money.ofSek(5_000), Money.ofSek(1_000_000), Money.ofOre(1)));
        new PlayerService(database, richer).register(id, "Old");
        assertThat(economy.balance(AccountOwner.player(id))).isEqualTo(EconomyConfig.defaults().starterGrant());
    }

    @Test
    void findByNameIsCaseInsensitiveAndPrefersMostRecent() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        players.register(oldOwner, "Name");
        database.inTransactionVoid(tx -> tx.update("UPDATE players SET last_seen = now() - interval '10 days' WHERE uuid = ?", oldOwner));
        players.register(newOwner, "NAME");
        assertThat(players.findByName("name")).map(NordiaPlayer::uuid).contains(newOwner);
        assertThat(players.findByName("x'; DROP TABLE players; --")).isEmpty();
    }

    @Test
    void rejectsInvalidNames() {
        assertThatThrownBy(() -> players.register(UUID.randomUUID(), "<red>evil")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> players.register(UUID.randomUUID(), "waytoolongname_12345")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storesLocalePreference() {
        UUID id = UUID.randomUUID();
        players.register(id, "Anna");
        players.setLocale(id, SupportedLocale.EN_US);
        assertThat(players.find(id).orElseThrow().locale()).isEqualTo("en_US");
        players.setLocale(id, null);
        assertThat(players.find(id).orElseThrow().locale()).isNull();
    }
}
