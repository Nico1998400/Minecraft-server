package se.nordia.plugins.paper.essentials;

import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.text.Messages;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Every player teleport that has a delay goes through here: spawn, home, warp, tpa and back. It applies the warm-up and
 * cooldown from essentials.yml (read fresh each time, so {@code /nordia reload} takes effect at once), cancels when the
 * player moves or is hurt, and remembers where the player came from for {@code /back}. All methods run on the main thread.
 */
public final class Teleporter implements Listener {

    private static final double MOVE_TOLERANCE_SQUARED = 0.25;
    private static final long CHECK_TICKS = 5L;

    private record Pending(Location origin, BukkitTask task) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Supplier<EssentialsConfig> config;
    private final Logger logger;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Location> back = new ConcurrentHashMap<>();

    public Teleporter(Plugin plugin, Messages messages, Supplier<EssentialsConfig> config) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    public EssentialsConfig config() {
        return config.get();
    }

    /** Seconds left of the player's cooldown for this kind (0 = ready; bypass players are always ready). */
    public long cooldownRemaining(Player player, EssentialsConfig.Kind kind) {
        if (player.hasPermission(Permissions.ESSENTIALS_BYPASS)) {
            return 0;
        }
        Long until = cooldownUntil.get(key(player, kind));
        long left = until == null ? 0 : until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    public Optional<Location> backLocation(Player player) {
        return Optional.ofNullable(back.get(player.getUniqueId())).map(Location::clone);
    }

    /**
     * Starts a teleport after the configured warm-up.
     *
     * @param arrivedKey language key sent when the player has arrived; it gets {@code arrivedArgs}
     */
    public void go(Player player, EssentialsConfig.Kind kind, Location destination, String arrivedKey, Object... arrivedArgs) {
        EssentialsConfig cfg = config.get();
        boolean bypass = player.hasPermission(Permissions.ESSENTIALS_BYPASS);
        long cooldown = cooldownRemaining(player, kind);
        if (cooldown > 0) {
            messages.send(player, "essentials.cooldown", "seconds", String.valueOf(cooldown));
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            messages.send(player, "essentials.already_pending");
            return;
        }
        EssentialsConfig.Rule rule = cfg.rule(kind);
        if (bypass || rule.warmupSeconds() == 0) {
            arrive(player, kind, destination, arrivedKey, arrivedArgs);
            return;
        }
        messages.send(player, "essentials.warmup", "seconds", String.valueOf(rule.warmupSeconds()));
        long[] ticksLeft = {rule.warmupSeconds() * 20L};
        Location origin = player.getLocation().clone();
        UUID id = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                cancel(id, null);
                return;
            }
            if (cfg.cancelOnMove() && moved(origin, online.getLocation())) {
                cancel(id, "essentials.cancelled_move");
                return;
            }
            ticksLeft[0] -= CHECK_TICKS;
            if (ticksLeft[0] <= 0) {
                cancel(id, null);
                arrive(online, kind, destination, arrivedKey, arrivedArgs);
                return;
            }
            if (ticksLeft[0] % 20 == 0) {
                online.sendActionBar(messages.render(online, "essentials.countdown", "seconds", String.valueOf(ticksLeft[0] / 20)));
            }
        }, CHECK_TICKS, CHECK_TICKS);
        pending.put(id, new Pending(origin, task));
    }

    private void arrive(Player player, EssentialsConfig.Kind kind, Location destination, String arrivedKey, Object[] arrivedArgs) {
        EssentialsConfig.Rule rule = config.get().rule(kind);
        if (rule.cooldownSeconds() > 0) {
            cooldownUntil.put(key(player, kind), System.currentTimeMillis() + rule.cooldownSeconds() * 1000L);
        }
        player.teleportAsync(destination).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (Boolean.TRUE.equals(ok) && player.isOnline()) {
                messages.send(player, arrivedKey, arrivedArgs);
                if (config.get().logActions()) {
                    logger.info("[Essentials] " + player.getName() + " teleported (" + kind + ") to " + destination.getWorld().getName()
                            + " " + destination.getBlockX() + " " + destination.getBlockY() + " " + destination.getBlockZ());
                }
            }
        }));
    }

    private void cancel(UUID id, String messageKey) {
        Pending removed = pending.remove(id);
        if (removed == null) {
            return;
        }
        removed.task().cancel();
        Player player = Bukkit.getPlayer(id);
        if (messageKey != null && player != null) {
            messages.send(player, messageKey);
        }
    }

    private static boolean moved(Location origin, Location now) {
        return !origin.getWorld().equals(now.getWorld()) || origin.distanceSquared(now) > MOVE_TOLERANCE_SQUARED;
    }

    private static String key(Player player, EssentialsConfig.Kind kind) {
        return player.getUniqueId() + ":" + kind;
    }

    // ---- events

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && config.get().cancelOnDamage()) {
            cancel(player.getUniqueId(), "essentials.cancelled_damage");
        }
    }

    /** Remember where the player came from so /back can return. Pearls and chorus fruit do not count. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            return;
        }
        back.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        back.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), null);
        back.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        for (UUID id : pending.keySet()) {
            cancel(id, null);
        }
    }
}
