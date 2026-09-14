package se.nordia.swedencore.settlements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementServiceTest extends CoreTest {

    private SettlementService settlements;
    private UUID founder;

    /** Small, fast tiers for testing growth. */
    @Override
    protected CoreConfig configure(CoreConfig d) {
        Map<Settlement.Tier, SettlementConfig.Requirements> tiers = new EnumMap<>(Settlement.Tier.class);
        tiers.put(Settlement.Tier.OUTPOST, new SettlementConfig.Requirements(32, 1, Money.ZERO, 0, 0, Money.ofSek(1_000)));
        tiers.put(Settlement.Tier.SETTLEMENT, new SettlementConfig.Requirements(64, 2, Money.ofSek(5_000), 3, 0, Money.ofSek(2_000)));
        tiers.put(Settlement.Tier.VILLAGE, new SettlementConfig.Requirements(128, 3, Money.ofSek(10_000), 7, 10, Money.ofSek(5_000)));
        tiers.put(Settlement.Tier.TOWN, new SettlementConfig.Requirements(192, 3, Money.ofSek(10_000), 7, 10, Money.ofSek(5_000)));
        tiers.put(Settlement.Tier.CITY, new SettlementConfig.Requirements(256, 3, Money.ofSek(10_000), 7, 10, Money.ofSek(5_000)));
        return new CoreConfig(d.database(), d.economy(), d.skills(), d.companies(), d.contracts(), d.properties(), d.shops(),
                new SettlementConfig(tiers, 64, 20), d.orders(), d.production(), d.loans(), d.leases(), d.storage(), d.defaultLocale(), d.shutdownOnDatabaseFailure());
    }

    @BeforeEach
    void setUp() {
        settlements = core.settlements();
        founder = player("Founder");
        grant(founder, 50_000);
    }

    private Settlement found(String name, int x) {
        return settlements.found(founder, name, "world", x, 0);
    }

    @Test
    void foundingCostsMoneyAndMakesLeader() {
        Settlement s = found("Björkby", 10_000);
        assertThat(s.tier()).isEqualTo(Settlement.Tier.OUTPOST);
        assertThat(s.radius()).isEqualTo(32);
        assertThat(balance(founder)).isEqualTo(Money.ofSek(50_000));
        assertThat(settlements.memberOf(founder)).get().satisfies(m -> assertThat(m.role()).isEqualTo(Settlement.Role.LEADER));
        assertDomainError(() -> found("Andra byn", 50_000), "settlement.already_member");
        assertLedgerHealthy();
    }

    @Test
    void placementReservesRoomForGrowth() {
        core.cities().create("Stockholm", "world", 0, 0, 500);
        UUID other = player("Other");
        grant(other, 10_000);
        // City edge 500 + max radius 256 + buffer 64 = 820 blocks from the city centre.
        assertDomainError(() -> settlements.found(founder, "Förorten", "world", 800, 0), "settlement.too_close_to_city");
        found("Björkby", 5_000);
        // Two max-size settlements plus buffer: 2*256 + 64 = 576.
        assertDomainError(() -> settlements.found(other, "Grannby", "world", 5_500, 0), "settlement.too_close");
        settlements.found(other, "Grannby", "world", 5_600, 0);
        assertDomainError(() -> core.cities().create("Nystad", "world", 5_300, 400, 100), "city.too_close_to_settlement");
        assertDomainError(() -> settlements.found(player("Third"), "x", "world", 20_000, 0), "settlement.invalid_name");
    }

    @Test
    void invitesAndMembershipRules() {
        Settlement s = found("Björkby", 10_000);
        UUID resident = player("Resident");
        UUID stranger = player("Stranger");
        assertDomainError(() -> settlements.join(resident, "Björkby"), "settlement.not_invited");
        assertDomainError(() -> settlements.invite(stranger, s.id(), resident), "settlement.not_member");
        settlements.invite(founder, s.id(), resident);
        settlements.join(resident, "björkby");
        assertDomainError(() -> settlements.invite(resident, s.id(), stranger), "settlement.no_permission");
        settlements.setRole(founder, s.id(), resident, Settlement.Role.OFFICER);
        settlements.invite(resident, s.id(), stranger);
        settlements.join(stranger, "Björkby");
        assertDomainError(() -> settlements.kick(resident, s.id(), founder), "settlement.no_permission");
        settlements.kick(resident, s.id(), stranger);
        assertDomainError(() -> settlements.leave(founder), "settlement.leader_cannot_leave");
        settlements.transferLeadership(founder, s.id(), resident);
        settlements.leave(founder);
        assertThat(settlements.find(s.id()).orElseThrow().leader()).isEqualTo(resident);
    }

    @Test
    void growthRequiresMembersTreasuryAgeAndReputation() {
        Settlement s = found("Björkby", 10_000);
        assertDomainError(() -> settlements.upgrade(founder, s.id()), "settlement.requirements_not_met");
        UUID resident = player("Resident");
        settlements.invite(founder, s.id(), resident);
        settlements.join(resident, "Björkby");
        settlements.deposit(founder, s.id(), Money.ofSek(6_000));
        assertDomainError(() -> settlements.upgrade(founder, s.id()), "settlement.requirements_not_met");
        clock.advance(Duration.ofDays(3));
        assertDomainError(() -> settlements.upgrade(resident, s.id()), "settlement.no_permission");

        Money supply = core.economy().moneySupply();
        Settlement upgraded = settlements.upgrade(founder, s.id());
        assertThat(upgraded.tier()).isEqualTo(Settlement.Tier.SETTLEMENT);
        assertThat(upgraded.radius()).isEqualTo(64);
        assertThat(core.economy().balance(AccountOwner.settlement(s.id()))).isEqualTo(Money.ofSek(4_000));
        assertThat(core.economy().moneySupply()).isEqualTo(supply.minus(Money.ofSek(2_000)));

        // Village needs leader reputation 10 as well.
        UUID third = player("Third");
        settlements.invite(founder, s.id(), third);
        settlements.join(third, "Björkby");
        settlements.deposit(founder, s.id(), Money.ofSek(10_000));
        clock.advance(Duration.ofDays(7));
        var progress = settlements.summary(s.id()).next();
        assertThat(progress.membersMet()).isTrue();
        assertThat(progress.reputationMet()).isFalse();
        assertDomainError(() -> settlements.upgrade(founder, s.id()), "settlement.requirements_not_met");
        core.reputation().adjust(ReputationService.Subject.player(founder), 10, "TEST", "rep");
        assertThat(settlements.upgrade(founder, s.id()).tier()).isEqualTo(Settlement.Tier.VILLAGE);
        assertLedgerHealthy();
    }

    @Test
    void treasuryRules() {
        Settlement s = found("Björkby", 10_000);
        UUID resident = player("Resident");
        settlements.invite(founder, s.id(), resident);
        settlements.join(resident, "Björkby");
        settlements.deposit(resident, s.id(), Money.ofSek(500));
        assertDomainError(() -> settlements.withdraw(resident, s.id(), Money.ofSek(100)), "settlement.no_permission");
        assertDomainError(() -> settlements.deposit(player("Outsider"), s.id(), Money.ofSek(1)), "settlement.not_member");
        settlements.withdraw(founder, s.id(), Money.ofSek(200));
        assertDomainError(() -> settlements.disband(founder, s.id()), "settlement.disband_has_members");
        settlements.kick(founder, s.id(), resident);
        assertThat(settlements.disband(founder, s.id())).isEqualTo(Money.ofSek(300));
        assertThat(settlements.memberOf(founder)).isEmpty();
        found("Björkby", 10_000);
        assertLedgerHealthy();
    }

    @Test
    void concurrentFoundingCannotViolateSpacing() throws Exception {
        List<UUID> founders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID f = player("Nybyggare" + i);
            grant(f, 5_000);
            founders.add(f);
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < founders.size(); i++) {
            UUID f = founders.get(i);
            int x = 30_000 + i * 10;
            String name = "Kolonin " + (char) ('A' + i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    settlements.found(f, name, "world", x, 0);
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int founded = 0;
        for (Future<Boolean> future : futures) {
            founded += future.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(founded).isEqualTo(1);
    }
}
