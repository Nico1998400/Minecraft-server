package se.nordia.swedencore.trade;

import se.nordia.swedencore.economy.Money;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The negotiation state of one trade, independent of how items are represented.
 *
 * <p>Rules that defeat classic trade scams:
 * <ul>
 *   <li>Any change to either offer clears <em>both</em> confirmations.</li>
 *   <li>Confirming is only possible {@code confirmCooldownMillis} after the last change, so an offer cannot be
 *       swapped at the last instant.</li>
 *   <li>Once both confirm, the state is frozen ({@link Status#COMPLETING}); nothing can be added or removed.</li>
 * </ul>
 * Not thread-safe; confine to one thread.
 *
 * @param <I> item representation
 */
public final class TradeState<I> {

    public enum Side {
        A, B;

        public Side other() {
            return this == A ? B : A;
        }
    }

    public enum Status {
        OPEN, COMPLETING, DONE, CANCELLED
    }

    public enum ConfirmResult {
        CONFIRMED, READY, TOO_SOON, NOT_OPEN
    }

    private final UUID playerA;
    private final UUID playerB;
    private final int maxItems;
    private final long confirmCooldownMillis;
    private final Money maxMoney;
    private final Map<Side, List<I>> items = new EnumMap<>(Side.class);
    private final Map<Side, Money> money = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> confirmed = new EnumMap<>(Side.class);
    private long lastChangeMillis;
    private Status status = Status.OPEN;

    public TradeState(UUID playerA, UUID playerB, int maxItems, long confirmCooldownMillis, Money maxMoney, long nowMillis) {
        if (Objects.equals(playerA, playerB)) {
            throw new IllegalArgumentException("Cannot trade with yourself");
        }
        this.playerA = playerA;
        this.playerB = playerB;
        this.maxItems = maxItems;
        this.confirmCooldownMillis = confirmCooldownMillis;
        this.maxMoney = maxMoney;
        for (Side side : Side.values()) {
            items.put(side, new ArrayList<>());
            money.put(side, Money.ZERO);
            confirmed.put(side, false);
        }
        this.lastChangeMillis = nowMillis;
    }

    public UUID player(Side side) {
        return side == Side.A ? playerA : playerB;
    }

    public Side sideOf(UUID player) {
        if (playerA.equals(player)) {
            return Side.A;
        }
        if (playerB.equals(player)) {
            return Side.B;
        }
        throw new IllegalArgumentException("Not part of this trade");
    }

    public Status status() {
        return status;
    }

    public List<I> items(Side side) {
        return Collections.unmodifiableList(items.get(side));
    }

    public Money money(Side side) {
        return money.get(side);
    }

    public boolean confirmed(Side side) {
        return confirmed.get(side);
    }

    public boolean addItem(Side side, I item, long now) {
        if (status != Status.OPEN || items.get(side).size() >= maxItems) {
            return false;
        }
        items.get(side).add(Objects.requireNonNull(item));
        changed(now);
        return true;
    }

    /** Removes the item at an index of the side's offer, or returns null. */
    public I removeItem(Side side, int index, long now) {
        List<I> list = items.get(side);
        if (status != Status.OPEN || index < 0 || index >= list.size()) {
            return null;
        }
        I removed = list.remove(index);
        changed(now);
        return removed;
    }

    public boolean setMoney(Side side, Money amount, long now) {
        if (status != Status.OPEN || amount.isNegative() || amount.isGreaterThan(maxMoney)) {
            return false;
        }
        if (!amount.equals(money.get(side))) {
            money.put(side, amount);
            changed(now);
        }
        return true;
    }

    public ConfirmResult confirm(Side side, long now) {
        if (status != Status.OPEN) {
            return ConfirmResult.NOT_OPEN;
        }
        if (now - lastChangeMillis < confirmCooldownMillis) {
            return ConfirmResult.TOO_SOON;
        }
        confirmed.put(side, true);
        if (confirmed.get(Side.A) && confirmed.get(Side.B)) {
            status = Status.COMPLETING;
            return ConfirmResult.READY;
        }
        return ConfirmResult.CONFIRMED;
    }

    public void unconfirm(Side side) {
        if (status == Status.OPEN) {
            confirmed.put(side, false);
        }
    }

    /** Completion failed before anything was exchanged: reopen for editing with confirmations cleared. */
    public void reopen(long now) {
        if (status == Status.COMPLETING) {
            status = Status.OPEN;
            changed(now);
        }
    }

    public void markDone() {
        status = Status.DONE;
    }

    /** Cancels the trade and returns every offered item per side (to be given back). Idempotent. */
    public Map<Side, List<I>> cancel() {
        Map<Side, List<I>> refunds = new EnumMap<>(Side.class);
        if (status == Status.DONE || status == Status.CANCELLED) {
            refunds.put(Side.A, List.of());
            refunds.put(Side.B, List.of());
            return refunds;
        }
        status = Status.CANCELLED;
        for (Side side : Side.values()) {
            refunds.put(side, List.copyOf(items.get(side)));
            items.get(side).clear();
        }
        return refunds;
    }

    public boolean isEmpty() {
        return items.get(Side.A).isEmpty() && items.get(Side.B).isEmpty()
                && money.get(Side.A).isZero() && money.get(Side.B).isZero();
    }

    private void changed(long now) {
        confirmed.put(Side.A, false);
        confirmed.put(Side.B, false);
        lastChangeMillis = now;
    }
}
