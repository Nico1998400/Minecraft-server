package se.nordia.swedencore.trade;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.economy.Account;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransactionType;
import se.nordia.swedencore.economy.TransferRequest;

import java.util.UUID;

/**
 * Records a completed direct trade and moves the offered money in one transaction.
 *
 * <p>Items are exchanged by the Paper layer only after this commit succeeded (or {@link #tradeRecorded} confirms it
 * after an ambiguous failure). If either side cannot pay, nothing is recorded and the trade reopens.
 */
public final class TradeService {

    private final Database database;
    private final EconomyService economy;

    public TradeService(Database database, EconomyService economy) {
        this.database = database;
        this.economy = economy;
    }

    public long complete(UUID token, UUID playerA, UUID playerB, Money aToB, Money bToA, String itemsA, String itemsB) {
        if (playerA.equals(playerB)) {
            throw new DomainException("trade.self");
        }
        if (aToB.isNegative() || bToA.isNegative()) {
            throw new DomainException("economy.invalid_amount");
        }
        return database.inTransaction(tx -> {
            if (tx.queryOne("SELECT 1 FROM trades WHERE token = ?", rs -> true, token).isPresent()) {
                throw new DomainException("trade.already_completed");
            }
            Account a = economy.requireAccount(tx, AccountOwner.player(playerA));
            Account b = economy.requireAccount(tx, AccountOwner.player(playerB));
            long id = tx.queryLong("""
                            INSERT INTO trades (token, player_a, player_b, money_a_to_b, money_b_to_a, items_a, items_b)
                            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    token, playerA, playerB, aToB.ore(), bToA.ore(), truncate(itemsA), truncate(itemsB));
            if (aToB.isPositive()) {
                economy.transfer(tx, new TransferRequest(a.id(), b.id(), aToB, TransactionType.TRADE,
                        "trade:" + token + ":ab", playerA, "TRADE", Long.toString(id), null));
            }
            if (bToA.isPositive()) {
                economy.transfer(tx, new TransferRequest(b.id(), a.id(), bToA, TransactionType.TRADE,
                        "trade:" + token + ":ba", playerB, "TRADE", Long.toString(id), null));
            }
            return id;
        });
    }

    public boolean tradeRecorded(UUID token) {
        return database.inTransaction(tx -> tx.queryOne("SELECT 1 FROM trades WHERE token = ?", rs -> true, token)).isPresent();
    }

    private static String truncate(String s) {
        String value = s == null ? "" : s;
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
