package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.economy.AccountOwner;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.player.NordiaPlayer;
import se.nordia.plugins.util.Durations;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Looking things up: {@code /online /seen /whois /playtime /ping /near /getpos /compass /depth /tps}. */
public final class InfoCommands {

    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private static final int SEA_LEVEL = 63;

    private record Seen(NordiaPlayer player, Optional<String> nick, Optional<String> discord, String balance) {
    }

    private final EssentialsKit kit;
    private final Supplier<EssentialsConfig> config;

    public InfoCommands(EssentialsKit kit, Supplier<EssentialsConfig> config) {
        this.kit = kit;
        this.config = config;
    }

    public void register(Commands commands) {
        commands.register(EssentialsKit.literal("online", "online").executes(c -> {
            online(c.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        }).build(), "Vilka är online / Who is online", List.of("list", "who", "plist", "playerlist"));

        commands.register(EssentialsKit.literal("seen", "seen")
                .then(EssentialsKit.playerArg().executes(c -> {
                    seen(c.getSource().getSender(), StringArgumentType.getString(c, "player"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "När sågs spelaren sist / When was a player last seen", List.of());

        commands.register(EssentialsKit.literal("whois", "whois")
                .then(EssentialsKit.playerArg().executes(c -> {
                    whois(c.getSource().getSender(), StringArgumentType.getString(c, "player"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Information om en spelare / Player information", List.of());

        commands.register(kit.selfOrOther("playtime", "playtime", (s, t) -> {
            Duration played = Duration.ofSeconds(t.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
            kit.messages().send(s, "info.playtime", "player", t.getName(), "time", Durations.format(played));
        }).build(), "Speltid / Time played", List.of());

        commands.register(kit.selfOrOther("ping", "ping", (s, t) ->
                kit.messages().send(s, "info.ping", "player", t.getName(), "ping", t.getPing())).build(), "Ping", List.of("pong"));

        commands.register(EssentialsKit.literal("near", "near")
                .executes(c -> near(c.getSource().getSender(), config.get().nearRadius()))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 2000)).executes(c ->
                        near(c.getSource().getSender(), Math.min(IntegerArgumentType.getInteger(c, "radius"), 2000))))
                .build(), "Spelare i närheten / Nearby players", List.of("nearby"));

        commands.register(kit.selfOrOther("getpos", "getpos", (s, t) -> {
            Location l = t.getLocation();
            kit.messages().send(s, "info.getpos", "player", t.getName(), "world", l.getWorld().getName(),
                    "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()), "z", String.valueOf(l.getBlockZ()),
                    "direction", direction(l.getYaw()));
        }).build(), "Visa koordinater / Show coordinates", List.of("coords", "position", "whereami"));

        commands.register(EssentialsKit.literal("compass", "compass").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                kit.messages().send(p, "info.compass", "direction", direction(p.getLocation().getYaw()));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Visa riktning / Show your bearing", List.of("direction"));

        commands.register(EssentialsKit.literal("depth", "depth").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                int y = p.getLocation().getBlockY();
                kit.messages().send(p, "info.depth", "y", String.valueOf(y), "relative", String.valueOf(y - SEA_LEVEL));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Höjd över havet / Height relative to sea level", List.of("height"));

        commands.register(Commands.literal("tps").requires(CommandServices.permission(Permissions.ess("tps")))
                .executes(c -> {
                    performance(c.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }).build(), "Serverprestanda / Server performance", List.of("gc", "lag", "mem", "memory", "uptime"));
    }

    private void online(CommandSender sender) {
        List<Component> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!kit.state().vanished.contains(p.getUniqueId()) || sender.hasPermission(Permissions.VANISH_SEE)) {
                names.add(p.displayName());
            }
        }
        kit.messages().send(sender, "info.online_header", "count", names.size(), "max", Bukkit.getMaxPlayers());
        if (!names.isEmpty()) {
            kit.messages().send(sender, "info.online_line", "players", Component.join(JoinConfiguration.commas(true), names));
        }
    }

    private void seen(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            kit.messages().send(sender, "info.seen_online", "player", online.getName());
            return;
        }
        kit.svc().tasks().run(sender, () -> kit.svc().core().social().findByNameOrNick(name)
                        .flatMap(id -> kit.svc().core().players().find(id))
                        .orElseThrow(() -> DomainException.of("player.unknown", "player", name)),
                p -> kit.messages().send(sender, "info.seen_offline", "player", p.name(), "time", p.lastSeen()));
    }

    private void whois(CommandSender sender, String name) {
        kit.svc().tasks().run(sender, () -> {
            var core = kit.svc().core();
            NordiaPlayer p = core.social().findByNameOrNick(name).flatMap(id -> core.players().find(id))
                    .orElseThrow(() -> DomainException.of("player.unknown", "player", name));
            String balance = core.economy().balance(AccountOwner.player(p.uuid())).format(kit.messages().localeOf(sender).javaLocale());
            return new Seen(p, core.social().nick(p.uuid()), core.discordLinks().linkedDiscordId(p.uuid()), balance);
        }, info -> {
            NordiaPlayer p = info.player();
            var m = kit.messages();
            m.send(sender, "whois.header", "player", p.name());
            m.send(sender, "whois.uuid", "value", p.uuid().toString());
            m.send(sender, "whois.nick", "value", info.nick().orElse("-"));
            m.send(sender, "whois.reputation", "value", p.reputation());
            m.send(sender, "whois.balance", "value", info.balance());
            m.send(sender, "whois.first_seen", "value", p.firstSeen());
            m.send(sender, "whois.last_seen", "value", p.lastSeen());
            m.send(sender, "whois.discord", "value", info.discord().orElse("-"));
            Player online = Bukkit.getPlayer(p.uuid());
            if (online != null) {
                Location l = online.getLocation();
                m.send(sender, "whois.online", "gamemode", online.getGameMode().name().toLowerCase(java.util.Locale.ROOT),
                        "world", l.getWorld().getName(), "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()),
                        "z", String.valueOf(l.getBlockZ()));
            }
        });
    }

    private int near(CommandSender sender, int radius) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        List<Component> found = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player) || (kit.state().vanished.contains(other.getUniqueId()) && !player.hasPermission(Permissions.VANISH_SEE))) {
                continue;
            }
            double distance = other.getLocation().distance(player.getLocation());
            if (distance <= radius) {
                found.add(kit.messages().render(player, "info.near_entry", "player", other.displayName(), "distance", String.valueOf((int) distance)));
            }
        }
        if (found.isEmpty()) {
            kit.messages().send(player, "info.near_none", "radius", String.valueOf(radius));
        } else {
            kit.messages().send(player, "info.near", "radius", String.valueOf(radius),
                    "players", Component.join(JoinConfiguration.commas(true), found));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void performance(CommandSender sender) {
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
        long maxMb = rt.maxMemory() / 1_048_576L;
        Duration uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        int chunks = 0;
        int entities = 0;
        for (var world : Bukkit.getWorlds()) {
            chunks += world.getLoadedChunks().length;
            entities += world.getEntityCount();
        }
        kit.messages().send(sender, "info.tps", "tps", String.format(java.util.Locale.ROOT, "%.1f", Math.min(20.0, tps[0])),
                "used", String.valueOf(usedMb), "max", String.valueOf(maxMb), "uptime", Durations.format(uptime),
                "chunks", String.valueOf(chunks), "entities", String.valueOf(entities));
    }

    /** The compass direction for a yaw (Minecraft: 0 = south, 90 = west). */
    static String direction(float yaw) {
        int index = Math.floorMod(Math.round(yaw / 45f), 8);
        return DIRECTIONS[index];
    }
}
