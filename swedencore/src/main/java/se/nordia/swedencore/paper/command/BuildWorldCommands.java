package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.paper.world.BuildWorld;

import java.util.List;
import java.util.Locale;

/** {@code /world bygg}, {@code /world nordia} {@code /world trad} and {@code /world hus}: op-only travel between the main world and the flat build world. */
public final class BuildWorldCommands {

    private static final String BUILD = "bygg";
    private static final String MAIN = "nordia";
    private static final String TREES = "trad";
    private static final String HOUSES = "hus";

    private final CommandServices svc;
    private final BuildWorld buildWorld;

    public BuildWorldCommands(CommandServices svc, BuildWorld buildWorld) {
        this.svc = svc;
        this.buildWorld = buildWorld;
    }

    public void register(Commands commands) {
        commands.register(world(), "Travel between Nordia and the build world / Res mellan Nordia och byggvärlden", List.of());
    }

    private LiteralCommandNode<CommandSourceStack> world() {
        return Commands.literal("world")
                .requires(CommandServices.permission(Permissions.ADMIN_BUILDWORLD))
                .then(Commands.argument("varld", StringArgumentType.word())
                        .suggests((c, b) -> {
                            String remaining = b.getRemainingLowerCase();
                            for (String name : List.of(BUILD, MAIN, TREES, HOUSES)) {
                                if (name.startsWith(remaining)) {
                                    b.suggest(name);
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(c -> {
                            Player player = svc.requirePlayer(c.getSource());
                            if (player == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            if (!player.hasPermission(Permissions.ADMIN_BUILDWORLD)) {
                                svc.messages().send(player, "buildworld.no_permission");
                                return Command.SINGLE_SUCCESS;
                            }
                            switch (StringArgumentType.getString(c, "varld").toLowerCase(Locale.ROOT)) {
                                case BUILD -> {
                                    if (buildWorld.enter(player)) {
                                        svc.messages().send(player, "buildworld.entered");
                                    } else {
                                        svc.messages().send(player, "buildworld.unavailable");
                                    }
                                }
                                case MAIN -> {
                                    buildWorld.leave(player);
                                    svc.messages().send(player, "buildworld.left");
                                }
                                case TREES -> {
                                    if (BuildWorld.isBuildWorld(player.getWorld())) {
                                        buildWorld.plantTrees(true);
                                        svc.messages().send(player, "buildworld.trees");
                                    } else {
                                        svc.messages().send(player, "buildworld.trees_elsewhere");
                                    }
                                }
                                case HOUSES -> {
                                    if (BuildWorld.isBuildWorld(player.getWorld())) {
                                        buildWorld.buildHouses();
                                        svc.messages().send(player, "buildworld.houses");
                                    } else {
                                        svc.messages().send(player, "buildworld.trees_elsewhere");
                                    }
                                }
                                default -> svc.messages().send(player, "buildworld.usage");
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "buildworld.usage");
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
