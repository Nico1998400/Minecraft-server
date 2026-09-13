package se.nordia.swedencore.paper.skills;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK detection. A player is active if they produced real input recently: turning their head, chatting, running a
 * command or clicking in an inventory.
 *
 * <p>Position changes alone do not count — water streams and minecarts move AFK players. Head rotation requires input.
 */
public final class ActivityTracker implements Listener {

    private static final float MIN_ROTATION_DEGREES = 3.0f;

    private final ConcurrentHashMap<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final long thresholdMillis;

    public ActivityTracker(Duration threshold) {
        this.thresholdMillis = threshold.toMillis();
    }

    public boolean isActive(UUID player) {
        Long last = lastActive.get(player);
        return last != null && System.currentTimeMillis() - last <= thresholdMillis;
    }

    public void touch(UUID player) {
        lastActive.put(player, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedOrientation()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        float yaw = Math.abs(wrap(to.getYaw() - from.getYaw()));
        float pitch = Math.abs(to.getPitch() - from.getPitch());
        if (yaw >= MIN_ROTATION_DEGREES || pitch >= MIN_ROTATION_DEGREES) {
            touch(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        touch(event.getWhoClicked().getUniqueId());
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
