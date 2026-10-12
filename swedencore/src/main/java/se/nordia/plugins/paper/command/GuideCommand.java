package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.guide.GuideService;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.guide.GuideNpc;

import java.util.List;

/**
 * {@code /brage} tells players where the guide is. Admins can also move him ({@code /brage flytta}) and check whether the
 * AI is switched on ({@code /brage status}); the API key itself is never shown.
 */
public final class GuideCommand {

    private final CommandServices svc;
    private final GuideNpc npc;
    private final GuideService guide;

    public GuideCommand(CommandServices svc, GuideNpc npc, GuideService guide) {
        this.svc = svc;
        this.npc = npc;
        this.guide = guide;
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("brage")
                .executes(c -> {
                    sendHelp(c.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(move())
                .then(status())
                .then(route())
                .then(emblem())
                .then(reset())
                .build(), "Guide / Guide", List.of("guide"));
    }

    /** What {@code /brage} on its own shows: how to talk to him, and for admins the list of commands. */
    private void sendHelp(CommandSender sender) {
        svc.messages().send(sender, "guide.hint", "name", npc.name());
        if (!sender.hasPermission(Permissions.ADMIN_GUIDE)) {
            return;
        }
        svc.messages().send(sender, "guide.help_header", "name", npc.name());
        String[][] entries = {
                {"/brage flytta", "flytta"},
                {"/brage status", "status"},
                {"/brage rutt start", "rutt_start"},
                {"/brage rutt stopp", "rutt_stopp"},
                {"/brage rutt punkt", "rutt_punkt"},
                {"/brage rutt rensa", "rutt_rensa"},
                {"/brage rutt info", "rutt_info"},
                {"/brage emblem [spelare] nytt|klart|inget", "emblem"},
                {"/brage reset [spelare]", "reset"}};
        for (String[] entry : entries) {
            svc.messages().send(sender, "guide.help_entry", "command", entry[0],
                    "description", svc.messages().render(sender, "guide.help." + entry[1]));
        }
    }

    private LiteralCommandNode<CommandSourceStack> move() {
        return Commands.literal("flytta")
                .requires(CommandServices.permission(Permissions.ADMIN_GUIDE))
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Location here = player.getLocation();
                    npc.moveTo(here);
                    svc.messages().send(player, "guide.moved", "name", npc.name(), "world", here.getWorld().getName(),
                            "x", here.getBlockX(), "y", here.getBlockY(), "z", here.getBlockZ());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    /** {@code /brage rutt start|stopp|punkt|rensa|info}: record the route he walks, by walking it yourself. */
    private LiteralCommandNode<CommandSourceStack> route() {
        return Commands.literal("rutt")
                .requires(CommandServices.permission(Permissions.ADMIN_GUIDE))
                .then(Commands.literal("start").executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        npc.startRecording(player);
                        svc.messages().send(player, "guide.route_started");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("stopp").executes(c -> {
                    int points = npc.stopRecording();
                    svc.messages().send(c.getSource().getSender(), "guide.route_stopped", "count", points);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("punkt").executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        svc.messages().send(player, "guide.route_point", "count", npc.addRoutePoint(player.getLocation()));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("rensa").executes(c -> {
                    npc.clearRoute();
                    svc.messages().send(c.getSource().getSender(), "guide.route_cleared");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("info").executes(c -> {
                    CommandSender sender = c.getSource().getSender();
                    svc.messages().send(sender, "guide.route_info", "count", npc.routeSize(),
                            "state", svc.messages().render(sender, npc.recording() ? "guide.route_recording"
                                    : npc.patrolEnabled() && npc.routeSize() >= 2 ? "guide.route_walking"
                                    : npc.wandering() ? "guide.route_wandering" : "guide.route_idle"));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    /** {@code /brage emblem <player> <nytt|klart|inget>}: what the player sees above his head (for testing and for the quest system). */
    private LiteralCommandNode<CommandSourceStack> emblem() {
        return Commands.literal("emblem")
                .requires(CommandServices.permission(Permissions.ADMIN_GUIDE))
                .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .then(Commands.argument("state", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (String s : List.of("nytt", "klart", "inget")) {
                                        if (s.startsWith(b.getRemainingLowerCase())) {
                                            b.suggest(s);
                                        }
                                    }
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    CommandSender sender = c.getSource().getSender();
                                    Player target = org.bukkit.Bukkit.getPlayerExact(
                                            com.mojang.brigadier.arguments.StringArgumentType.getString(c, "player"));
                                    GuideNpc.Mark mark = switch (com.mojang.brigadier.arguments.StringArgumentType.getString(c, "state")
                                            .toLowerCase(java.util.Locale.ROOT)) {
                                        case "nytt" -> GuideNpc.Mark.AVAILABLE;
                                        case "klart" -> GuideNpc.Mark.READY;
                                        case "inget" -> GuideNpc.Mark.NONE;
                                        default -> null;
                                    };
                                    if (target == null || mark == null) {
                                        svc.messages().send(sender, target == null ? "error.player.unknown" : "guide.emblem_usage");
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    npc.setMark(target, mark);
                                    svc.messages().send(sender, "guide.emblem_set", "player", target.getName(),
                                            "state", svc.messages().render(sender, "guide.mark." + mark.name().toLowerCase(java.util.Locale.ROOT)));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    /** {@code /brage reset [player]}: start over with Brage (for yourself, or for another player). */
    private LiteralCommandNode<CommandSourceStack> reset() {
        return Commands.literal("reset")
                .requires(CommandServices.permission(Permissions.ADMIN_GUIDE))
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        npc.resetProgress(player);
                        svc.messages().send(player, "guide.reset_self");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            Player target = org.bukkit.Bukkit.getPlayerExact(
                                    com.mojang.brigadier.arguments.StringArgumentType.getString(c, "player"));
                            if (target == null) {
                                svc.messages().send(sender, "error.player.unknown");
                                return Command.SINGLE_SUCCESS;
                            }
                            npc.resetProgress(target);
                            svc.messages().send(sender, "guide.reset_other", "player", target.getName());
                            if (!sender.equals(target)) {
                                svc.messages().send(target, "guide.reset_self");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> status() {
        return Commands.literal("status")
                .requires(CommandServices.permission(Permissions.ADMIN_GUIDE))
                .executes(c -> {
                    CommandSender sender = c.getSource().getSender();
                    var messages = svc.messages();
                    messages.send(sender, "guide.status", "name", npc.name(),
                            "ai", messages.render(sender, guide.aiAvailable() ? "guide.on" : "guide.off"),
                            "model", guide.config().model(),
                            "npc", messages.render(sender, npc.figurePresent() ? "guide.present" : "guide.absent"));
                    if (guide.config().apiKey().isEmpty()) {
                        messages.send(sender, "guide.status_nokey");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
