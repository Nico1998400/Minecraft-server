package se.nordia.swedencore.paper.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.paper.session.PlayerSessions;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.player.PlayerService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Registers players before they enter the world.
 *
 * <p>Registration happens in {@link AsyncPlayerPreLoginEvent}, which Paper runs off the main thread, so blocking
 * database access is allowed there. If the database is unavailable the login is refused: letting players into a
 * world whose economy cannot record anything would create unrecoverable inconsistencies.
 */
public final class ConnectionListener implements Listener {

    private final NordiaCore core;
    private final PlayerSessions sessions;
    private final Messages messages;
    private final Tasks tasks;
    private final List<Consumer<Player>> joinHooks;
    private final List<Consumer<UUID>> quitHooks;
    private final ConcurrentHashMap<UUID, PlayerService.Registration> pending = new ConcurrentHashMap<>();

    public ConnectionListener(NordiaCore core, PlayerSessions sessions, Messages messages, Tasks tasks,
                              List<Consumer<Player>> joinHooks, List<Consumer<UUID>> quitHooks) {
        this.core = core;
        this.sessions = sessions;
        this.messages = messages;
        this.tasks = tasks;
        this.joinHooks = joinHooks;
        this.quitHooks = quitHooks;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            pending.put(event.getUniqueId(), core.players().register(event.getUniqueId(), event.getName()));
        } catch (RuntimeException e) {
            core.logger().log(Level.SEVERE, "Failed to register " + event.getName() + " (" + event.getUniqueId() + ")", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.render(messages.defaultLocale(), "login.unavailable"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            pending.remove(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerService.Registration registration = pending.remove(player.getUniqueId());
        if (registration == null) {
            // Should not happen; e.g. plugin enabled while the player was logging in. Register asynchronously.
            sessions.put(new PlayerSession(player.getUniqueId(), null));
            refresh(player);
            return;
        }
        SupportedLocale locale = SupportedLocale.parse(registration.player().locale()).orElse(null);
        sessions.put(new PlayerSession(player.getUniqueId(), locale));
        if (registration.firstJoin()) {
            messages.send(player, "join.welcome_first", "player", player.getName(),
                    "amount", core.config().economy().starterGrant());
        } else {
            messages.send(player, "join.welcome_back", "player", player.getName());
        }
        joinHooks.forEach(hook -> hook.accept(player));
    }

    /** Registers an already-online player (plugin enable / reload). */
    public void refresh(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        tasks.async(() -> core.players().register(uuid, name)).whenComplete((reg, error) -> tasks.sync(() -> {
            if (error != null) {
                core.logger().log(Level.SEVERE, "Failed to register online player " + name, Tasks.unwrap(error));
                return;
            }
            SupportedLocale locale = SupportedLocale.parse(reg.player().locale()).orElse(null);
            sessions.put(new PlayerSession(uuid, locale));
            if (player.isOnline()) {
                joinHooks.forEach(hook -> hook.accept(player));
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        quitHooks.forEach(hook -> hook.accept(uuid));
        sessions.remove(uuid);
        tasks.async("record quit " + uuid, () -> core.players().recordQuit(uuid));
    }
}
