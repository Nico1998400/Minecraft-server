package se.nordia.swedencore.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import se.nordia.swedencore.database.Database;

/** Base class for tests that need a fresh, migrated PostgreSQL database per test method. */
public abstract class DatabaseTest {

    protected Database database;

    @BeforeEach
    void openDatabase() {
        database = TestDatabase.fresh();
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }
}
