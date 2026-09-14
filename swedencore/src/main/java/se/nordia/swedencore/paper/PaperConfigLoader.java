package se.nordia.swedencore.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import se.nordia.swedencore.companies.CompanyConfig;
import se.nordia.swedencore.contracts.ContractConfig;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.DatabaseConfig;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.orders.BuyOrderService;
import se.nordia.swedencore.production.ProductionService;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.settlements.Settlement;
import se.nordia.swedencore.settlements.SettlementConfig;
import se.nordia.swedencore.shops.ShopService;
import se.nordia.swedencore.skills.LevelCurve;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillsConfig;

import java.time.Duration;
import java.util.EnumMap;
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

    public static CoreConfig load(FileConfiguration file, FileConfiguration productionFile, Logger logger) {
        return load(file, productionFile, System.getenv()::get, logger);
    }

    static ProductionService.Config production(FileConfiguration file, Logger logger) {
        ProductionService.Config defaults = ProductionService.Config.defaults();
        ConfigurationSection recipesSection = file.getConfigurationSection("recipes");
        Map<String, ProductionService.Recipe> recipes = new java.util.HashMap<>();
        if (recipesSection == null) {
            recipes.putAll(defaults.recipes());
        } else {
            for (String id : recipesSection.getKeys(false)) {
                ConfigurationSection r = recipesSection.getConfigurationSection(id);
                if (r == null) {
                    continue;
                }
                try {
                    recipes.put(id.toLowerCase(java.util.Locale.ROOT), new ProductionService.Recipe(id.toLowerCase(java.util.Locale.ROOT),
                            materials(r.getConfigurationSection("inputs")), materials(r.getConfigurationSection("outputs")),
                            r.getInt("seconds", 60), r.getInt("engineering-level", 1), r.getLong("xp-per-batch", 10),
                            se.nordia.swedencore.properties.Property.Type.valueOf(r.getString("facility", "FACTORY").toUpperCase(java.util.Locale.ROOT))));
                } catch (IllegalArgumentException e) {
                    logger.warning("production.yml: invalid recipe " + id + ": " + e.getMessage());
                }
            }
        }
        int runsPerFacility = file.getInt("runs-per-facility", file.getInt("runs-per-factory", defaults.runsPerFactory()));
        return new ProductionService.Config(recipes, runsPerFacility, file.getInt("max-batches", defaults.maxBatches()));
    }

    private static Map<String, Integer> materials(ConfigurationSection section) {
        Map<String, Integer> result = new java.util.HashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
                if (material == null || !material.isItem()) {
                    throw new IllegalArgumentException("unknown material " + key);
                }
                result.put(material.name(), Math.max(1, section.getInt(key)));
            }
        }
        return result;
    }

    static CoreConfig load(FileConfiguration file, FileConfiguration productionFile, Function<String, String> env, Logger logger) {
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

        return new CoreConfig(database, economy, skills(section(file, "skills")), companies(section(file, "companies")),
                contracts(section(file, "contracts")), properties(section(file, "properties")), shops(section(file, "shops")),
                settlements(section(file, "settlements")), orders(section(file, "buy-orders")), production(productionFile, logger), loans(section(file, "loans")), leases(section(file, "renting")), storage(section(file, "storage")), defaultLocale,
                db.getBoolean("shutdown-server-on-failure", true));
    }

    static se.nordia.swedencore.finance.LoanService.Config loans(ConfigurationSection s) {
        var d = se.nordia.swedencore.finance.LoanService.Config.defaults();
        return new se.nordia.swedencore.finance.LoanService.Config(
                s.getInt("max-installments", d.maxInstallments()), s.getInt("min-interval-hours", d.minIntervalHours()),
                s.getInt("max-interval-hours", d.maxIntervalHours()), s.getInt("max-interest-percent", d.maxInterestPercent()),
                s.getInt("offer-ttl-hours", d.offerTtlHours()), s.getInt("grace-hours", d.graceHours()),
                s.getInt("default-after-overdue-installments", d.defaultAfterOverdue()),
                s.getInt("reputation.late-installment", d.lateReputation()), s.getInt("reputation.default", d.defaultReputation()),
                s.getInt("reputation.repaid", d.repaidReputation()), s.getInt("max-open-offers", d.maxOpenOffers()));
    }

    static se.nordia.swedencore.properties.LeaseService.Config leases(ConfigurationSection s) {
        var d = se.nordia.swedencore.properties.LeaseService.Config.defaults();
        return new se.nordia.swedencore.properties.LeaseService.Config(s.getInt("min-period-hours", d.minPeriodHours()),
                s.getInt("max-period-hours", d.maxPeriodHours()), s.getInt("grace-hours", d.graceHours()),
                s.getInt("eviction-reputation", d.evictionReputation()));
    }

    static se.nordia.swedencore.inventory.ItemStashService.Capacity storage(ConfigurationSection s) {
        var d = se.nordia.swedencore.inventory.ItemStashService.Capacity.defaults();
        return new se.nordia.swedencore.inventory.ItemStashService.Capacity(s.getInt("player-stacks", d.playerBase()),
                s.getInt("company-stacks", d.companyBase()), s.getInt("stacks-per-warehouse", d.perWarehouse()));
    }

    static BuyOrderService.Config orders(ConfigurationSection s) {
        BuyOrderService.Config d = BuyOrderService.Config.defaults();
        return new BuyOrderService.Config(s.getInt("fee-percent", d.feePercent()),
                s.getInt("max-active-per-issuer", d.maxActivePerIssuer()), s.getInt("max-duration-hours", d.maxDurationHours()));
    }

    static SettlementConfig settlements(ConfigurationSection s) {
        SettlementConfig d = SettlementConfig.defaults();
        Map<Settlement.Tier, SettlementConfig.Requirements> tiers = new EnumMap<>(Settlement.Tier.class);
        for (Settlement.Tier tier : Settlement.Tier.values()) {
            SettlementConfig.Requirements def = d.requirements(tier);
            String p = "tiers." + tier.name() + ".";
            tiers.put(tier, new SettlementConfig.Requirements(
                    s.getInt(p + "radius", def.radius()),
                    s.getInt(p + "min-members", def.minMembers()),
                    money(s.getString(p + "min-treasury", Long.toString(def.minTreasury().ore() / Money.ORE_PER_SEK)), true),
                    s.getInt(p + "min-age-days", def.minAgeDays()),
                    s.getInt(p + "min-leader-reputation", def.minLeaderReputation()),
                    money(s.getString(p + "cost", Long.toString(def.cost().ore() / Money.ORE_PER_SEK)), true)));
        }
        return new SettlementConfig(tiers, s.getInt("spacing-buffer", d.spacingBuffer()), s.getInt("max-invites", d.maxInvites()));
    }

    static ShopService.Config shops(ConfigurationSection s) {
        ShopService.Config d = ShopService.Config.defaults();
        return new ShopService.Config(s.getInt("max-listings-per-shop", d.maxListingsPerShop()),
                s.getInt("max-bundles-per-purchase", d.maxBundlesPerPurchase()));
    }

    static PropertyService.Config properties(ConfigurationSection s) {
        PropertyService.Config d = PropertyService.Config.defaults();
        return new PropertyService.Config(
                s.getInt("max-owned-per-player", d.maxOwnedPerPlayer()),
                s.getLong("max-volume", d.maxVolume()),
                s.getInt("max-trusted", d.maxTrusted()));
    }

    static ContractConfig contracts(ConfigurationSection s) {
        ContractConfig d = ContractConfig.defaults();
        return new ContractConfig(
                s.getInt("fee-percent", d.feePercent()),
                s.getInt("max-active-per-issuer", d.maxActivePerIssuer()),
                s.getInt("max-active-per-contractor", d.maxActivePerContractor()),
                s.getInt("max-duration-hours", d.maxDurationHours()),
                s.getLong("sek-per-xp", d.sekPerXp()),
                s.getLong("max-xp-per-contract", d.maxXpPerContract()),
                money(s.getString("min-reward-for-reputation", "500"), true),
                s.getInt("reputation.contractor-completed", d.contractorCompletionReputation()),
                s.getInt("reputation.issuer-completed", d.issuerCompletionReputation()),
                s.getInt("reputation.abandoned", d.abandonReputation()),
                s.getInt("reputation.expired", d.expiryReputation()));
    }

    static CompanyConfig companies(ConfigurationSection section) {
        CompanyConfig defaults = CompanyConfig.defaults();
        return new CompanyConfig(
                money(section.getString("registration-fee", "5000"), true),
                section.getInt("max-owned-companies", defaults.maxOwnedCompanies()),
                section.getInt("max-open-positions", defaults.maxOpenPositions()),
                money(section.getString("max-salary-per-hour", "100000"), false),
                section.getInt("max-pending-applications", defaults.maxPendingApplications()),
                section.getInt("payroll-interval-minutes", defaults.payrollIntervalMinutes()),
                section.getInt("wage-default-reputation", defaults.wageDefaultReputation()));
    }

    static SkillsConfig skills(ConfigurationSection section) {
        SkillsConfig defaults = SkillsConfig.defaults();
        LevelCurve curve = new LevelCurve(
                section.getLong("level-curve.base", 50),
                section.getDouble("level-curve.exponent", 1.5),
                section.getInt("max-level", defaults.curve().maxLevel()));
        Map<Skill, Long> caps = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            caps.put(skill, section.getLong("hourly-soft-cap." + skill.name(), defaults.softCap(skill)));
        }
        return new SkillsConfig(curve, caps,
                section.getInt("soft-cap-percent", defaults.softCapPercent()),
                section.getInt("employment-bonus-percent", defaults.employmentBonusPercent()),
                Duration.ofSeconds(section.getLong("afk-threshold-seconds", defaults.afkThreshold().toSeconds())),
                Duration.ofSeconds(Math.max(5, section.getLong("flush-interval-seconds", defaults.flushInterval().toSeconds()))),
                Duration.ofSeconds(section.getLong("building-maturity-seconds", defaults.buildingMaturity().toSeconds())));
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
