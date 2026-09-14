package se.nordia.swedencore.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.reputation.ReputationService;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.testing.CoreTest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransportServiceTest extends CoreTest {

    private static final ItemStashService.ItemCodec CODEC = new ItemStashService.ItemCodec() {
        @Override
        public byte[] pristine(String material, int amount) {
            return (material + ":" + amount).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int maxStackSize(String material) {
            return 64;
        }

        @Override
        public boolean isKnownMaterial(String material) {
            return true;
        }
    };

    private static final TransportService.Point PICKUP = new TransportService.Point("world", 0, 64, 0);
    private static final TransportService.Point DEST = new TransportService.Point("world", 1_000, 64, 0);

    private UUID issuer;
    private UUID carrier;
    private TransportService transports;

    @BeforeEach
    void setUp() {
        transports = core.transports();
        issuer = player("Issuer");
        carrier = player("Carrier");
        grant(issuer, 20_000);
        core.database().inTransactionVoid(tx -> core.stash().deposit(tx, ItemStashService.Owner.player(issuer),
                List.of(new ItemStashService.StashItem("IRON_INGOT", 64, CODEC.pristine("IRON_INGOT", 64), true),
                        new ItemStashService.StashItem("IRON_INGOT", 36, CODEC.pristine("IRON_INGOT", 36), true)), "TEST", null));
    }

    private TransportService.Transport create(long collateralSek) {
        return transports.create(issuer, null, "IRON_INGOT", 100, Money.ofSek(2_000), Money.ofSek(collateralSek), PICKUP, DEST, 24, CODEC);
    }

    private long stashTotal(UUID owner) {
        return core.stash().summary(ItemStashService.Owner.player(owner)).stream().mapToLong(ItemStashService.MaterialCount::total).sum();
    }

    private static List<ItemStashService.StashItem> cargo(int... amounts) {
        return java.util.Arrays.stream(amounts).mapToObj(a -> new ItemStashService.StashItem("IRON_INGOT", a, new byte[]{1}, true)).toList();
    }

    @Test
    void fullDeliveryPaysRewardReturnsCollateralAndGrantsLogisticsXp() {
        var t = create(500);
        assertThat(stashTotal(issuer)).isZero();
        assertThat(t.xpReward()).isEqualTo(200);
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(21_000 - 2_000 - 40));

        UUID pickupToken = UUID.randomUUID();
        transports.pickUp(carrier, t.id(), pickupToken);
        assertThat(transports.pickupRecorded(pickupToken)).isTrue();
        assertThat(balance(carrier)).isEqualTo(Money.ofSek(500));
        assertDomainError(() -> transports.deliver(carrier, t.id(), cargo(64), UUID.randomUUID()), "transport.incomplete_cargo");

        transports.deliver(carrier, t.id(), cargo(64, 36), UUID.randomUUID());
        assertThat(balance(carrier)).isEqualTo(Money.ofSek(3_000));
        assertThat(stashTotal(issuer)).isEqualTo(100);
        assertThat(core.skills().load(carrier).get(Skill.LOGISTICS).xp()).isEqualTo(200);
        assertLedgerHealthy();
    }

    @Test
    void rules() {
        assertDomainError(() -> transports.create(issuer, null, "IRON_INGOT", 100, Money.ofSek(10), Money.ZERO, PICKUP,
                new TransportService.Point("world", 50, 64, 0), 24, CODEC), "transport.too_close");
        assertDomainError(() -> transports.create(issuer, null, "IRON_INGOT", 500, Money.ofSek(10), Money.ZERO, PICKUP, DEST, 24, CODEC),
                "production.missing_input");
        var t = create(5_000);
        assertDomainError(() -> transports.pickUp(issuer, t.id(), UUID.randomUUID()), "transport.own_transport");
        assertDomainError(() -> transports.pickUp(carrier, t.id(), UUID.randomUUID()), "economy.insufficient_funds");
        assertDomainError(() -> transports.deliver(carrier, t.id(), cargo(64, 36), UUID.randomUUID()), "transport.not_carrier");
    }

    @Test
    void failedTransportCompensatesIssuerWithCollateral() {
        var t = create(800);
        transports.pickUp(carrier, t.id(), UUID.randomUUID());
        clock.advance(Duration.ofHours(25));
        assertThat(transports.expireDue(CODEC)).extracting(TransportService.Transport::status).containsExactly(TransportService.Status.FAILED);
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(21_000 - 40 + 800));
        assertThat(balance(carrier)).isEqualTo(Money.ofSek(200));
        assertThat(core.reputation().score(ReputationService.Subject.player(carrier))).isEqualTo(-5);
        assertLedgerHealthy();
    }

    @Test
    void cancelledTransportReturnsCargoAndReward() {
        var t = create(0);
        assertDomainError(() -> transports.cancel(carrier, t.id(), CODEC), "transport.not_issuer");
        transports.cancel(issuer, t.id(), CODEC);
        assertThat(stashTotal(issuer)).isEqualTo(100);
        assertThat(balance(issuer)).isEqualTo(Money.ofSek(21_000 - 40));
        assertDomainError(() -> transports.pickUp(carrier, t.id(), UUID.randomUUID()), "transport.not_open");
    }

    @Test
    void bankruptcyReturnsOpenTransportEscrowToCreditors() {
        var company = core.companies().found(issuer, "Frakt AB");
        core.database().inTransactionVoid(tx -> core.stash().deposit(tx, ItemStashService.Owner.company(company.id()),
                List.of(new ItemStashService.StashItem("IRON_INGOT", 64, CODEC.pristine("IRON_INGOT", 64), true)), "TEST", null));
        UUID lender = player("Lender");
        grant(lender, 5_000);
        var loan = core.loans().offer(lender, se.nordia.swedencore.finance.LoanService.Party.player(lender),
                se.nordia.swedencore.finance.LoanService.Party.company(company.id()), Money.ofSek(1_000), 10, 2, 24);
        core.loans().accept(issuer, loan.id());
        core.companies().deposit(issuer, company.id(), Money.ofSek(3_000));
        var t = transports.create(issuer, company.id(), "IRON_INGOT", 64, Money.ofSek(2_000), Money.ZERO, PICKUP, DEST, 24, CODEC);
        assertThat(core.companies().balance(company.id())).isEqualTo(Money.ofSek(1_960));

        var result = core.bankruptcy().declare(company.id(), se.nordia.swedencore.finance.BankruptcyService.Reason.VOLUNTARY, issuer);
        assertThat(result.assets()).isEqualTo(Money.ofSek(3_960));
        assertThat(result.paidCreditors()).isEqualTo(Money.ofSek(1_100));
        assertThat(transports.find(t.id()).orElseThrow().status()).isEqualTo(TransportService.Status.CANCELLED);
        assertThat(core.database().inTransaction(tx -> core.economy().findAccount(tx,
                se.nordia.swedencore.economy.AccountOwner.transport(t.id()), se.nordia.swedencore.economy.Account.ESCROW))
                .orElseThrow().balance()).isEqualTo(Money.ZERO);
        assertDomainError(() -> transports.pickUp(carrier, t.id(), UUID.randomUUID()), "transport.not_open");
        assertLedgerHealthy();
    }
}
