package se.nordia.swedencore.economy;

import java.time.Instant;

/**
 * One transaction seen from the perspective of a single account.
 *
 * @param signedAmount positive for incoming money, negative for outgoing
 * @param counterparty the other account's owner
 */
public record LedgerEntry(
        long transactionId,
        TransactionType type,
        Money signedAmount,
        Money balanceAfter,
        AccountOwner counterparty,
        String memo,
        Instant createdAt
) {
}
