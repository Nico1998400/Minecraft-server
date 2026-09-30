package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.paper.world.BuildWorld;

import java.util.List;

/** {@code /bygg} and {@code /tillbaka}: op-only travel between the main world and the flat build world. */
public final class BuildWorldCommands {

    private final CommandServices svc;
    private final BuildWorld buildWorld;

    public BuildWorldCommands(CommandServices svc, BuildWorld buildWorld) {
        this.svc = svc;
        this.buildWorld = buildWorld;
    }

    public void register(Commands commands) {
        commands.register(enter(), "Go to the build world / Gå till byggvärlden", List.of("buildworld"));
        commands.register(leave(), "Return from the build world / Gå tillbaka från byggvärlden", List.of("buildback"));
    }

    private LiteralCommandNode<CommandSourceStack> enter() {
        return Commands.literal("bygg")
                .requires(CommandServices.permission(Permissions.ADMIN_BUILDWORLD))
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!player.hasPermission(Permissions.ADMIN_BUILDWORLD)) {
                        svc.messages().send(player, "buildworld.no_permission");
                    } else if (buildWorld.enter(player)) {
                        svc.messages().send(player, "buildworld.entered");
                    } else {
                        svc.messages().send(player, "buildworld.unavailable");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> leave() {
        return Commands.literal("tillbaka")
                .requires(CommandServices.permission(Permissions.ADMIN_BUILDWORLD))
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!player.hasPermission(Permissions.ADMIN_BUILDWORLD)) {
                        svc.messages().send(player, "buildworld.no_permission");
                    } else {
                        buildWorld.leave(player);
                        svc.messages().send(player, "buildworld.left");
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
