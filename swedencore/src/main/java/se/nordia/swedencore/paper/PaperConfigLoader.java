package se.nordia.swedencore.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.DatabaseConfig;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Maps config.yml (+ environment variable overrides) into the immutable {@link CoreConfig}.
 *
 * <p>Environment variables win over the file so that secrets never need to be written to disk:
 * {@code NORDIA_DB_HOST, NORDIA_DB_PORT, NORDIA_DB_NAME, NORDIA_DB_USER, NORDIA_DB_PASSWORD}.
 */
public final class PaperConfigLoader {

    private PaperConfigLoader() {
    }

    public static CoreConfig load(FileConfiguration file, Logger logger) {
        return load(file, System.getenv()::get, logger);
    }

    static CoreConfig load(FileConfiguration file, Function<String, String> env, Logger logger) {
        ConfigurationSection db = section(file, "database");
        DatabaseConfig database = new DatabaseConfig(
                envOr(env, "NORDIA_DB_HOST", db.getString("host", "localhost")),
                Integer.parseInt(envOr(env, "NORDIA_DB_PORT", Integer.toString(db.getInt("port", 5432)))),
                envOr(env, "NORDIA_DB_NAME", db.getString("name", "nordia")),
                envOr(env, "NORDIA_DB_USER", db.getString("user", "nordia")),
                envOr(env, "NORDIA_DB_PASSWORD", db.getString("password", "")),
                db.getString("ssl-mode", "disable"),
                db.getInt("pool-size", 10),
                db.getLong("connection-timeout-ms", 5000));

        ConfigurationSection eco = section(file, "economy");
        EconomyConfig economy = new EconomyConfig(
                money(eco.getString("starter-grant", "1000"), true),
                money(eco.getString("max-transfer", "100000000"), false),
                money(eco.getString("min-payment", "0.01"), false));

        String localeTag = file.getString("language.default", "sv_SE");
        SupportedLocale defaultLocale = SupportedLocale.parse(localeTag).orElseGet(() -> {
            logger.warning("Unknown language.default '" + localeTag + "', using sv_SE");
            return SupportedLocale.SV_SE;
        });

        return new CoreConfig(database, economy, defaultLocale, db.getBoolean("shutdown-server-on-failure", true));
    }

    private static ConfigurationSection section(FileConfiguration file, String path) {
        ConfigurationSection section = file.getConfigurationSection(path);
        return section != null ? section : file.createSection(path, Map.of());
    }

    private static String envOr(Function<String, String> env, String name, String fallback) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static Money money(String value, boolean allowZero) {
        Objects.requireNonNull(value);
        if (allowZero && value.trim().matches("0+([.,]0+)?")) {
            return Money.ZERO;
        }
        try {
            return Money.parsePositive(value);
        } catch (DomainException e) {
            throw new IllegalArgumentException("Invalid money value in config: " + value);
        }
    }
}
