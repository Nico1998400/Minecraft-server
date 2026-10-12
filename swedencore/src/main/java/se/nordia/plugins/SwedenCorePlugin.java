package se.nordia.plugins;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import se.nordia.plugins.core.CoreConfig;
import se.nordia.plugins.core.NordiaCore;
import se.nordia.plugins.database.Database;
import se.nordia.plugins.database.MigrationRunner;
import se.nordia.plugins.localization.LocalizationService;
import se.nordia.plugins.paper.PaperConfigLoader;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.command.AdminCommands;
import se.nordia.plugins.paper.command.CommandServices;
import se.nordia.plugins.paper.command.ChatCommands;
import se.nordia.plugins.paper.command.DiscordCommands;
import se.nordia.plugins.paper.command.EssentialsKit;
import se.nordia.plugins.paper.command.InfoCommands;
import se.nordia.plugins.paper.command.InventoryCommands;
import se.nordia.plugins.paper.command.ModerationCommands;
import se.nordia.plugins.paper.command.PlayerStateCommands;
import se.nordia.plugins.paper.command.TeleportExtraCommands;
import se.nordia.plugins.paper.command.WorldCommands;
import se.nordia.plugins.paper.command.CompanyCommands;
import se.nordia.plugins.paper.command.ContractCommands;
import se.nordia.plugins.paper.command.PropertyCommands;
import se.nordia.plugins.paper.command.SettlementCommands;
import se.nordia.plugins.paper.command.ShopCommands;
import se.nordia.plugins.paper.command.LoanCommands;
import se.nordia.plugins.paper.command.MarketCommand;
import se.nordia.plugins.paper.command.OrderCommands;
import se.nordia.plugins.paper.command.ProductionCommands;
import se.nordia.plugins.paper.command.ProfileCommand;
import se.nordia.plugins.paper.inventory.ItemTransfer;
import se.nordia.plugins.paper.command.TradeCommands;
import se.nordia.plugins.paper.command.TransportCommands;
import se.nordia.plugins.paper.trade.TradeManager;
import se.nordia.plugins.paper.shops.ShopIndex;
import se.nordia.plugins.paper.shops.ShopListener;
import se.nordia.plugins.paper.command.StashCommands;
import se.nordia.plugins.paper.properties.ProtectionIndex;
import se.nordia.plugins.paper.properties.ProtectionListener;
import se.nordia.plugins.paper.command.EconomyCommands;
import se.nordia.plugins.paper.command.JobCommands;
import se.nordia.plugins.paper.command.LanguageCommand;
import se.nordia.plugins.paper.jobs.WorkSiteListener;
import se.nordia.plugins.paper.jobs.WorkTracker;
import se.nordia.plugins.paper.command.SkillCommands;
import se.nordia.plugins.paper.listener.ConnectionListener;
import se.nordia.plugins.paper.scheduler.Tasks;
import se.nordia.plugins.paper.session.PlayerSessions;
import se.nordia.plugins.paper.skills.ActivityTracker;
import se.nordia.plugins.paper.skills.BuildingXpListener;
import se.nordia.plugins.paper.skills.GatheringListener;
import se.nordia.plugins.paper.skills.PlacedBlockTracker;
import se.nordia.plugins.paper.skills.SkillTracker;
import se.nordia.plugins.paper.skills.XpTables;
import se.nordia.plugins.paper.text.Messages;

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
    private se.nordia.plugins.paper.guide.GuideNpc guideNpc;
    private volatile se.nordia.plugins.essentials.EssentialsConfig essentialsConfig;
    private se.nordia.plugins.discord.DiscordBot discordBot;
    private se.nordia.plugins.paper.tutorial.TutorialBoards tutorialBoards;
    private final List<Runnable> shutdownHooks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        CoreConfig config;
        try {
            saveResourceIfMissing("production.yml");
            config = PaperConfigLoader.load(getConfig(),
                    YamlConfiguration.loadConfiguration(new File(getDataFolder(), "production.yml")), getLogger());
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

        se.nordia.plugins.paper.world.BuildWorld buildWorld = new se.nordia.plugins.paper.world.BuildWorld(getLogger());
        buildWorld.ensureLoaded();
        getServer().getPluginManager().registerEvents(new se.nordia.plugins.paper.world.BuildWorldListener(), this);

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

        // ---- main menu (/meny)
        se.nordia.plugins.paper.gui.MainMenu mainMenu = new se.nordia.plugins.paper.gui.MainMenu(messages, tasks);
        registerListeners(mainMenu);
se.nordia.plugins.paper.gui.TexturePack texturePack = createTexturePack(messages);
        registerListeners(texturePack);

        // ---- essentials (spawn, homes, warps, tpa, back): settings live in essentials.yml and are re-read by /nordia reload
        saveResourceIfMissing("essentials.yml");
        se.nordia.plugins.paper.essentials.EssentialsState essentialsState = new se.nordia.plugins.paper.essentials.EssentialsState();
        essentialsConfig = PaperConfigLoader.essentials(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "essentials.yml")));
        se.nordia.plugins.paper.essentials.Teleporter teleporter =
                new se.nordia.plugins.paper.essentials.Teleporter(this, messages, () -> essentialsConfig);
        se.nordia.plugins.paper.essentials.TpaManager tpaManager =
                new se.nordia.plugins.paper.essentials.TpaManager(this, messages, teleporter, essentialsState);
        registerListeners(teleporter);
        quitHooks.add(tpaManager::forget);
        shutdownHooks.add(teleporter::shutdown);

        se.nordia.plugins.paper.essentials.EssentialsListener essentialsListener =
                new se.nordia.plugins.paper.essentials.EssentialsListener(this, core, messages, tasks, essentialsState);
        registerListeners(essentialsListener);
        Permissions.registerEssentials(getServer().getPluginManager(), List.of(
                "heal", "feed", "fly", "god", "gms", "gmc", "gma", "gmsp", "speed", "vanish", "suicide", "burn", "ext", "lightning",
                "hat", "more", "repair", "afk", "msg", "r", "msgtoggle", "me", "broadcast", "broadcastworld", "helpop", "ignore",
                "socialspy", "mute", "motd", "rules", "info", "nick", "online", "seen", "whois", "playtime", "ping", "near", "getpos",
                "compass", "depth", "tps", "weather", "time", "ptime", "pweather", "butcher", "remove", "spawnmob", "workbench",
                "anvil", "grindstone", "loom", "cartographytable", "smithingtable", "stonecutter", "disposal", "enderchest", "invsee",
                "item", "skull", "itemname", "itemlore", "tempban", "kickall", "tpahere", "tpacancel", "tpall", "tptoggle", "tphere",
                "tppos", "top", "jump", "tpr", "renamehome", "warpinfo", "discord", "link", "unlink"));

        // ---- Discord: /discord, /link, /unlink. The bot token comes only from the environment (NORDIA_DISCORD_TOKEN).
        saveResourceIfMissing("discord.yml");
        YamlConfiguration discordYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "discord.yml"));
        se.nordia.plugins.discord.DiscordConfig discordConfig = PaperConfigLoader.discord(discordYml, System.getenv()::get);
        if (discordConfig.botCanRun()) {
            discordBot = new se.nordia.plugins.discord.DiscordBot(discordConfig, core.discordLinks(), player ->
                    tasks.sync(() -> {
                        Player online = getServer().getPlayer(player);
                        if (online != null) {
                            messages.send(online, "discord.linked");
                        }
                    }), getLogger());
            discordBot.start();
            shutdownHooks.add(discordBot::stop);
        } else {
            getLogger().info("Discord bot is off (set bot.enabled in discord.yml and NORDIA_DISCORD_TOKEN in .env to turn it on)");
        }

        // ---- tutorial board + /tutorial menu (texts in the language files, sign text in tutorial.yml)
        saveResourceIfMissing("tutorial.yml");
        se.nordia.plugins.paper.tutorial.TutorialMenu tutorialMenu = new se.nordia.plugins.paper.tutorial.TutorialMenu(messages, mainMenu::open);
        tutorialBoards = new se.nordia.plugins.paper.tutorial.TutorialBoards(this, tutorialMenu,
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "tutorial.yml")));
        registerListeners(tutorialMenu, tutorialBoards);
        tutorialBoards.start();
        shutdownHooks.add(tutorialBoards::shutdown);

        // ---- server list message
        if (!text("motd.line1").isEmpty()) {
            registerListeners(new se.nordia.plugins.paper.listener.MotdListener(text("motd.line1"),
                    text("motd.line2"), getLogger()));
        }

        // ---- guide NPC (Brage). The AI key comes only from the environment; without it he points to /meny.
        saveResourceIfMissing("guide.yml");
        YamlConfiguration guideYml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "guide.yml"));
        se.nordia.plugins.guide.GuideConfig guideConfig = PaperConfigLoader.guide(guideYml, System.getenv()::get);
        se.nordia.plugins.guide.GuideService guideService = new se.nordia.plugins.guide.GuideService(guideConfig,
                se.nordia.plugins.guide.AiClient.create(guideConfig), core.clock(), loadGuideKnowledge(), getLogger());
        guideNpc = new se.nordia.plugins.paper.guide.GuideNpc(this, guideService,
                new se.nordia.plugins.guide.GuideContextBuilder(core.economy(), core.skills(), core.players()),
                messages, tasks, guideYml.getString("skin", ""),
                se.nordia.plugins.paper.guide.GuideBehaviour.fromYaml(guideYml));
        registerListeners(guideNpc);
        quitHooks.add(guideNpc::onQuit);
        guideNpc.start();
        shutdownHooks.add(guideNpc::shutdown);
        getLogger().info(guideConfig.aiAvailable() ? "Guide " + guideConfig.name() + " is ready (model " + guideConfig.model() + ")"
                : "Guide " + guideConfig.name() + " has no AI key (set NORDIA_AI_API_KEY in .env); he will only point to /tutorial");

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
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async("expire share offers", () -> core.shares().expireDue()),
                1350L, 1200L);
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async(() -> core.transports().expireDue(ItemTransfer.CODEC))
                .whenComplete((expired, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Transport expiry failed", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> expired.stream().filter(t -> t.carrier() != null).forEach(t -> {
                        Player carrier = getServer().getPlayer(t.carrier());
                        if (carrier != null) {
                            messages.send(carrier, "transport.failed_notice", "id", t.id());
                        }
                    }));
                }), 1400L, 1200L);
        // ---- leases: collect rent every 5 minutes and tell tenants about overdue rent and evictions
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async(() -> core.leases().collectDue())
                .whenComplete((outcomes, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Rent collection failed", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> outcomes.stream()
                            .filter(o -> !o.result().equals("PAID") && o.lease().tenantType() == se.nordia.plugins.properties.Property.OwnerType.PLAYER)
                            .forEach(o -> {
                                Player tenant = getServer().getPlayer(UUID.fromString(o.lease().tenantId()));
                                if (tenant != null) {
                                    messages.send(tenant, "lease.notice." + o.result().toLowerCase(java.util.Locale.ROOT), "name", o.lease().propertyName());
                                }
                            }));
                }), 3000L, 6000L);

        // ---- loans: collect due installments every 5 minutes; announce defaults and bankruptcies
        core.events().subscribe(event -> {
            if (event instanceof se.nordia.plugins.events.DomainEvent.CompanyBankrupt bankrupt) {
                tasks.async(() -> core.companies().find(bankrupt.companyId())).whenComplete((company, error) -> {
                    if (error == null && company.isPresent()) {
                        tasks.sync(() -> getServer().getOnlinePlayers().forEach(p ->
                                messages.send(p, "bankruptcy.broadcast", "name", company.get().name())));
                    }
                });
            }
        });
        getServer().getScheduler().runTaskTimer(this, () -> tasks.async(() -> core.loans().collectDue())
                .whenComplete((results, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Loan collection failed", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> results.stream().filter(r -> r.defaulted()
                                    && r.loan().borrowerType() == se.nordia.plugins.finance.Loan.PartyType.PLAYER)
                            .forEach(r -> {
                                Player borrower = getServer().getPlayer(UUID.fromString(r.loan().borrowerId()));
                                if (borrower != null) {
                                    messages.send(borrower, "loan.defaulted_notice", "id", r.loan().id());
                                }
                            }));
                }), 6000L, 6000L);

        getServer().getScheduler().runTaskTimer(this, () -> tasks.async(() -> core.production().completeDue(ItemTransfer.CODEC))
                .whenComplete((runs, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Production completion failed", Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> runs.forEach(run -> {
                        Player operator = getServer().getPlayer(run.operator());
                        if (operator != null) {
                            messages.send(operator, "production.completed_notice", "recipe", run.recipe(), "batches", run.batches());
                        }
                    }));
                }), 400L, 400L);

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
            new ProductionCommands(services).register(event.registrar());
            new MarketCommand(services).register(event.registrar());
            new LoanCommands(services).register(event.registrar());
            new TransportCommands(services).register(event.registrar());
            new se.nordia.plugins.paper.command.ShareCommands(services).register(event.registrar());
            new se.nordia.plugins.paper.command.SpawnCommands(services, teleporter).register(event.registrar());
            new se.nordia.plugins.paper.command.MenuCommand(services, mainMenu).register(event.registrar());
            new se.nordia.plugins.paper.command.TutorialCommand(services, tutorialMenu, tutorialBoards).register(event.registrar());
            new se.nordia.plugins.paper.command.GuideCommand(services, guideNpc, guideService).register(event.registrar());
            new se.nordia.plugins.paper.command.EssentialsCommands(services, teleporter, tpaManager, getLogger()).register(event.registrar());
            EssentialsKit kit = new EssentialsKit(services, essentialsState, getLogger());
            java.util.function.Supplier<se.nordia.plugins.essentials.EssentialsConfig> essentialsSupplier = () -> essentialsConfig;
            new PlayerStateCommands(kit, essentialsListener).register(event.registrar());
            new ChatCommands(kit, essentialsSupplier).register(event.registrar());
            new InfoCommands(kit, essentialsSupplier).register(event.registrar());
            new WorldCommands(kit).register(event.registrar());
            new InventoryCommands(kit).register(event.registrar());
            new ModerationCommands(kit).register(event.registrar());
            new TeleportExtraCommands(this, kit, teleporter, tpaManager, essentialsSupplier).register(event.registrar());
            new DiscordCommands(kit, discordConfig, () -> discordBot != null && discordBot.running()).register(event.registrar());
            new se.nordia.plugins.paper.command.TexturePackCommand(services, texturePack).register(event.registrar());
            new se.nordia.plugins.paper.command.BuildWorldCommands(services, buildWorld).register(event.registrar());
            new AdminCommands(services, () -> {
                localization.reload(getClassLoader(), langDir);
                essentialsConfig = PaperConfigLoader.essentials(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "essentials.yml")));
                tutorialBoards.reload(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "tutorial.yml")));
                if (guideNpc != null) {
                    guideNpc.reloadBehaviour(se.nordia.plugins.paper.guide.GuideBehaviour.fromYaml(
                            YamlConfiguration.loadConfiguration(new File(getDataFolder(), "guide.yml"))));
                }
            }).register(event.registrar());
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

    /**
     * The server texture pack. Either a download link from the config (url + sha1), or the pack bundled in this jar, which a
     * tiny built-in web server hands out (texture-pack.host) with its SHA-1 computed automatically.
     */
    private se.nordia.plugins.paper.gui.TexturePack createTexturePack(Messages messages) {
        String url = text("texture-pack.url");
        byte[] sha1 = null;
        java.util.UUID id = null;
        try {
            id = UUID.fromString(text("texture-pack.id"));
        } catch (IllegalArgumentException e) {
            getLogger().warning("texture-pack.id is not a valid UUID; the texture pack is off");
        }
        if (id != null && !url.isEmpty()) {
            try {
                sha1 = java.util.HexFormat.of().parseHex(text("texture-pack.sha1"));
                if (sha1.length != 20) {
                    throw new IllegalArgumentException("SHA-1 must be 40 hex characters");
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("texture-pack is off: " + e.getMessage());
                url = "";
                sha1 = null;
            }
        } else if (id != null && getConfig().getBoolean("texture-pack.host.enabled")) {
            String address = text("texture-pack.host.public-address");
            if (address.isEmpty()) {
                getLogger().info("Texture pack is off: set texture-pack.host.public-address in config.yml (the address players use to reach this server)");
            } else {
                try (java.io.InputStream in = getResource("nordia-pack.zip")) {
                    if (in == null) {
                        getLogger().warning("This jar has no bundled texture pack (nordia-pack.zip)");
                    } else {
                        se.nordia.plugins.paper.gui.PackHost host = se.nordia.plugins.paper.gui.PackHost.start(in.readAllBytes(),
                                getConfig().getInt("texture-pack.host.port"), address, getLogger());
                        shutdownHooks.add(host::close);
                        url = host.url();
                        sha1 = host.sha1();
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    getLogger().log(Level.WARNING, "Could not start the texture pack web server (is the port free?): " + e.getMessage());
                }
            }
        }
        return new se.nordia.plugins.paper.gui.TexturePack(this, messages, url, sha1, id,
                getConfig().getBoolean("texture-pack.ask-on-join"), getConfig().getBoolean("texture-pack.required"));
    }

/** A text setting from config.yml. Reads the value the way that also uses the defaults packed in the jar, so settings added in a newer version work on an older config.yml. */    private String text(String path) {        String value = getConfig().getString(path);        return value == null ? "" : value.trim();    }
    private String loadGuideKnowledge() {
        try (java.io.InputStream in = getResource("guide/knowledge.md")) {
            return in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            getLogger().log(Level.WARNING, "Could not read the guide knowledge file", e);
            return "";
        }
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }
}
