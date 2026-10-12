package se.nordia.plugins.economy;

/**
 * Result of a transfer.
 *
 * @param duplicate true if the idempotency key was already used and nothing was applied by this call
 */
public record TransferReceipt(long transactionId, Money amount, Money fromBalanceAfter, Money toBalanceAfter, boolean duplicate) {
}
