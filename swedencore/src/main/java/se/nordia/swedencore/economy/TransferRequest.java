package se.nordia.swedencore.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * A request to move money between two accounts.
 *
 * @param idempotencyKey optional; if a transaction with this key already exists, the transfer is not applied again.
 *                       Reusing a key with different parameters is rejected as an attack/bug.
 * @param actor          the player who caused the transfer, if any (audit trail)
 * @param referenceType  optional domain reference, e.g. {@code CONTRACT}
 * @param referenceId    optional domain reference id
 */
public record TransferRequest(
        long fromAccountId,
        long toAccountId,
        Money amount,
        TransactionType type,
        String idempotencyKey,
        UUID actor,
        String referenceType,
        String referenceId,
        String memo
) {
    public TransferRequest {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128)) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        if (memo != null && memo.length() > 200) {
            memo = memo.substring(0, 200);
        }
    }

    public static TransferRequest of(long from, long to, Money amount, TransactionType type) {
        return new TransferRequest(from, to, amount, type, null, null, null, null, null);
    }

    public TransferRequest withKey(String key) {
        return new TransferRequest(fromAccountId, toAccountId, amount, type, key, actor, referenceType, referenceId, memo);
    }

    public TransferRequest withActor(UUID newActor) {
        return new TransferRequest(fromAccountId, toAccountId, amount, type, idempotencyKey, newActor, referenceType, referenceId, memo);
    }

    public TransferRequest withReference(String refType, String refId) {
        return new TransferRequest(fromAccountId, toAccountId, amount, type, idempotencyKey, actor, refType, refId, memo);
    }

    public TransferRequest withMemo(String newMemo) {
        return new TransferRequest(fromAccountId, toAccountId, amount, type, idempotencyKey, actor, referenceType, referenceId, newMemo);
    }
}
