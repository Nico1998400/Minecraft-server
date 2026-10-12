package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.tutorial.TutorialBoards;
import se.nordia.plugins.paper.tutorial.TutorialMenu;

import java.util.List;

/**
 * {@code /tutorial} opens the tutorial menu for anyone. Admins can place the tutorial Vex where they stand
 * ({@code skapa}), move the nearest one ({@code flytta}), remove it ({@code ta-bort}) and list them ({@code lista}).
 */
public final class TutorialCommand {

    private final CommandServices svc;
    private final TutorialMenu menu;
    private final TutorialBoards boards;

    public TutorialCommand(CommandServices svc, TutorialMenu menu, TutorialBoards boards) {
        this.svc = svc;
        this.menu = menu;
        this.boards = boards;
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("tutorial")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        menu.open(player);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("skapa")
                        .requires(CommandServices.permission(Permissions.ADMIN_TUTORIAL))
                        .executes(c -> {
                            Player player = svc.requirePlayer(c.getSource());
                            if (player != null) {
                                boards.add(player.getLocation());
                                svc.messages().send(player, "tutorial.created");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("flytta")
                        .requires(CommandServices.permission(Permissions.ADMIN_TUTORIAL))
                        .executes(c -> {
                            Player player = svc.requirePlayer(c.getSource());
                            if (player != null) {
                                svc.messages().send(player, boards.moveNearest(player.getLocation(), player.getLocation(), 40.0)
                                        ? "tutorial.moved" : "tutorial.none_near_far");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("ta-bort")
                        .requires(CommandServices.permission(Permissions.ADMIN_TUTORIAL))
                        .executes(c -> {
                            Player player = svc.requirePlayer(c.getSource());
                            if (player != null) {
                                svc.messages().send(player, boards.removeNearest(player.getLocation(), 6.0)
                                        ? "tutorial.removed" : "tutorial.none_near");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("lista")
                        .requires(CommandServices.permission(Permissions.ADMIN_TUTORIAL))
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            svc.messages().send(sender, "tutorial.list_header", "count", boards.count());
                            for (String line : boards.describe()) {
                                svc.messages().send(sender, "tutorial.list_entry", "place", line);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build(), "Handledning / Tutorial", List.of("handledning"));
    }
}
