package se.nordia.swedencore.core;

import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.player.PlayerService;
import se.nordia.swedencore.skills.SkillService;

import java.time.Clock;
import java.util.logging.Logger;

/**
 * Composition root for all domain services. Pure Java: constructed identically by the plugin and by tests.
 *
 * <p>Services are created once and are thread-safe. There is deliberately no dependency-injection framework;
 * the wiring below is the single place to see how modules depend on each other.
 */
public final class NordiaCore {

    private final CoreConfig config;
    private final Database database;
    private final Clock clock;
    private final Logger logger;

    private final EconomyService economy;
    private final PlayerService players;
    private final SkillService skills;

    public NordiaCore(CoreConfig config, Database database, Clock clock, Logger logger) {
        this.config = config;
        this.database = database;
        this.clock = clock;
        this.logger = logger;

        this.economy = new EconomyService(database, config.economy());
        this.players = new PlayerService(database, economy);
        this.skills = new SkillService(database, config.skills());
    }

    public CoreConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public Clock clock() {
        return clock;
    }

    public Logger logger() {
        return logger;
    }

    public EconomyService economy() {
        return economy;
    }

    public PlayerService players() {
        return players;
    }

    public SkillService skills() {
        return skills;
    }
}
