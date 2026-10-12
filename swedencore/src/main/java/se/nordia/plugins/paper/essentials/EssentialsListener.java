package se.nordia.plugins.paper.essentials;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import se.nordia.plugins.core.NordiaCore;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.scheduler.Tasks;
import se.nordia.plugins.paper.text.Messages;
import se.nordia.plugins.social.SocialService;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Glue for the social features: nicknames on join, mute and ignore in chat, and vanish (hidden from other players and
 * ignored by mobs). Mutes and ignore lists are loaded once on join into {@link EssentialsState} so chat never waits for
 * the database.
 */
public final class EssentialsListener implements Listener {

    private record Loaded(Optional<String> nick, Optional<SocialService.Mute> mute, Set<UUID> ignored) {
    }

    private final Plugin plugin;
    private final NordiaCore core;
    private final Messages messages;
    private final Tasks tasks;
    private final EssentialsState state;

    public EssentialsListener(Plugin plugin, NordiaCore core, Messages messages, Tasks tasks, EssentialsState state) {
        this.plugin = plugin;
        this.core = core;
        this.messages = messages;
        this.tasks = tasks;
        this.state = state;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // People who are vanished stay hidden from the newcomer, unless the newcomer may see them.
        if (!player.hasPermission(Permissions.VANISH_SEE)) {
            for (UUID hidden : state.vanished) {
                Player other = Bukkit.getPlayer(hidden);
                if (other != null) {
                    player.hidePlayer(plugin, other);
                }
            }
        }
        tasks.async(() -> new Loaded(core.social().nick(id), core.social().activeMute(id), core.social().ignored(id)))
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load social data for " + player.getName(), Tasks.unwrap(error));
                        return;
                    }
                    tasks.sync(() -> {
                        loaded.nick().ifPresent(nick -> applyNick(player, nick));
                        loaded.mute().ifPresent(mute -> state.mutes.put(id, mute));
                        state.ignores.put(id, loaded.ignored());
                    });
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        state.forget(event.getPlayer().getUniqueId());
    }

    /** Shows a nickname in chat, the tab list and above the head. */
    public static void applyNick(Player player, String nick) {
        Component name = Component.text(nick);
        player.displayName(name);
        player.playerListName(name);
    }

    public static void clearNick(Player player) {
        player.displayName(null);
        player.playerListName(null);
    }

    /** Muted players cannot talk; people who ignore the sender do not see the message. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID id = sender.getUniqueId();
        SocialService.Mute mute = state.mutes.get(id);
        if (mute != null && !sender.hasPermission(Permissions.MUTE_EXEMPT)) {
            if (mute.until() != null && !mute.until().isAfter(Instant.now())) {
                state.mutes.remove(id);
            } else {
                event.setCancelled(true);
                tasks.sync(() -> messages.send(sender, "mute.you_are_muted"));
                return;
            }
        }
        event.viewers().removeIf(audience -> audience instanceof Player viewer && state.ignores(viewer.getUniqueId(), id));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && state.vanished.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Hides or shows a player to everyone who is not allowed to see vanished players. */
    public void setVanished(Player player, boolean vanish) {
        UUID id = player.getUniqueId();
        if (vanish) {
            state.vanished.add(id);
        } else {
            state.vanished.remove(id);
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || other.hasPermission(Permissions.VANISH_SEE)) {
                continue;
            }
            if (vanish) {
                other.hidePlayer(plugin, player);
            } else {
                other.showPlayer(plugin, player);
            }
        }
        player.setCollidable(!vanish);
        player.setSleepingIgnored(vanish);
    }
}
