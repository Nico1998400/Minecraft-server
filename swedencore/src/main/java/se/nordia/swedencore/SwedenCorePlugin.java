package se.nordia.swedencore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import se.nordia.swedencore.core.CoreConfig;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.MigrationRunner;
import se.nordia.swedencore.localization.LocalizationService;
import se.nordia.swedencore.paper.PaperConfigLoader;
import se.nordia.swedencore.paper.command.AdminCommands;
import se.nordia.swedencore.paper.command.CommandServices;
import se.nordia.swedencore.paper.command.EconomyCommands;
import se.nordia.swedencore.paper.command.LanguageCommand;
import se.nordia.swedencore.paper.listener.ConnectionListener;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSessions;
import se.nordia.swedencore.paper.text.Messages;

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

        CommandServices services = new CommandServices(core, messages, tasks, sessions, getPluginMeta().getVersion());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            new EconomyCommands(services).register(event.registrar());
            new LanguageCommand(services).register(event.registrar());
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
}
