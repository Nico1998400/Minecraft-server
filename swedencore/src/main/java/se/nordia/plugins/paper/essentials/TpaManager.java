package se.nordia.plugins.paper.essentials;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.paper.text.Messages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teleport requests. {@code /tpa} asks to go to someone, {@code /tpahere} asks someone to come to you. A target has at most
 * one open request, a new one replaces the old, and requests expire after the configured time. Players can block all
 * requests with {@code /tptoggle}.
 */
public final class TpaManager {

    /** {@code here} = the target is the one who gets teleported (to the requester). */
    private record Request(UUID requester, long expiresAt, boolean here) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Teleporter teleporter;
    private final EssentialsState state;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();

    public TpaManager(Plugin plugin, Messages messages, Teleporter teleporter, EssentialsState state) {
        this.plugin = plugin;
        this.messages = messages;
        this.teleporter = teleporter;
        this.state = state;
    }

    public void request(Player from, Player to) {
        send(from, to, false);
    }

    public void requestHere(Player from, Player to) {
        send(from, to, true);
    }

    /** Asks everyone to come to the sender (admin). */
    public void requestAll(Player from) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(from)) {
                send(from, other, true);
            }
        }
    }

    private void send(Player from, Player to, boolean here) {
        if (from.equals(to)) {
            messages.send(from, "tpa.self");
            return;
        }
        if (state.tpBlocked.contains(to.getUniqueId())) {
            messages.send(from, "tpa.blocked", "player", to.getName());
            return;
        }
        long cooldown = teleporter.cooldownRemaining(here ? to : from, EssentialsConfig.Kind.TPA);
        if (cooldown > 0 && !here) {
            messages.send(from, "essentials.cooldown", "seconds", String.valueOf(cooldown));
            return;
        }
        int timeout = teleporter.config().tpaTimeoutSeconds();
        Request request = new Request(from.getUniqueId(), System.currentTimeMillis() + timeout * 1000L, here);
        requests.put(to.getUniqueId(), request);
        messages.send(from, "tpa.sent", "player", to.getName());
        messages.send(to, here ? "tpa.received_here" : "tpa.received", "player", from.getName(), "seconds", String.valueOf(timeout));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (requests.remove(to.getUniqueId(), request)) {
                Player requester = Bukkit.getPlayer(request.requester());
                if (requester != null) {
                    messages.send(requester, "tpa.expired", "player", to.getName());
                }
            }
        }, timeout * 20L);
    }

    public void accept(Player target) {
        Request request = requests.remove(target.getUniqueId());
        if (request == null || request.expiresAt() < System.currentTimeMillis()) {
            messages.send(target, "tpa.none");
            return;
        }
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester == null) {
            messages.send(target, "tpa.none");
            return;
        }
        messages.send(target, "tpa.accepted", "player", requester.getName());
        if (request.here()) {
            teleporter.go(target, EssentialsConfig.Kind.TPA, requester.getLocation(), "essentials.arrived.tpa", "player", requester.getName());
        } else {
            teleporter.go(requester, EssentialsConfig.Kind.TPA, target.getLocation(), "essentials.arrived.tpa", "player", target.getName());
        }
    }

    public void deny(Player target) {
        Request request = requests.remove(target.getUniqueId());
        if (request == null) {
            messages.send(target, "tpa.none");
            return;
        }
        messages.send(target, "tpa.denied_self");
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null) {
            messages.send(requester, "tpa.denied", "player", target.getName());
        }
    }

    /** Cancels every request the player has sent. */
    public void cancel(Player requester) {
        boolean any = requests.values().removeIf(r -> r.requester().equals(requester.getUniqueId()));
        messages.send(requester, any ? "tpa.cancelled" : "tpa.none_sent");
    }

    public void forget(UUID player) {
        requests.remove(player);
        requests.values().removeIf(r -> r.requester().equals(player));
    }
}
