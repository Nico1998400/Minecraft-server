package se.nordia.swedencore.economy;

import java.time.Instant;

/**
 * One transaction seen from the perspective of a single account.
 *
 * @param signedAmount positive for incoming money, negative for outgoing
 * @param counterparty     the other account's owner
 * @param counterpartyName display name of the counterparty if known (player or company name), else {@code null}
 */
public record LedgerEntry(
        long transactionId,
        TransactionType type,
        Money signedAmount,
        Money balanceAfter,
        AccountOwner counterparty,
        String counterpartyName,
        String memo,
        Instant createdAt
) {
}
