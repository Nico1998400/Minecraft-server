package se.nordia.swedencore.testing;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.MigrationRunner;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Real PostgreSQL for tests, no Docker required.
 *
 * <p>One embedded server per test JVM. Migrations run once into a template database; each test gets a fresh copy via
 * {@code CREATE DATABASE ... TEMPLATE}, which is fast and fully isolated.
 */
public final class TestDatabase {

    private static final Logger LOGGER = Logger.getLogger("SwedenCoreTest");
    private static final String TEMPLATE = "nordia_template";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static EmbeddedPostgres postgres;

    private TestDatabase() {
    }

    private static synchronized EmbeddedPostgres server() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }));
            execute("CREATE DATABASE " + TEMPLATE);
            try (Database template = new Database(pool(TEMPLATE, 2), LOGGER)) {
                new MigrationRunner(template, LOGGER, TestDatabase.class.getClassLoader()).migrate();
            }
        }
        return postgres;
    }

    /** Creates an isolated, fully migrated database. Close it after the test. */
    public static Database fresh() {
        server();
        String name = "test_" + COUNTER.incrementAndGet();
        execute("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
        return new Database(pool(name, 16), LOGGER);
    }

    /** Creates an empty database with no migrations applied. */
    public static Database empty() {
        server();
        String name = "empty_" + COUNTER.incrementAndGet();
        execute("CREATE DATABASE " + name);
        return new Database(pool(name, 4), LOGGER);
    }

    private static HikariDataSource pool(String databaseName, int size) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(postgres.getDatabase("postgres", databaseName));
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(0);
        config.setPoolName("test-" + databaseName);
        return new HikariDataSource(config);
    }

    private static void execute(String sql) {
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed: " + sql, e);
        }
    }

    public static Logger logger() {
        return LOGGER;
    }
}
