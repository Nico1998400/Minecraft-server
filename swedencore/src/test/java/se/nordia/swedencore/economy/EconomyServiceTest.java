package se.nordia.swedencore.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.DatabaseException;
import se.nordia.swedencore.player.PlayerService;
import se.nordia.swedencore.testing.DatabaseTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EconomyServiceTest extends DatabaseTest {

    private EconomyService economy;
    private PlayerService players;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        economy = new EconomyService(database, new EconomyConfig(Money.ofSek(1_000), Money.ofSek(1_000_000), Money.ofOre(1)));
        players = new PlayerService(database, economy);
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        players.register(alice, "Alice");
        players.register(bob, "Bob");
    }

    private Money balance(UUID player) {
        return economy.balance(AccountOwner.player(player));
    }

    private static String code(Throwable t) {
        return ((DomainException) t).code();
    }

    @Test
    void newPlayersReceiveStarterGrantExactlyOnce() {
        players.register(alice, "Alice");
        players.register(alice, "AliceRenamed");
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_000));
        assertThat(economy.moneySupply()).isEqualTo(Money.ofSek(2_000));
        assertThat(economy.audit().healthy()).isTrue();
    }

    @Test
    void payMovesMoneyAtomically() {
        TransferReceipt receipt = economy.pay(alice, bob, Money.ofSek(250), UUID.randomUUID().toString());
        assertThat(receipt.duplicate()).isFalse();
        assertThat(receipt.fromBalanceAfter()).isEqualTo(Money.ofSek(750));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(750));
        assertThat(balance(bob)).isEqualTo(Money.ofSek(1_250));
        assertThat(economy.moneySupply()).isEqualTo(Money.ofSek(2_000));
        assertThat(economy.audit().healthy()).isTrue();
    }

    @Test
    void cannotSpendMoreThanBalance() {
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofOre(100_001), "k1"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.insufficient_funds"));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_000));
        assertThat(balance(bob)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void canSpendExactBalance() {
        economy.pay(alice, bob, Money.ofSek(1_000), "exact");
        assertThat(balance(alice)).isEqualTo(Money.ZERO);
    }

    @Test
    void rejectsZeroNegativeAndSelfPayments() {
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ZERO, "z"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.invalid_amount"));
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofOre(-100), "n"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.invalid_amount"));
        assertThatThrownBy(() -> economy.pay(alice, alice, Money.ofSek(1), "s"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.pay_self"));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void negativeTransferCannotStealViaGenericTransfer() {
        long a = economy.findAccount(AccountOwner.player(alice)).orElseThrow().id();
        long b = economy.findAccount(AccountOwner.player(bob)).orElseThrow().id();
        assertThatThrownBy(() -> economy.transfer(TransferRequest.of(a, b, Money.ofOre(-500), TransactionType.PLAYER_PAYMENT)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.invalid_amount"));
        assertThatThrownBy(() -> economy.transfer(TransferRequest.of(a, a, Money.ofOre(500), TransactionType.PLAYER_PAYMENT)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.same_account"));
    }

    @Test
    void rejectsAmountsAboveMaximum() {
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofSek(1_000_001), "big"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.amount_too_large"));
    }

    @Test
    void payingUnknownPlayerFails() {
        assertThatThrownBy(() -> economy.pay(alice, UUID.randomUUID(), Money.ofSek(1), "u"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("player.unknown"));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_000));
    }

    @Test
    void duplicateIdempotencyKeyDoesNotChargeTwice() {
        TransferReceipt first = economy.pay(alice, bob, Money.ofSek(100), "same-key");
        TransferReceipt second = economy.pay(alice, bob, Money.ofSek(100), "same-key");
        assertThat(second.duplicate()).isTrue();
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(balance(alice)).isEqualTo(Money.ofSek(900));
        assertThat(balance(bob)).isEqualTo(Money.ofSek(1_100));
    }

    @Test
    void reusingIdempotencyKeyForDifferentOperationIsRejected() {
        economy.pay(alice, bob, Money.ofSek(100), "reused");
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofSek(900), "reused"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.idempotency_conflict"));
        assertThatThrownBy(() -> economy.pay(bob, alice, Money.ofSek(100), "reused"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.idempotency_conflict"));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(900));
    }

    @Test
    void concurrentSpendingCannotDoubleSpend() throws Exception {
        // Alice has 1000 SEK and fires 50 concurrent 100 SEK payments: exactly 10 may succeed.
        int attempts = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    economy.pay(alice, bob, Money.ofSek(100), UUID.randomUUID().toString());
                    return true;
                } catch (DomainException e) {
                    if (e.code().equals("economy.insufficient_funds")) {
                        insufficient.incrementAndGet();
                    }
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(40);
        assertThat(balance(alice)).isEqualTo(Money.ZERO);
        assertThat(balance(bob)).isEqualTo(Money.ofSek(2_000));
        assertThat(economy.audit().healthy()).isTrue();
    }

    @Test
    void concurrentOpposingTransfersDoNotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final boolean aliceToBob = i % 2 == 0;
            tasks.add(() -> {
                start.await();
                if (aliceToBob) {
                    economy.pay(alice, bob, Money.ofSek(1), UUID.randomUUID().toString());
                } else {
                    economy.pay(bob, alice, Money.ofSek(1), UUID.randomUUID().toString());
                }
                return null;
            });
        }
        List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_000));
        assertThat(balance(bob)).isEqualTo(Money.ofSek(1_000));
        assertThat(economy.audit().healthy()).isTrue();
    }

    @Test
    void concurrentDuplicateKeyAppliesOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferReceipt>> futures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return economy.pay(alice, bob, Money.ofSek(10), "reward-once");
            }));
        }
        start.countDown();
        long applied = 0;
        for (Future<TransferReceipt> f : futures) {
            if (!f.get().duplicate()) {
                applied++;
            }
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(balance(bob)).isEqualTo(Money.ofSek(1_010));
    }

    @Test
    void databaseConstraintBlocksNegativeBalanceEvenIfCodeIsBypassed() {
        long a = economy.findAccount(AccountOwner.player(alice)).orElseThrow().id();
        assertThatThrownBy(() -> database.inTransaction(tx -> tx.update("UPDATE accounts SET balance = -1 WHERE id = ?", a)))
                .isInstanceOf(DatabaseException.class);
        assertThatThrownBy(() -> database.inTransaction(tx -> tx.update(
                "UPDATE accounts SET allow_negative = TRUE WHERE id = ?", a)))
                .isInstanceOf(DatabaseException.class);
    }

    @Test
    void balanceOverflowIsRejected() {
        // Give Bob an enormous (but legal) balance via direct mint in several steps would take too long;
        // simulate by setting balance and matching ledger is not possible, so test the service guard directly.
        long b = economy.findAccount(AccountOwner.player(bob)).orElseThrow().id();
        database.inTransactionVoid(tx -> tx.update("UPDATE accounts SET balance = ? WHERE id = ?", Long.MAX_VALUE - 10, b));
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofSek(1), "overflow"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.balance_overflow"));
    }

    @Test
    void frozenAccountsCannotTransact() {
        long a = economy.findAccount(AccountOwner.player(alice)).orElseThrow().id();
        database.inTransactionVoid(tx -> tx.update("UPDATE accounts SET frozen = TRUE WHERE id = ?", a));
        assertThatThrownBy(() -> economy.pay(alice, bob, Money.ofSek(1), "frozen"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.account_frozen"));
        assertThatThrownBy(() -> economy.pay(bob, alice, Money.ofSek(1), "frozen2"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.account_frozen"));
    }

    @Test
    void adminGrantAndRemovalAreLedgeredAndChangeSupply() {
        UUID admin = UUID.randomUUID();
        economy.adminGrant(admin, alice, Money.ofSek(500));
        economy.adminRemove(admin, bob, Money.ofSek(200));
        assertThat(balance(alice)).isEqualTo(Money.ofSek(1_500));
        assertThat(balance(bob)).isEqualTo(Money.ofSek(800));
        assertThat(economy.moneySupply()).isEqualTo(Money.ofSek(2_300));
        assertThat(economy.audit().healthy()).isTrue();
        assertThatThrownBy(() -> economy.adminRemove(admin, bob, Money.ofSek(801)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("economy.insufficient_funds"));
    }

    @Test
    void historyShowsSignedEntriesNewestFirst() {
        economy.pay(alice, bob, Money.ofSek(100), "h1");
        economy.pay(bob, alice, Money.ofSek(30), "h2");
        List<LedgerEntry> history = economy.history(AccountOwner.player(alice), 10);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).signedAmount()).isEqualTo(Money.ofSek(30));
        assertThat(history.get(0).balanceAfter()).isEqualTo(Money.ofSek(930));
        assertThat(history.get(1).signedAmount()).isEqualTo(Money.ofSek(-100));
        assertThat(history.get(2).type()).isEqualTo(TransactionType.STARTER_GRANT);
        assertThat(history.get(2).counterparty()).isEqualTo(AccountOwner.MINT);
    }
}
