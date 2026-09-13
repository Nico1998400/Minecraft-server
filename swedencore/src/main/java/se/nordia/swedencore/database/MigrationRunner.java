package se.nordia.swedencore.database;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Minimal, strict SQL migration runner.
 *
 * <p>Why not Flyway: Flyway must be shaded and relocated inside a Paper plugin (ServiceLoader plugins break under
 * relocation), gates newer PostgreSQL versions behind releases, and brings far more than we need. Our needs are:
 * ordered SQL files, one transaction per migration, checksum verification, and a cluster-wide lock.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>Files are named {@code V<version>__<description>.sql} under {@code db/migration/}.</li>
 *   <li>An applied migration whose checksum changed aborts startup (committed migrations are immutable).</li>
 *   <li>A database containing migrations unknown to this build aborts startup (prevents old plugin on new schema).</li>
 *   <li>A PostgreSQL advisory lock prevents two servers migrating concurrently.</li>
 * </ul>
 */
public final class MigrationRunner {

    static final String LOCATION = "db/migration";
    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__([A-Za-z0-9_]+)\\.sql");
    private static final long ADVISORY_LOCK_KEY = 0x4E4F52444941L; // "NORDIA"

    public record Migration(int version, String description, String sql, String checksum) {
    }

    public record AppliedMigration(int version, String description, String checksum) {
    }

    private final Database database;
    private final Logger logger;
    private final ClassLoader classLoader;

    public MigrationRunner(Database database, Logger logger, ClassLoader classLoader) {
        this.database = database;
        this.logger = logger;
        this.classLoader = classLoader;
    }

    /** Applies all pending migrations. Returns the number of migrations applied. */
    public int migrate() {
        List<Migration> migrations = loadMigrations();
        try (Connection lockConnection = database.dataSource().getConnection()) {
            try (Statement st = lockConnection.createStatement()) {
                st.execute("SELECT pg_advisory_lock(" + ADVISORY_LOCK_KEY + ")");
            }
            try {
                ensureHistoryTable();
                Map<Integer, AppliedMigration> applied = loadApplied();
                validate(migrations, applied);
                int count = 0;
                for (Migration migration : migrations) {
                    if (!applied.containsKey(migration.version())) {
                        apply(migration);
                        count++;
                    }
                }
                logger.info("Database schema up to date (" + migrations.size() + " migrations, " + count + " applied now)");
                return count;
            } finally {
                try (Statement st = lockConnection.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK_KEY + ")");
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Migration failed: " + e.getMessage(), e);
        }
    }

    static void validate(List<Migration> migrations, Map<Integer, AppliedMigration> applied) {
        Map<Integer, Migration> known = migrations.stream()
                .collect(Collectors.toMap(Migration::version, Function.identity()));
        for (AppliedMigration a : applied.values()) {
            Migration local = known.get(a.version());
            if (local == null) {
                throw new DatabaseException("Database contains migration V" + a.version() + " (" + a.description()
                        + ") which this plugin build does not know. Refusing to start with an older plugin version.");
            }
            if (!local.checksum().equals(a.checksum())) {
                throw new DatabaseException("Checksum mismatch for applied migration V" + a.version()
                        + ". Committed migrations must never be edited; create a new migration instead.");
            }
        }
    }

    private void ensureHistoryTable() {
        database.inTransactionVoid(tx -> tx.update("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version      INT PRIMARY KEY,
                    description  TEXT        NOT NULL,
                    checksum     TEXT        NOT NULL,
                    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                    execution_ms BIGINT      NOT NULL
                )"""));
    }

    private Map<Integer, AppliedMigration> loadApplied() {
        return database.inTransaction(tx -> tx.queryList(
                        "SELECT version, description, checksum FROM schema_migrations ORDER BY version",
                        rs -> new AppliedMigration(rs.getInt("version"), rs.getString("description"), rs.getString("checksum"))))
                .stream()
                .collect(Collectors.toMap(AppliedMigration::version, Function.identity()));
    }

    private void apply(Migration migration) {
        logger.info("Applying migration V" + migration.version() + " " + migration.description());
        long start = System.nanoTime();
        database.inTransactionVoid(tx -> {
            try (Statement st = tx.connection().createStatement()) {
                st.execute(migration.sql());
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            tx.update("INSERT INTO schema_migrations (version, description, checksum, execution_ms) VALUES (?, ?, ?, ?)",
                    migration.version(), migration.description(), migration.checksum(), ms);
        });
    }

    /** Discovers migration files on the classpath, both from a directory (tests/IDE) and from the plugin jar. */
    public List<Migration> loadMigrations() {
        List<String> names = discoverFileNames();
        List<Migration> migrations = new ArrayList<>();
        for (String name : names) {
            Matcher m = FILE_NAME.matcher(name);
            if (!m.matches()) {
                throw new DatabaseException("Invalid migration file name: " + name + " (expected V<n>__<description>.sql)");
            }
            String sql = readResource(LOCATION + "/" + name);
            migrations.add(new Migration(Integer.parseInt(m.group(1)), m.group(2), sql, checksum(sql)));
        }
        migrations.sort(Comparator.comparingInt(Migration::version));
        for (int i = 0; i < migrations.size(); i++) {
            if (migrations.get(i).version() != i + 1) {
                throw new DatabaseException("Migration versions must be contiguous starting at 1; found V"
                        + migrations.get(i).version() + " at position " + (i + 1));
            }
        }
        return migrations;
    }

    private List<String> discoverFileNames() {
        URL url = classLoader.getResource(LOCATION);
        if (url == null) {
            throw new DatabaseException("Migration directory not found on classpath: " + LOCATION);
        }
        try {
            if ("file".equals(url.getProtocol())) {
                try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
                    return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql")).sorted().toList();
                }
            }
            if ("jar".equals(url.getProtocol())) {
                String path = url.getPath();
                String jarPath = path.substring("file:".length(), path.indexOf('!'));
                try (JarFile jar = new JarFile(Path.of(new java.net.URI("file:" + jarPath)).toFile())) {
                    String prefix = LOCATION + "/";
                    return jar.stream()
                            .map(JarEntry::getName)
                            .filter(n -> n.startsWith(prefix) && n.endsWith(".sql") && n.indexOf('/', prefix.length()) < 0)
                            .map(n -> n.substring(prefix.length()))
                            .sorted()
                            .toList();
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new DatabaseException("Failed to list migrations", e);
        }
        throw new DatabaseException("Unsupported classpath protocol for migrations: " + url);
    }

    private String readResource(String path) {
        try (InputStream in = Objects.requireNonNull(classLoader.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException("Failed to read migration " + path, e);
        }
    }

    static String checksum(String sql) {
        String normalized = sql.replace("\r\n", "\n");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
