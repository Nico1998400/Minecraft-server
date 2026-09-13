package se.nordia.swedencore.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for all database access. Every unit of work runs in a transaction via {@link #inTransaction(TxWork)}.
 *
 * <p>Transactions use READ COMMITTED; correctness of economic operations comes from explicit row locks
 * ({@code SELECT ... FOR UPDATE}) plus database constraints. Serialization failures and deadlocks are retried.
 */
public final class Database implements AutoCloseable {

    /** SQLSTATE codes that indicate a transient conflict where retrying the whole transaction is safe. */
    private static final Set<String> RETRYABLE_STATES = Set.of("40001", "40P01");
    private static final int MAX_ATTEMPTS = 5;

    private final DataSource dataSource;
    private final Logger logger;

    public Database(DataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public static Database connect(DatabaseConfig config, Logger logger) {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setServerNames(new String[]{config.host()});
        pg.setPortNumbers(new int[]{config.port()});
        pg.setDatabaseName(config.database());
        pg.setUser(config.user());
        pg.setPassword(config.password());
        pg.setApplicationName("SwedenCore");
        pg.setSslMode(config.sslMode());

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SwedenCore-DB");
        hikari.setDataSource(pg);
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(Math.min(2, config.poolSize()));
        hikari.setConnectionTimeout(config.connectionTimeoutMillis());
        hikari.setAutoCommit(true);
        return new Database(new HikariDataSource(hikari), logger);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public <T> T inTransaction(TxWork<T> work) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    T result = work.execute(new Tx(connection));
                    connection.commit();
                    return result;
                } catch (Throwable t) {
                    rollbackQuietly(connection);
                    throw t;
                }
                // HikariCP restores auto-commit and isolation when the connection is returned to the pool.
            } catch (SQLException e) {
                if (isRetryable(e) && attempt < MAX_ATTEMPTS) {
                    last = e;
                    logger.log(Level.FINE, "Retrying transaction after transient conflict (attempt " + attempt + ")", e);
                    backoff(attempt);
                    continue;
                }
                throw new DatabaseException("Database transaction failed: " + e.getMessage(), e);
            }
        }
        throw new DatabaseException("Transaction failed after " + MAX_ATTEMPTS + " attempts", last);
    }

    /** Convenience for transactions without a result. */
    public void inTransactionVoid(VoidTxWork work) {
        inTransaction(tx -> {
            work.execute(tx);
            return null;
        });
    }

    @FunctionalInterface
    public interface VoidTxWork {
        void execute(Tx tx) throws SQLException;
    }

    public static boolean isRetryable(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null && RETRYABLE_STATES.contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** SQLSTATE 23505: unique_violation. */
    public static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is more important than a rollback failure.
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(5L * attempt + (long) (Math.random() * 10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Interrupted while retrying transaction", e);
        }
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close data source", e);
            }
        }
    }
}
