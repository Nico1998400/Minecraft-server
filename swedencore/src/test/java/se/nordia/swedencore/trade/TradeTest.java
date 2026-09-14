package se.nordia.swedencore.trade;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.testing.CoreTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static se.nordia.swedencore.trade.TradeState.ConfirmResult;
import static se.nordia.swedencore.trade.TradeState.Side.A;
import static se.nordia.swedencore.trade.TradeState.Side.B;

class TradeTest extends CoreTest {

    private static TradeState<String> state(long now) {
        return new TradeState<>(UUID.randomUUID(), UUID.randomUUID(), 3, 3_000, Money.ofSek(1_000_000), now);
    }

    @Test
    void bothMustConfirmAfterCooldown() {
        TradeState<String> t = state(0);
        t.addItem(A, "diamond", 1_000);
        assertThat(t.confirm(A, 2_000)).isEqualTo(ConfirmResult.TOO_SOON);
        assertThat(t.confirm(A, 4_000)).isEqualTo(ConfirmResult.CONFIRMED);
        assertThat(t.confirm(B, 4_100)).isEqualTo(ConfirmResult.READY);
        assertThat(t.status()).isEqualTo(TradeState.Status.COMPLETING);
        assertThat(t.addItem(A, "sneaky", 5_000)).isFalse();
        assertThat(t.removeItem(A, 0, 5_000)).isNull();
        assertThat(t.setMoney(B, Money.ofSek(1), 5_000)).isFalse();
    }

    @Test
    void anyChangeClearsBothConfirmations() {
        TradeState<String> t = state(0);
        t.addItem(A, "diamond", 0);
        t.confirm(A, 10_000);
        assertThat(t.confirmed(A)).isTrue();
        // B changes their money offer: A's confirmation is gone, and the cooldown restarts.
        t.setMoney(B, Money.ofSek(10), 10_500);
        assertThat(t.confirmed(A)).isFalse();
        assertThat(t.confirm(B, 11_000)).isEqualTo(ConfirmResult.TOO_SOON);
        // A removes the diamond after B confirmed: B must re-confirm the new deal.
        t.confirm(B, 14_000);
        t.removeItem(A, 0, 14_100);
        assertThat(t.confirmed(B)).isFalse();
    }

    @Test
    void limitsAndCancellationReturnEverything() {
        TradeState<String> t = state(0);
        assertThat(t.addItem(A, "1", 0)).isTrue();
        assertThat(t.addItem(A, "2", 0)).isTrue();
        assertThat(t.addItem(A, "3", 0)).isTrue();
        assertThat(t.addItem(A, "4", 0)).isFalse();
        t.addItem(B, "x", 0);
        assertThat(t.setMoney(A, Money.ofOre(-1), 0)).isFalse();
        assertThat(t.setMoney(A, Money.ofSek(2_000_000), 0)).isFalse();
        var refunds = t.cancel();
        assertThat(refunds.get(A)).containsExactly("1", "2", "3");
        assertThat(refunds.get(B)).containsExactly("x");
        assertThat(t.cancel().get(A)).isEmpty();
        assertThat(t.addItem(A, "late", 0)).isFalse();
    }

    @Test
    void reopenAfterFailedCompletion() {
        TradeState<String> t = state(0);
        t.confirm(A, 5_000);
        t.confirm(B, 5_000);
        t.reopen(6_000);
        assertThat(t.status()).isEqualTo(TradeState.Status.OPEN);
        assertThat(t.confirmed(A)).isFalse();
        assertThat(t.confirm(A, 7_000)).isEqualTo(ConfirmResult.TOO_SOON);
    }

    @Test
    void completionMovesMoneyBothWaysAtomically() {
        UUID a = player("Alice");
        UUID b = player("Bob");
        UUID token = UUID.randomUUID();
        core.trades().complete(token, a, b, Money.ofSek(300), Money.ofSek(100), "1x DIAMOND", "64x IRON_INGOT");
        assertThat(balance(a)).isEqualTo(Money.ofSek(800));
        assertThat(balance(b)).isEqualTo(Money.ofSek(1_200));
        assertThat(core.trades().tradeRecorded(token)).isTrue();
        assertDomainError(() -> core.trades().complete(token, a, b, Money.ofSek(300), Money.ZERO, "", ""), "trade.already_completed");
        assertLedgerHealthy();
    }

    @Test
    void failedPaymentRecordsNothing() {
        UUID a = player("Alice");
        UUID b = player("Bob");
        UUID token = UUID.randomUUID();
        // B can pay, A cannot: the whole trade must roll back, including B's payment.
        assertDomainError(() -> core.trades().complete(token, a, b, Money.ofSek(5_000), Money.ofSek(100), "", ""),
                "economy.insufficient_funds");
        assertThat(core.trades().tradeRecorded(token)).isFalse();
        assertThat(balance(a)).isEqualTo(Money.ofSek(1_000));
        assertThat(balance(b)).isEqualTo(Money.ofSek(1_000));
    }
}
