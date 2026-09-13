package se.nordia.swedencore.core;

import se.nordia.swedencore.database.DatabaseConfig;
import se.nordia.swedencore.economy.EconomyConfig;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.skills.SkillsConfig;

/** Complete, validated plugin configuration. Built by the Paper layer from config.yml and environment variables. */
public record CoreConfig(
        DatabaseConfig database,
        EconomyConfig economy,
        SkillsConfig skills,
        SupportedLocale defaultLocale,
        boolean shutdownOnDatabaseFailure
) {
}
