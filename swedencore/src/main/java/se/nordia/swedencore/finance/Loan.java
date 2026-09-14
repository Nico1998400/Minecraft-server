package se.nordia.swedencore.finance;

import se.nordia.swedencore.economy.Money;

import java.time.Duration;
import java.time.Instant;

/**
 * A loan between two parties. Repayment follows a fixed schedule: {@code installments} equal parts of
 * {@code totalRepayment} (the last absorbs rounding), due every {@code intervalHours} after acceptance.
 */
public record Loan(long id, PartyType lenderType, String lenderId, String lenderName, PartyType borrowerType, String borrowerId,
                   String borrowerName, Money principal, Money totalRepayment, Money repaid, int installments, int intervalHours,
                   Status status, Instant offeredAt, Instant acceptedAt) {

    public enum PartyType {
        PLAYER, COMPANY
    }

    public enum Status {
        OFFERED, ACTIVE, REPAID, DEFAULTED, DECLINED, WITHDRAWN, SETTLED_IN_BANKRUPTCY
    }

    public Money outstanding() {
        return totalRepayment.minus(repaid);
    }

    /** Amount that must have been repaid once {@code k} installments are due. */
    public long cumulativeDue(int k) {
        int n = Math.clamp(k, 0, installments);
        long base = totalRepayment.ore() / installments;
        return n == installments ? totalRepayment.ore() : base * n;
    }

    /** Number of installments due at {@code at}. */
    public int installmentsDue(Instant at) {
        if (acceptedAt == null || at.isBefore(acceptedAt)) {
            return 0;
        }
        long hours = Duration.between(acceptedAt, at).toHours();
        return (int) Math.min(installments, hours / intervalHours);
    }

    /** Number of installments fully covered by the amount repaid so far. */
    public int installmentsCovered() {
        int k = 0;
        while (k < installments && cumulativeDue(k + 1) <= repaid.ore()) {
            k++;
        }
        return k;
    }

    public Instant nextDueAt() {
        if (acceptedAt == null) {
            return null;
        }
        int next = Math.min(installmentsCovered() + 1, installments);
        return acceptedAt.plus(Duration.ofHours((long) intervalHours * next));
    }
}
