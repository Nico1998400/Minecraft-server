package se.nordia.swedencore.database;

import org.junit.jupiter.api.Test;
import se.nordia.swedencore.testing.TestDatabase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRunnerTest {

    @Test
    void appliesAllMigrationsOnceAndIsIdempotent() {
        try (Database db = TestDatabase.empty()) {
            MigrationRunner runner = new MigrationRunner(db, TestDatabase.logger(), getClass().getClassLoader());
            int total = runner.loadMigrations().size();
            assertThat(total).isGreaterThan(0);
            assertThat(runner.migrate()).isEqualTo(total);
            assertThat(runner.migrate()).isZero();
            long recorded = db.inTransaction(tx -> tx.queryLong("SELECT count(*) FROM schema_migrations"));
            assertThat(recorded).isEqualTo(total);
        }
    }

    @Test
    void migrationsAreContiguousAndWellNamed() {
        try (Database db = TestDatabase.empty()) {
            List<MigrationRunner.Migration> migrations =
                    new MigrationRunner(db, TestDatabase.logger(), getClass().getClassLoader()).loadMigrations();
            for (int i = 0; i < migrations.size(); i++) {
                assertThat(migrations.get(i).version()).isEqualTo(i + 1);
            }
        }
    }

    @Test
    void refusesEditedMigration() {
        var local = List.of(new MigrationRunner.Migration(1, "init", "SELECT 1", MigrationRunner.checksum("SELECT 1")));
        var applied = Map.of(1, new MigrationRunner.AppliedMigration(1, "init", MigrationRunner.checksum("SELECT 2")));
        assertThatThrownBy(() -> MigrationRunner.validate(local, applied))
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    void refusesUnknownAppliedMigration() {
        var local = List.of(new MigrationRunner.Migration(1, "init", "SELECT 1", MigrationRunner.checksum("SELECT 1")));
        var applied = Map.of(
                1, new MigrationRunner.AppliedMigration(1, "init", MigrationRunner.checksum("SELECT 1")),
                2, new MigrationRunner.AppliedMigration(2, "future", "abc"));
        assertThatThrownBy(() -> MigrationRunner.validate(local, applied))
                .isInstanceOf(DatabaseException.class)
                .hasMessageContaining("does not know");
    }

    @Test
    void checksumIgnoresLineEndings() {
        assertThat(MigrationRunner.checksum("a\r\nb")).isEqualTo(MigrationRunner.checksum("a\nb"));
    }
}
