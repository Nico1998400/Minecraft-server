package se.nordia.plugins.core;

import se.nordia.plugins.cities.CityService;
import se.nordia.plugins.companies.CompanyFinanceService;
import se.nordia.plugins.companies.CompanyService;
import se.nordia.plugins.events.DomainEvents;
import se.nordia.plugins.finance.BankruptcyService;
import se.nordia.plugins.finance.LoanService;
import se.nordia.plugins.properties.LeaseService;
import se.nordia.plugins.properties.PropertyService;
import se.nordia.plugins.settlements.SettlementService;
import se.nordia.plugins.shops.ShopService;
import se.nordia.plugins.orders.BuyOrderService;
import se.nordia.plugins.trade.TradeService;
import se.nordia.plugins.contracts.ContractService;
import se.nordia.plugins.inventory.ItemStashService;
import se.nordia.plugins.database.Database;
import se.nordia.plugins.economy.EconomyService;
import se.nordia.plugins.jobs.JobService;
import se.nordia.plugins.market.MarketService;
import se.nordia.plugins.jobs.PayrollService;
import se.nordia.plugins.player.PlayerService;
import se.nordia.plugins.player.ProfileService;
import se.nordia.plugins.production.ProductionService;
import se.nordia.plugins.reputation.ReputationService;
import se.nordia.plugins.skills.SkillService;

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
    private final LeaseService leases;
    private final SettlementService settlements;
    private final TradeService trades;
    private final BuyOrderService orders;
    private final ProfileService profiles;
    private final ProductionService production;
    private final MarketService market;
    private final BankruptcyService bankruptcy;
    private final LoanService loans;
    private final CompanyFinanceService companyFinance;
    private final se.nordia.plugins.logistics.TransportService transports;
    private final se.nordia.plugins.shares.ShareService shares;
    private final se.nordia.plugins.essentials.EssentialsService essentials;
    private final se.nordia.plugins.social.SocialService social;
    private final se.nordia.plugins.discord.DiscordLinkService discordLinks;

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
        this.stash = new ItemStashService(database, companies, config.storage());
        this.contracts = new ContractService(database, economy, companies, skills, reputation, stash, config.contracts(), clock);
        this.cities = new CityService(database, economy);
        this.properties = new PropertyService(database, economy, companies, cities, events, config.properties());
        this.leases = new LeaseService(database, economy, companies, properties, reputation, events, config.leases(), clock);
        this.shops = new ShopService(database, economy, properties, events, config.shops(), clock);
        this.settlements = new SettlementService(database, economy, cities, events, config.settlements(), clock);
        this.trades = new TradeService(database, economy);
        this.orders = new BuyOrderService(database, economy, companies, stash, config.orders(), clock);
        this.profiles = new ProfileService(database, economy, skills);
        this.production = new ProductionService(database, companies, stash, skills, config.production(), clock);
        this.market = new MarketService(database, clock, java.time.Duration.ofDays(7));
        this.transports = new se.nordia.plugins.logistics.TransportService(database, economy, companies, stash, skills, reputation,
                se.nordia.plugins.logistics.TransportService.Config.defaults(), clock);
        this.bankruptcy = new BankruptcyService(database, economy, companies, payroll, contracts, orders, properties, leases, transports,
                reputation, events, clock, -15);
        this.loans = new LoanService(database, economy, companies, reputation, bankruptcy, config.loans(), clock);
        this.companyFinance = new CompanyFinanceService(database, economy, companies, payroll);
        this.shares = new se.nordia.plugins.shares.ShareService(database, economy, companies, companyFinance, payroll, config.shares(), clock);
        this.essentials = new se.nordia.plugins.essentials.EssentialsService(database, clock);
        this.social = new se.nordia.plugins.social.SocialService(database, clock);
        this.discordLinks = new se.nordia.plugins.discord.DiscordLinkService(database, clock, java.time.Duration.ofMinutes(10));
    }

    public se.nordia.plugins.social.SocialService social() {
        return social;
    }

    public se.nordia.plugins.discord.DiscordLinkService discordLinks() {
        return discordLinks;
    }

    public se.nordia.plugins.essentials.EssentialsService essentials() {
        return essentials;
    }

    public se.nordia.plugins.shares.ShareService shares() {
        return shares;
    }

    public se.nordia.plugins.logistics.TransportService transports() {
        return transports;
    }

    public CompanyFinanceService companyFinance() {
        return companyFinance;
    }

    public BankruptcyService bankruptcy() {
        return bankruptcy;
    }

    public LoanService loans() {
        return loans;
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

    public LeaseService leases() {
        return leases;
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
