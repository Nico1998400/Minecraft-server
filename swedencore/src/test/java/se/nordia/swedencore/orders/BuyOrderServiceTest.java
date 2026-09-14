package se.nordia.swedencore.orders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.testing.CoreTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class BuyOrderServiceTest extends CoreTest {

    private BuyOrderService orders;
    private UUID buyer;
    private UUID seller;

    @BeforeEach
    void setUp() {
        orders = core.orders();
        buyer = player("Buyer");
        seller = player("Seller");
        grant(buyer, 100_000);
    }

    private static List<ItemStashService.StashItem> items(String material, int... amounts) {
        List<ItemStashService.StashItem> list = new ArrayList<>();
        for (int amount : amounts) {
            list.add(new ItemStashService.StashItem(material, amount, new byte[]{1}));
        }
        return list;
    }

    private Money escrow(long id) {
        return core.database().inTransaction(tx -> core.economy().findAccount(tx, AccountOwner.buyOrder(id), Account.ESCROW))
                .map(Account::balance).orElseThrow();
    }

    @Test
    void createEscrowsBudgetAndSellersArePaidPerItem() {
        BuyOrder order = orders.create(buyer, null, "IRON_ORE", 100, Money.ofSek(18), 24);
        assertThat(escrow(order.id())).isEqualTo(Money.ofSek(1_800));
        assertThat(balance(buyer)).isEqualTo(Money.ofSek(101_000 - 1_800 - 18));

        var fill = orders.fill(seller, order.id(), items("IRON_ORE", 64, 6), UUID.randomUUID());
        assertThat(fill.payout()).isEqualTo(Money.ofSek(1_260));
        assertThat(fill.order().remaining()).isEqualTo(30);
        assertThat(balance(seller)).isEqualTo(Money.ofSek(2_260));

        UUID second = player("Second");
        var last = orders.fill(second, order.id(), items("IRON_ORE", 30), UUID.randomUUID());
        assertThat(last.order().status()).isEqualTo(BuyOrder.Status.FILLED);
        assertThat(escrow(order.id())).isEqualTo(Money.ZERO);
        assertThat(core.stash().summary(ItemStashService.Owner.player(buyer))).singleElement()
                .satisfies(s -> assertThat(s.total()).isEqualTo(100));
        assertDomainError(() -> orders.fill(seller, order.id(), items("IRON_ORE", 1), UUID.randomUUID()), "order.not_open");
        assertLedgerHealthy();
    }

    @Test
    void fillRules() {
        BuyOrder order = orders.create(buyer, null, "WHEAT", 10, Money.ofSek(5), 24);
        assertDomainError(() -> orders.fill(buyer, order.id(), items("WHEAT", 1), UUID.randomUUID()), "order.own_order");
        assertDomainError(() -> orders.fill(seller, order.id(), items("CARROT", 1), UUID.randomUUID()), "contract.wrong_material");
        assertDomainError(() -> orders.fill(seller, order.id(), items("WHEAT", 11), UUID.randomUUID()), "contract.too_many_items");
        UUID token = UUID.randomUUID();
        orders.fill(seller, order.id(), items("WHEAT", 2), token);
        assertThat(orders.fillRecorded(token)).isTrue();
        assertDomainError(() -> orders.fill(seller, order.id(), items("WHEAT", 2), token), "order.duplicate_fill");
        assertThat(balance(seller)).isEqualTo(Money.ofSek(1_010));
    }

    @Test
    void cancelAndExpiryRefundRemainingEscrow() {
        BuyOrder a = orders.create(buyer, null, "COAL", 10, Money.ofSek(10), 1);
        BuyOrder b = orders.create(buyer, null, "COAL", 10, Money.ofSek(10), 48);
        orders.fill(seller, a.id(), items("COAL", 4), UUID.randomUUID());
        assertDomainError(() -> orders.cancel(seller, b.id()), "order.not_issuer");
        orders.cancel(buyer, b.id());
        clock.advance(Duration.ofHours(2));
        assertDomainError(() -> orders.fill(seller, a.id(), items("COAL", 1), UUID.randomUUID()), "contract.expired");
        assertThat(orders.expireDue()).extracting(BuyOrder::id).containsExactly(a.id());
        // Escrowed 100 per order plus a 1 SEK fee each; 40 went to the seller; the rest came back.
        assertThat(balance(buyer)).isEqualTo(Money.ofSek(101_000 - 40 - 1 - 1));
        assertLedgerHealthy();
    }

    @Test
    void companyOrdersBlockDissolutionAndManagersCannotSellToThem() {
        Company company = core.companies().found(buyer, "Stål AB");
        core.companies().deposit(buyer, company.id(), Money.ofSek(20_000));
        assertDomainError(() -> orders.create(seller, company.id(), "IRON_INGOT", 10, Money.ofSek(50), 24), "company.not_member");
        BuyOrder order = orders.create(buyer, company.id(), "IRON_INGOT", 10, Money.ofSek(50), 24);
        assertDomainError(() -> core.companies().dissolve(buyer, company.id()), "company.dissolve_has_orders");
        orders.fill(seller, order.id(), items("IRON_INGOT", 10), UUID.randomUUID());
        assertThat(core.stash().summary(ItemStashService.Owner.company(company.id()))).hasSize(1);
        assertThat(orders.issuedBy(buyer)).isEmpty();
    }

    @Test
    void concurrentSellersCannotOverfill() throws Exception {
        BuyOrder order = orders.create(buyer, null, "OAK_LOG", 20, Money.ofSek(3), 24);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID s = player("Logger" + i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return orders.fill(s, order.id(), items("OAK_LOG", 6), UUID.randomUUID()).quantity();
                } catch (DomainException e) {
                    return 0;
                }
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : futures) {
            total += f.get();
        }
        pool.shutdown();
        assertThat(total).isEqualTo(18);
        assertThat(escrow(order.id())).isEqualTo(Money.ofSek(6));
        assertLedgerHealthy();
    }
}
