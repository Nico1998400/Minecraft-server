package se.nordia.plugins.paper.gui;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import se.nordia.plugins.paper.text.Messages;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server texture pack (quest emblems and other custom art).
 *
 * <p>When a player joins they are asked once ("This server recommends a custom resource pack. Download and install it?").
 * Yes downloads it and it stays on; No is remembered so they are not asked again. Either way they can switch it with
 * {@code /texturer} or the menu button at any time. It is only <em>required</em> if the owner sets {@code required: true}.
 * Unavailable until the plugin has a download link and SHA-1 (built-in host or a link from the config).
 */
public final class TexturePack implements Listener {

    private static final long ASK_DELAY_TICKS = 60L;

    private final Plugin plugin;
    private final Messages messages;
    private final String url;
    private final byte[] sha1;
    private final UUID id;
    private final boolean askOnJoin;
    private final boolean required;
    private final NamespacedKey declinedKey;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Players who were sent the pack on join (no chat message when it loads). */
    private final Set<UUID> automatic = ConcurrentHashMap.newKeySet();

    /** Pass a null or empty {@code url} (or a null {@code sha1}) for an unavailable pack. */
    public TexturePack(Plugin plugin, Messages messages, String url, byte[] sha1, UUID id, boolean askOnJoin, boolean required) {
        this.plugin = plugin;
        this.messages = messages;
        boolean usable = url != null && !url.isBlank() && sha1 != null && sha1.length == 20 && id != null;
        this.url = usable ? url.trim() : "";
        this.sha1 = usable ? sha1.clone() : null;
        this.id = usable ? id : null;
        this.askOnJoin = askOnJoin;
        this.required = required;
        this.declinedKey = new NamespacedKey(plugin, "texturepack_declined");
    }

    public boolean available() {
        return !url.isEmpty();
    }

    public boolean isOn(Player player) {
        return active.contains(player.getUniqueId());
    }

    /** Switches the pack on if it is off and off if it is on. */
    public void toggle(Player player) {
        if (!available()) {
            messages.send(player, "texturepack.unavailable");
        } else if (isOn(player)) {
            active.remove(player.getUniqueId());
            player.removeResourcePacks(id);
            player.getPersistentDataContainer().set(declinedKey, PersistentDataType.BYTE, (byte) 1);
            messages.send(player, "texturepack.off");
        } else {
            player.getPersistentDataContainer().remove(declinedKey);
            messages.send(player, "texturepack.loading");
            send(player);
        }
    }

    private void send(Player player) {
        player.setResourcePack(id, url, sha1, messages.render(player, "texturepack.prompt"), required);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!available() || !askOnJoin) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getPersistentDataContainer().has(declinedKey, PersistentDataType.BYTE)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                automatic.add(player.getUniqueId());
                send(player);
            }
        }, ASK_DELAY_TICKS);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!available() || !id.equals(event.getID())) {
            return;
        }
        Player player = event.getPlayer();
        boolean quiet = automatic.contains(player.getUniqueId());
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                active.add(player.getUniqueId());
                automatic.remove(player.getUniqueId());
                if (!quiet) {
                    messages.send(player, "texturepack.on");
                }
            }
            case DECLINED -> {
                active.remove(player.getUniqueId());
                automatic.remove(player.getUniqueId());
                player.getPersistentDataContainer().set(declinedKey, PersistentDataType.BYTE, (byte) 1);
                if (!quiet) {
                    messages.send(player, "texturepack.failed");
                }
            }
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                active.remove(player.getUniqueId());
                automatic.remove(player.getUniqueId());
                messages.send(player, "texturepack.failed");
            }
            case DISCARDED -> active.remove(player.getUniqueId());
            default -> {
                // accepted / downloaded: wait for the final result
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        automatic.remove(event.getPlayer().getUniqueId());
    }
}
