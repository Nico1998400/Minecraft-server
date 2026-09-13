package se.nordia.swedencore.database;

import java.sql.SQLException;

/**
 * Work executed inside a database transaction. May be executed more than once when the transaction is retried
 * after a serialization failure or deadlock, so it must not have side effects outside the database.
 */
@FunctionalInterface
public interface TxWork<T> {
    T execute(Tx tx) throws SQLException;
}
