package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.essentials.EssentialsService;
import se.nordia.plugins.essentials.Spot;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.essentials.Teleporter;
import se.nordia.plugins.paper.essentials.TpaManager;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@code /sethome /home /delhome /homes}, {@code /setwarp /warp /delwarp /warps}, {@code /back} and
 * {@code /tpa /tpaccept /tpdeny}. Delays and limits come from essentials.yml via {@link Teleporter}.
 */
public final class EssentialsCommands {

    private static final String DEFAULT_HOME = "home";

    private final CommandServices svc;
    private final Teleporter teleporter;
    private final TpaManager tpa;
    private final Logger logger;

    public EssentialsCommands(CommandServices svc, Teleporter teleporter, TpaManager tpa, Logger logger) {
        this.svc = svc;
        this.teleporter = teleporter;
        this.tpa = tpa;
        this.logger = logger;
    }

    private EssentialsService service() {
        return svc.core().essentials();
    }

    public void register(Commands commands) {
        commands.register(optionalName("sethome", this::setHome).build(), "Spara ett hem / Save a home", List.of("sattahem"));
        commands.register(optionalName("home", this::home).build(), "Gå hem / Go home", List.of("hem"));
        commands.register(requiredName("delhome", this::delHome).build(), "Ta bort ett hem / Delete a home", List.of("taborthem"));
        commands.register(Commands.literal("homes").executes(c -> run(c, this::listHomes)).build(),
                "Lista dina hem / List your homes", List.of("hems"));
        commands.register(requiredName("setwarp", this::setWarp)
                .requires(CommandServices.permission(Permissions.ESSENTIALS_WARP_MANAGE)).build(),
                "Skapa en warp / Create a warp", List.of());
        commands.register(requiredName("delwarp", this::delWarp)
                .requires(CommandServices.permission(Permissions.ESSENTIALS_WARP_MANAGE)).build(),
                "Ta bort en warp / Delete a warp", List.of());
        commands.register(requiredName("warp", this::warp).executes(c -> run(c, this::listWarps)).build(),
                "Teleportera till en warp / Go to a warp", List.of());
        commands.register(Commands.literal("warps").executes(c -> run(c, this::listWarps)).build(),
                "Lista warps / List warps", List.of());
        commands.register(Commands.literal("back").executes(c -> run(c, this::back)).build(),
                "Tillbaka till förra platsen / Return to your previous location", List.of("tillbaka"));
        commands.register(Commands.literal("tpa")
                .then(Commands.argument("player", StringArgumentType.word()).suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            Player player = svc.requirePlayer(c.getSource());
                            if (player == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            Player target = Bukkit.getPlayerExact(StringArgumentType.getString(c, "player"));
                            if (target == null) {
                                svc.messages().send(player, "error.player.unknown");
                            } else {
                                tpa.request(player, target);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build(), "Be om att teleportera till en spelare / Ask to teleport to a player", List.of());
        commands.register(Commands.literal("tpaccept").executes(c -> run(c, tpa::accept)).build(),
                "Godkänn en teleportförfrågan / Accept a teleport request", List.of("tpgodkann"));
        commands.register(Commands.literal("tpdeny").executes(c -> run(c, tpa::deny)).build(),
                "Neka en teleportförfrågan / Deny a teleport request", List.of("tpneka"));
    }

    // ---- command shapes

    private interface PlayerAction {
        void run(Player player);
    }

    private interface NamedAction {
        void run(Player player, String name);
    }

    private int run(CommandContext<CommandSourceStack> c, PlayerAction action) {
        Player player = svc.requirePlayer(c.getSource());
        if (player != null) {
            action.run(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private LiteralArgumentBuilder<CommandSourceStack> optionalName(String literal, NamedAction action) {
        return Commands.literal(literal)
                .executes(c -> run(c, p -> action.run(p, DEFAULT_HOME)))
                .then(nameArgument(action));
    }

    private LiteralArgumentBuilder<CommandSourceStack> requiredName(String literal, NamedAction action) {
        return Commands.literal(literal).then(nameArgument(action));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> nameArgument(NamedAction action) {
        return Commands.argument("name", StringArgumentType.word())
                .executes(c -> run(c, p -> action.run(p, StringArgumentType.getString(c, "name"))));
    }

    // ---- homes

    private void setHome(Player player, String name) {
        UUID id = player.getUniqueId();
        Spot spot = spot(player.getLocation());
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> {
            service().setHome(id, clean, spot, teleporter.config().maxHomes());
            return clean;
        }, saved -> {
            svc.messages().send(player, "home.set", "name", saved);
            log(player.getName() + " saved home '" + saved + "' at " + describe(spot));
        });
    }

    private void home(Player player, String name) {
        UUID id = player.getUniqueId();
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> service().home(id, clean),
                spot -> teleportTo(player, spot, EssentialsConfig.Kind.HOME, "essentials.arrived.home", "name", clean));
    }

    private void delHome(Player player, String name) {
        UUID id = player.getUniqueId();
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> {
            service().deleteHome(id, clean);
            return clean;
        }, removed -> {
            svc.messages().send(player, "home.deleted", "name", removed);
            log(player.getName() + " deleted home '" + removed + "'");
        });
    }

    private void listHomes(Player player) {
        UUID id = player.getUniqueId();
        svc.tasks().run(player, () -> service().homes(id), homes -> {
            if (homes.isEmpty()) {
                svc.messages().send(player, "home.none");
                return;
            }
            svc.messages().send(player, "home.list_header", "count", homes.size(), "max", teleporter.config().maxHomes());
            for (EssentialsService.Home home : homes) {
                svc.messages().send(player, "home.list_entry", "name", home.name(), "place", describe(home.spot()));
            }
        });
    }

    // ---- warps

    private void setWarp(Player player, String name) {
        UUID id = player.getUniqueId();
        Spot spot = spot(player.getLocation());
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> {
            service().setWarp(id, clean, spot);
            return clean;
        }, saved -> {
            svc.messages().send(player, "warp.set", "name", saved);
            log(player.getName() + " saved warp '" + saved + "' at " + describe(spot));
        });
    }

    private void delWarp(Player player, String name) {
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> {
            service().deleteWarp(clean);
            return clean;
        }, removed -> {
            svc.messages().send(player, "warp.deleted", "name", removed);
            log(player.getName() + " deleted warp '" + removed + "'");
        });
    }

    private void warp(Player player, String name) {
        String clean = name.toLowerCase(Locale.ROOT);
        svc.tasks().run(player, () -> service().warp(clean),
                spot -> teleportTo(player, spot, EssentialsConfig.Kind.WARP, "essentials.arrived.warp", "name", clean));
    }

    private void listWarps(Player player) {
        svc.tasks().run(player, () -> service().warps(), warps -> {
            if (warps.isEmpty()) {
                svc.messages().send(player, "warp.none");
                return;
            }
            svc.messages().send(player, "warp.list_header", "count", warps.size());
            for (EssentialsService.Warp warp : warps) {
                svc.messages().send(player, "warp.list_entry", "name", warp.name());
            }
        });
    }

    // ---- back

    private void back(Player player) {
        teleporter.backLocation(player).ifPresentOrElse(
                location -> teleporter.go(player, EssentialsConfig.Kind.BACK, location, "essentials.arrived.back"),
                () -> svc.messages().send(player, "essentials.back_none"));
    }

    // ---- helpers

    private void teleportTo(Player player, Spot spot, EssentialsConfig.Kind kind, String arrivedKey, Object... args) {
        World world = Bukkit.getWorld(spot.world());
        if (world == null) {
            svc.messages().send(player, "essentials.world_missing", "world", spot.world());
            return;
        }
        teleporter.go(player, kind, new Location(world, spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch()), arrivedKey, args);
    }

    private static Spot spot(Location l) {
        return new Spot(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    private static String describe(Spot spot) {
        return spot.world() + " " + (int) Math.floor(spot.x()) + " " + (int) Math.floor(spot.y()) + " " + (int) Math.floor(spot.z());
    }

    private void log(String line) {
        if (teleporter.config().logActions()) {
            logger.info("[Essentials] " + line);
        }
    }
}
