package se.nordia.swedencore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.MigrationRunner;
import se.nordia.swedencore.localization.LocalizationService;
import se.nordia.swedencore.paper.PaperConfigLoader;
import se.nordia.swedencore.paper.command.AdminCommands;
import se.nordia.swedencore.paper.command.CommandServices;
import se.nordia.swedencore.paper.command.CompanyCommands;
import se.nordia.swedencore.paper.command.ContractCommands;
import se.nordia.swedencore.paper.command.PropertyCommands;
import se.nordia.swedencore.paper.command.SettlementCommands;
import se.nordia.swedencore.paper.command.ShopCommands;
import se.nordia.swedencore.paper.command.OrderCommands;
import se.nordia.swedencore.paper.command.ProfileCommand;
import se.nordia.swedencore.paper.command.TradeCommands;
import se.nordia.swedencore.paper.trade.TradeManager;
import se.nordia.swedencore.paper.shops.ShopIndex;
import se.nordia.swedencore.paper.shops.ShopListener;
import se.nordia.swedencore.paper.command.StashCommands;
import se.nordia.swedencore.paper.properties.ProtectionIndex;
import se.nordia.swedencore.paper.properties.ProtectionListener;
import se.nordia.swedencore.paper.command.EconomyCommands;
import se.nordia.swedencore.paper.command.JobCommands;
import se.nordia.swedencore.paper.command.LanguageCommand;
import se.nordia.swedencore.paper.jobs.WorkSiteListener;
import se.nordia.swedencore.paper.jobs.WorkTracker;
import se.nordia.swedencore.paper.command.SkillCommands;
import se.nordia.swedencore.paper.listener.ConnectionListener;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSessions;
import se.nordia.swedencore.paper.skills.ActivityTracker;
import se.nordia.swedencore.paper.skills.BuildingXpListener;
import se.nordia.swedencore.paper.skills.GatheringListener;
import se.nordia.swedencore.paper.skills.PlacedBlockTracker;
import se.nordia.swedencore.paper.skills.SkillTracker;
import se.nordia.swedencore.paper.skills.XpTables;
import se.nordia.swedencore.paper.text.Messages;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Paper entry point. Wiring only — no game logic lives here.
 */
public final class SwedenCorePlugin extends JavaPlugin {

    private Database database;
    private NordiaCore core;
    private Tasks tasks;
    private LocalizationService localization;
    private final List<Runnable> shutdownHooks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CoreConfig config;
        try {
            config = PaperConfigLoader.load(getConfig(), getLogger());
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Invalid configuration; SwedenCore cannot start", e);
            getServer().shutdown();
            return;
        }

        Path langDir = getDataFolder().toPath().resolve("lang");
        localization = LocalizationService.loadBundled(getLogger(), getClassLoader(), langDir);

        try {
            getLogger().info("Connecting to " + config.database());
            database = Database.connect(config.database(), getLogger());
            new MigrationRunner(database, getLogger(), getClassLoader()).migrate();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Database unavailable; SwedenCore cannot start", e);
            if (database != null) {
                database.close();
                database = null;
            }
            if (config.shutdownOnDatabaseFailure()) {
                getLogger().severe("Shutting down the server (database.shutdown-server-on-failure = true)");
                getServer().shutdown();
            } else {
                getServer().getPluginManager().disablePlugin(this);
            }
            return;
        }

        core = new NordiaCore(config, database, Clock.systemUTC(), getLogger());
        PlayerSessions sessions = new PlayerSessions();
        Messages messages = new Messages(localization, sessions, config.defaultLocale());
        tasks = new Tasks(this, messages, config.database().poolSize());

        List<Consumer<Player>> joinHooks = new ArrayList<>();
        List<Consumer<UUID>> quitHooks = new ArrayList<>();
        ConnectionListener connectionListener = new ConnectionListener(core, sessions, messages, tasks, joinHooks, quitHooks);
        getServer().getPluginManager().registerEvents(connectionListener, this);

        // ---- skills
        saveResourceIfMissing("skills.yml");
        XpTables xpTables = new XpTables(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "skills.yml")), getLogger());
        ActivityTracker activity = new ActivityTracker(config.skills().afkThreshold());
        PlacedBlockTracker placedBlocks = new PlacedBlockTracker(this, xpTables);
        SkillTracker skillTracker = new SkillTracker(core.skills(), activity, messages, tasks, getLogger());
        BuildingXpListener building = new BuildingXpListener(skillTracker, placedBlocks, activity, xpTables,
                config.skills().buildingMaturity().toMillis());
        registerListeners(activity, placedBlocks, new GatheringListener(skillTracker, placedBlocks, xpTables), building);
        joinHooks.add(skillTracker::onJoin);
        quitHooks.add(skillTracker::onQuit);
        long flushTicks = Math.max(100, config.skills().flushInterval().toSeconds() * 20);
        getServer().getScheduler().runTaskTimer(this, skillTracker::flushAll, flushTicks, flushTicks);
        getServer().getScheduler().runTaskTimer(this, building::tick, 200L, 200L);
        shutdownHooks.add(skillTracker::flushAllBlocking);
        shutdownHooks.add(() -> placedBlocks.saveAll(getServer().getWorlds()));

        // ---- employment
        int payrollMinutes = config.companies().payrollIntervalMinutes();
        WorkTracker work = new WorkTracker(core.payroll(), activity, messages, tasks, getLogger(),
                config.skills().employmentBonusPercent(), payrollMinutes);
        skillTracker.addListener(work);
        skillTracker.setBonusProvider(work::bonusPercent);
        quitHooks.add(work::stopDuty);
        getServer().getScheduler().runTaskTimer(this, work::tickMinute, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, work::flushAll, payrollMinutes * 1200L, payrollMinutes * 1200L);

        // ---- properties & protection
        ProtectionIndex protection = new ProtectionIndex(core, tasks);
        registerListeners(new ProtectionListener(protection, messages, getConfig().getBoolean("properties.protect-city-land", true)));
        protection.reloadAll();
        WorkSiteListener workSites = new WorkSiteListener(core, work, protection, messages, tasks);
        registerListeners(workSites);
        getServer().getScheduler().runTaskTimer(this, workSites::flushAll, 400L, 400L);
        shutdownHooks.add(workSites::flushAllBlocking);

        // ---- shops
        ShopIndex shopIndex = new ShopIndex(this, core, tasks);
        registerListeners(shopIndex, new ShopListener(core, shopIndex, protection, messages, tasks));
        shopIndex.queueReload();

        // ---- direct trade
        TradeManager tradeManager = new TradeManager(core, messages, tasks);
        registerListeners(tradeManager);
        getServer().getScheduler().runTaskTimer(this, tradeManager::tick, 20L, 20L);
        shutdownHooks.addFirst(tradeManager::cancelAll);

        // ---- contracts: expire overdue contracts every minute and tell online parties
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async(() -> core.contracts().expireDue())
                .whenComplete((expired, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Contract expiry failed", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> expired.forEach(k -> {
                        for (UUID party : new UUID[]{k.issuerPlayer(), k.contractor()}) {
                            Player online = party == null ? null : getServer().getPlayer(party);
                            if (online != null) {
                                messages.send(online, "contract.expired_notice", "id", k.id(), "title", k.title());
                            }
                        }
                    }));
                }), 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async("expire buy orders", () -> core.orders().expireDue()),
                1300L, 1200L);

        CommandServices services = new CommandServices(core, messages, tasks, sessions, getPluginMeta().getVersion());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            new EconomyCommands(services).register(event.registrar());
            new LanguageCommand(services).register(event.registrar());
            new SkillCommands(services, skillTracker).register(event.registrar());
            new CompanyCommands(services).register(event.registrar());
            new JobCommands(services, work).register(event.registrar());
            new ContractCommands(services).register(event.registrar());
            new StashCommands(services).register(event.registrar());
            new PropertyCommands(services, protection).register(event.registrar());
            new ShopCommands(services, shopIndex, protection).register(event.registrar());
            new SettlementCommands(services).register(event.registrar());
            new TradeCommands(services, tradeManager).register(event.registrar());
            new OrderCommands(services).register(event.registrar());
            new ProfileCommand(services).register(event.registrar());
            new AdminCommands(services, () -> localization.reload(getClassLoader(), langDir)).register(event.registrar());
        });

        for (Player online : getServer().getOnlinePlayers()) {
            connectionListener.refresh(online);
        }
        getLogger().info("SwedenCore enabled — välkommen till NORDIA");
    }

    @Override
    public void onDisable() {
        for (Runnable hook : shutdownHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Shutdown hook failed", e);
            }
        }
        if (tasks != null) {
            tasks.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public NordiaCore core() {
        return core;
    }

    private void registerListeners(Listener... listeners) {
        for (Listener listener : listeners) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }
}
