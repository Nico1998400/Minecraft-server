package se.nordia.swedencore.economy;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.DatabaseException;
import se.nordia.swedencore.database.Tx;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * All money movement in NORDIA goes through this service.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Atomicity</b> — the ledger row and both balance updates commit together or not at all.</li>
 *   <li><b>No double spending</b> — both accounts are locked {@code FOR UPDATE} in ascending id order before the
 *       balance check, so concurrent transfers serialise and cannot deadlock each other.</li>
 *   <li><b>Idempotency</b> — a repeated idempotency key returns the original receipt without moving money again.</li>
 *   <li><b>Conservation</b> — money is only created from {@link AccountOwner#MINT} and destroyed into
 *       {@link AccountOwner#SINK}; the sum of all balances is always zero.</li>
 *   <li><b>Defence in depth</b> — database CHECK constraints reject negative balances even if code is wrong.</li>
 * </ul>
 *
 * <p>Methods taking a {@link Tx} participate in the caller's transaction so domain changes (e.g. a contract state
 * change) and money movement commit atomically.
 */
public final class EconomyService {

    private final Database database;
    private final EconomyConfig config;

    public EconomyService(Database database, EconomyConfig config) {
        this.database = database;
        this.config = config;
    }

    public EconomyConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ accounts

    public Account getOrCreateAccount(Tx tx, AccountOwner owner, String purpose) throws SQLException {
        tx.update("""
                INSERT INTO accounts (owner_type, owner_id, purpose) VALUES (?, ?, ?)
                ON CONFLICT (owner_type, owner_id, purpose) DO NOTHING""", owner.type(), owner.id(), purpose);
        return findAccount(tx, owner, purpose).orElseThrow(() -> new DatabaseException("Account vanished: " + owner));
    }

    public Optional<Account> findAccount(Tx tx, AccountOwner owner, String purpose) throws SQLException {
        return tx.queryOne("SELECT * FROM accounts WHERE owner_type = ? AND owner_id = ? AND purpose = ?",
                EconomyService::mapAccount, owner.type(), owner.id(), purpose);
    }

    public Optional<Account> findAccount(AccountOwner owner) {
        return database.inTransaction(tx -> findAccount(tx, owner, Account.MAIN));
    }

    public Account requireAccount(Tx tx, AccountOwner owner) throws SQLException {
        return findAccount(tx, owner, Account.MAIN).orElseThrow(() -> new DomainException("economy.account_not_found"));
    }

    public Money balance(AccountOwner owner) {
        return findAccount(owner).map(Account::balance).orElseThrow(() -> new DomainException("economy.account_not_found"));
    }

    public Account systemAccount(Tx tx, AccountOwner systemOwner) throws SQLException {
        return findAccount(tx, systemOwner, Account.MAIN)
                .orElseThrow(() -> new DatabaseException("System account missing: " + systemOwner));
    }

    // ------------------------------------------------------------------ transfers

    public TransferReceipt transfer(TransferRequest request) {
        return database.inTransaction(tx -> transfer(tx, request));
    }

    public TransferReceipt transfer(Tx tx, TransferRequest request) throws SQLException {
        Money amount = request.amount();
        if (!amount.isPositive()) {
            throw new DomainException("economy.invalid_amount");
        }
        if (amount.isGreaterThan(config.maxTransferAmount())) {
            throw DomainException.of("economy.amount_too_large", "max", Long.toString(config.maxTransferAmount().ore()));
        }
        if (request.fromAccountId() == request.toAccountId()) {
            throw new DomainException("economy.same_account");
        }

        if (request.idempotencyKey() != null) {
            Optional<TransferReceipt> existing = findExisting(tx, request);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        long secondId = Math.max(request.fromAccountId(), request.toAccountId());
        List<Account> locked = tx.queryList("SELECT * FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                EconomyService::mapAccount, firstId, secondId);
        if (locked.size() != 2) {
            throw new DomainException("economy.account_not_found");
        }
        Account from = locked.get(0).id() == request.fromAccountId() ? locked.get(0) : locked.get(1);
        Account to = from == locked.get(0) ? locked.get(1) : locked.get(0);

        if (from.frozen() || to.frozen()) {
            throw new DomainException("economy.account_frozen");
        }

        Money fromAfter;
        Money toAfter;
        try {
            fromAfter = from.balance().minus(amount);
            toAfter = to.balance().plus(amount);
        } catch (ArithmeticException e) {
            throw new DomainException("economy.balance_overflow");
        }
        if (fromAfter.isNegative() && !from.allowNegative()) {
            throw DomainException.of("economy.insufficient_funds", "balance", Long.toString(from.balance().ore()));
        }

        Optional<Long> txId = tx.queryOne("""
                        INSERT INTO transactions (idempotency_key, type, from_account_id, to_account_id, amount,
                                                  from_balance_after, to_balance_after, actor_uuid,
                                                  reference_type, reference_id, memo)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id""",
                rs -> rs.getLong(1),
                request.idempotencyKey(), request.type(), from.id(), to.id(), amount.ore(),
                fromAfter.ore(), toAfter.ore(), request.actor(),
                request.referenceType(), request.referenceId(), request.memo());

        if (txId.isEmpty()) {
            // A concurrent transaction with the same key committed while we waited for the locks.
            return findExisting(tx, request)
                    .orElseThrow(() -> new DatabaseException("Idempotency conflict without existing row"));
        }

        tx.update("UPDATE accounts SET balance = ? WHERE id = ?", fromAfter.ore(), from.id());
        tx.update("UPDATE accounts SET balance = ? WHERE id = ?", toAfter.ore(), to.id());
        return new TransferReceipt(txId.get(), amount, fromAfter, toAfter, false);
    }

    private Optional<TransferReceipt> findExisting(Tx tx, TransferRequest request) throws SQLException {
        Optional<ExistingTx> existing = tx.queryOne("""
                        SELECT id, type, from_account_id, to_account_id, amount, from_balance_after, to_balance_after
                        FROM transactions WHERE idempotency_key = ?""",
                rs -> new ExistingTx(rs.getLong("id"), TransactionType.valueOf(rs.getString("type")),
                        rs.getLong("from_account_id"), rs.getLong("to_account_id"), rs.getLong("amount"),
                        rs.getLong("from_balance_after"), rs.getLong("to_balance_after")),
                request.idempotencyKey());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ExistingTx e = existing.get();
        if (e.from != request.fromAccountId() || e.to != request.toAccountId()
                || e.amount != request.amount().ore() || e.type != request.type()) {
            // Same key, different operation: never silently accept.
            throw new DomainException("economy.idempotency_conflict");
        }
        return Optional.of(new TransferReceipt(e.id, Money.ofOre(e.amount), Money.ofOre(e.fromAfter), Money.ofOre(e.toAfter), true));
    }

    private record ExistingTx(long id, TransactionType type, long from, long to, long amount, long fromAfter, long toAfter) {
    }

    /** Creates money from the mint into the target account. Every call must have a documented design reason. */
    public TransferReceipt mint(Tx tx, long toAccountId, Money amount, TransactionType type, String idempotencyKey, UUID actor)
            throws SQLException {
        Account mint = systemAccount(tx, AccountOwner.MINT);
        return transfer(tx, new TransferRequest(mint.id(), toAccountId, amount, type, idempotencyKey, actor, null, null, null));
    }

    /** Destroys money by moving it into the sink. */
    public TransferReceipt burn(Tx tx, long fromAccountId, Money amount, TransactionType type, String idempotencyKey, UUID actor)
            throws SQLException {
        Account sink = systemAccount(tx, AccountOwner.SINK);
        return transfer(tx, new TransferRequest(fromAccountId, sink.id(), amount, type, idempotencyKey, actor, null, null, null));
    }

    /**
     * Player-to-player payment.
     *
     * @param idempotencyKey unique per payment attempt (e.g. a random UUID per command invocation)
     */
    public TransferReceipt pay(UUID from, UUID to, Money amount, String idempotencyKey) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to)) {
            throw new DomainException("economy.pay_self");
        }
        if (amount.isLessThan(config.minPaymentAmount())) {
            throw new DomainException("economy.invalid_amount");
        }
        return database.inTransaction(tx -> {
            Account source = findAccount(tx, AccountOwner.player(from), Account.MAIN)
                    .orElseThrow(() -> new DomainException("economy.account_not_found"));
            Account target = findAccount(tx, AccountOwner.player(to), Account.MAIN)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            return transfer(tx, new TransferRequest(source.id(), target.id(), amount, TransactionType.PLAYER_PAYMENT,
                    idempotencyKey, from, "PLAYER", to.toString(), null));
        });
    }

    /** Admin grant: mints money to a player. */
    public TransferReceipt adminGrant(UUID admin, UUID target, Money amount) {
        return database.inTransaction(tx -> {
            Account account = findAccount(tx, AccountOwner.player(target), Account.MAIN)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            return mint(tx, account.id(), amount, TransactionType.ADMIN_GRANT, null, admin);
        });
    }

    /** Admin removal: burns money from a player. */
    public TransferReceipt adminRemove(UUID admin, UUID target, Money amount) {
        return database.inTransaction(tx -> {
            Account account = findAccount(tx, AccountOwner.player(target), Account.MAIN)
                    .orElseThrow(() -> new DomainException("player.unknown"));
            return burn(tx, account.id(), amount, TransactionType.ADMIN_REMOVAL, null, admin);
        });
    }

    // ------------------------------------------------------------------ reporting

    public List<LedgerEntry> history(AccountOwner owner, int limit) {
        int safeLimit = Math.clamp(limit, 1, 100);
        return database.inTransaction(tx -> {
            Account account = requireAccount(tx, owner);
            return tx.queryList("""
                            SELECT t.id, t.type, t.memo, t.created_at,
                                   CASE WHEN t.to_account_id = ? THEN t.amount ELSE -t.amount END AS signed_amount,
                                   CASE WHEN t.to_account_id = ? THEN t.to_balance_after ELSE t.from_balance_after END AS balance_after,
                                   c.owner_type AS cp_type, c.owner_id AS cp_id
                            FROM transactions t
                            JOIN accounts c ON c.id = CASE WHEN t.to_account_id = ? THEN t.from_account_id ELSE t.to_account_id END
                            WHERE t.from_account_id = ? OR t.to_account_id = ?
                            ORDER BY t.id DESC
                            LIMIT ?""",
                    rs -> new LedgerEntry(rs.getLong("id"), TransactionType.valueOf(rs.getString("type")),
                            Money.ofOre(rs.getLong("signed_amount")), Money.ofOre(rs.getLong("balance_after")),
                            new AccountOwner(AccountOwner.OwnerType.valueOf(rs.getString("cp_type")), rs.getString("cp_id")),
                            rs.getString("memo"), Tx.instant(rs, "created_at")),
                    account.id(), account.id(), account.id(), account.id(), account.id(), safeLimit);
        });
    }

    /** Money currently held by non-system accounts (equals money minted minus money sunk). */
    public Money moneySupply() {
        return database.inTransaction(tx -> Money.ofOre(tx.queryLong(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE owner_type <> 'SYSTEM'")));
    }

    public record LedgerAudit(long totalBalanceOre, List<Long> mismatchedAccountIds) {
        public boolean healthy() {
            return totalBalanceOre == 0 && mismatchedAccountIds.isEmpty();
        }
    }

    /** Verifies conservation of money and that every balance equals the sum of its ledger rows. */
    public LedgerAudit audit() {
        return database.inTransaction(tx -> {
            long total = tx.queryLong("SELECT COALESCE(SUM(balance), 0) FROM accounts");
            List<Long> mismatched = tx.queryList("""
                    SELECT a.id FROM accounts a
                    LEFT JOIN (SELECT to_account_id AS id, SUM(amount) AS s FROM transactions GROUP BY to_account_id) i ON i.id = a.id
                    LEFT JOIN (SELECT from_account_id AS id, SUM(amount) AS s FROM transactions GROUP BY from_account_id) o ON o.id = a.id
                    WHERE a.balance <> COALESCE(i.s, 0) - COALESCE(o.s, 0)
                    ORDER BY a.id""", rs -> rs.getLong(1));
            return new LedgerAudit(total, mismatched);
        });
    }

    static Account mapAccount(ResultSet rs) throws SQLException {
        return new Account(
                rs.getLong("id"),
                new AccountOwner(AccountOwner.OwnerType.valueOf(rs.getString("owner_type")), rs.getString("owner_id")),
                rs.getString("purpose"),
                Money.ofOre(rs.getLong("balance")),
                rs.getBoolean("allow_negative"),
                rs.getBoolean("frozen"));
    }
}
