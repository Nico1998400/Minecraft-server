package se.nordia.swedencore.core;

import se.nordia.swedencore.cities.CityService;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.events.DomainEvents;
import se.nordia.swedencore.properties.PropertyService;
import se.nordia.swedencore.settlements.SettlementService;
import se.nordia.swedencore.shops.ShopService;
import se.nordia.swedencore.orders.BuyOrderService;
import se.nordia.swedencore.trade.TradeService;
import se.nordia.swedencore.contracts.ContractService;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.jobs.JobService;
import se.nordia.swedencore.market.MarketService;
import se.nordia.swedencore.jobs.PayrollService;
import se.nordia.swedencore.player.PlayerService;
import se.nordia.swedencore.player.ProfileService;
import se.nordia.swedencore.production.ProductionService;
import se.nordia.swedencore.reputation.ReputationService;
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
    private final ReputationService reputation;
    private final PayrollService payroll;
    private final CompanyService companies;
    private final JobService jobs;
    private final ItemStashService stash;
    private final ContractService contracts;
    private final DomainEvents events;
    private final CityService cities;
    private final PropertyService properties;
    private final ShopService shops;
    private final SettlementService settlements;
    private final TradeService trades;
    private final BuyOrderService orders;
    private final ProfileService profiles;
    private final ProductionService production;
    private final MarketService market;

    public NordiaCore(CoreConfig config, Database database, Clock clock, Logger logger) {
        this.config = config;
        this.database = database;
        this.clock = clock;
        this.logger = logger;

        this.events = new DomainEvents(logger);
        this.economy = new EconomyService(database, config.economy());
        this.players = new PlayerService(database, economy);
        this.skills = new SkillService(database, config.skills());
        this.reputation = new ReputationService(database);
        this.payroll = new PayrollService(database, economy, reputation, config.companies(), clock);
        this.companies = new CompanyService(database, economy, payroll, config.companies(), events);
        this.jobs = new JobService(database, companies, skills);
        this.stash = new ItemStashService(database, companies);
        this.contracts = new ContractService(database, economy, companies, skills, reputation, stash, config.contracts(), clock);
        this.cities = new CityService(database, economy);
        this.properties = new PropertyService(database, economy, companies, cities, events, config.properties());
        this.shops = new ShopService(database, economy, properties, events, config.shops(), clock);
        this.settlements = new SettlementService(database, economy, cities, events, config.settlements(), clock);
        this.trades = new TradeService(database, economy);
        this.orders = new BuyOrderService(database, economy, companies, stash, config.orders(), clock);
        this.profiles = new ProfileService(database, economy, skills);
        this.production = new ProductionService(database, companies, stash, skills, config.production(), clock);
        this.market = new MarketService(database, clock, java.time.Duration.ofDays(7));
    }

    public MarketService market() {
        return market;
    }

    public ProductionService production() {
        return production;
    }

    public ProfileService profiles() {
        return profiles;
    }

    public BuyOrderService orders() {
        return orders;
    }

    public TradeService trades() {
        return trades;
    }

    public SettlementService settlements() {
        return settlements;
    }

    public ShopService shops() {
        return shops;
    }

    public DomainEvents events() {
        return events;
    }

    public CityService cities() {
        return cities;
    }

    public PropertyService properties() {
        return properties;
    }

    public ItemStashService stash() {
        return stash;
    }

    public ContractService contracts() {
        return contracts;
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

    public ReputationService reputation() {
        return reputation;
    }

    public PayrollService payroll() {
        return payroll;
    }

    public CompanyService companies() {
        return companies;
    }

    public JobService jobs() {
        return jobs;
    }
}
