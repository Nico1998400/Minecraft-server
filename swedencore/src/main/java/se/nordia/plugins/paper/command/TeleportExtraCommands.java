package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.essentials.Spot;
import se.nordia.plugins.paper.essentials.Teleporter;
import se.nordia.plugins.paper.essentials.TpaManager;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * More ways to move: {@code /tpahere /tpacancel /tpall /tptoggle /tphere /tppos /top /jump /tpr /renamehome /warpinfo}.
 * The admin ones teleport at once; {@code /tpr} and the request commands follow the delays in essentials.yml.
 */
public final class TeleportExtraCommands {

    private static final int TPR_ATTEMPTS = 12;
    private static final Set<Material> DANGEROUS = Set.of(Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK, Material.FIRE,
            Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE);

    private final org.bukkit.plugin.Plugin plugin;
    private final EssentialsKit kit;
    private final Teleporter teleporter;
    private final TpaManager tpa;
    private final Supplier<EssentialsConfig> config;

    public TeleportExtraCommands(org.bukkit.plugin.Plugin plugin, EssentialsKit kit, Teleporter teleporter, TpaManager tpa,
                                Supplier<EssentialsConfig> config) {
        this.plugin = plugin;
        this.kit = kit;
        this.teleporter = teleporter;
        this.tpa = tpa;
        this.config = config;
    }

    public void register(Commands commands) {
        commands.register(EssentialsKit.literal("tpahere", "tpahere")
                .then(EssentialsKit.playerArg().executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        Player target = kit.online(p, StringArgumentType.getString(c, "player"));
                        if (target != null) {
                            tpa.requestHere(p, target);
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Be någon komma till dig / Ask a player to come to you", List.of());

        commands.register(EssentialsKit.literal("tpacancel", "tpacancel").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                tpa.cancel(p);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Avbryt din teleportförfrågan / Cancel your request", List.of());

        commands.register(EssentialsKit.literal("tpall", "tpall").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                tpa.requestAll(p);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Be alla komma till dig / Ask everyone to come to you", List.of("tpaall"));

        commands.register(EssentialsKit.literal("tptoggle", "tptoggle").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                boolean on = kit.state().tpBlocked.add(p.getUniqueId());
                if (!on) {
                    kit.state().tpBlocked.remove(p.getUniqueId());
                }
                kit.messages().send(p, "ess.toggle", "feature", kit.messages().render(p, "ess.feature.tpblock"), "state", kit.state(p, on));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Blockera teleportförfrågningar / Block teleport requests", List.of());

        commands.register(EssentialsKit.literal("tphere", "tphere")
                .then(EssentialsKit.playerArg().executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        Player target = kit.online(p, StringArgumentType.getString(c, "player"));
                        if (target != null) {
                            target.teleportAsync(p.getLocation());
                            kit.messages().send(p, "ess.tp_here", "player", target.getName());
                            kit.log(p.getName() + " teleported " + target.getName() + " to themselves");
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Hämta en spelare / Bring a player to you", List.of("s"));

        commands.register(EssentialsKit.literal("tppos", "tppos")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000))
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(-2_048, 4_096))
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30_000_000, 30_000_000)).executes(c -> {
                                    Player p = kit.executorPlayer(c);
                                    if (p != null) {
                                        Location to = new Location(p.getWorld(), DoubleArgumentType.getDouble(c, "x"),
                                                DoubleArgumentType.getDouble(c, "y"), DoubleArgumentType.getDouble(c, "z"),
                                                p.getLocation().getYaw(), p.getLocation().getPitch());
                                        p.teleportAsync(to);
                                        kit.log(p.getName() + " used tppos to " + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ());
                                    }
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .build(), "Teleportera till koordinater / Teleport to coordinates", List.of());

        commands.register(EssentialsKit.literal("top", "top").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                Location l = p.getLocation();
                int y = p.getWorld().getHighestBlockYAt(l) + 1;
                p.teleportAsync(new Location(p.getWorld(), l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch()));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Till högsta blocket / Go to the highest block here", List.of());

        commands.register(EssentialsKit.literal("jump", "jump").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                Block block = p.getTargetBlockExact(150);
                if (block == null) {
                    kit.messages().send(p, "ess.no_block");
                } else {
                    Location l = block.getLocation().add(0.5, 1, 0.5);
                    l.setYaw(p.getLocation().getYaw());
                    l.setPitch(p.getLocation().getPitch());
                    p.teleportAsync(l);
                }
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Hoppa till blocket du tittar på / Jump to the block you look at", List.of("j", "jumpto"));

        commands.register(EssentialsKit.literal("tpr", "tpr").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                randomTeleport(p);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Slumpmässig teleport / Random teleport", List.of("tprandom"));

        commands.register(EssentialsKit.literal("renamehome", "renamehome")
                .then(Commands.argument("old", StringArgumentType.word())
                        .then(Commands.argument("new", StringArgumentType.word()).executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                UUID id = p.getUniqueId();
                                String oldName = StringArgumentType.getString(c, "old");
                                String newName = StringArgumentType.getString(c, "new");
                                kit.svc().tasks().run(p, () -> {
                                    kit.svc().core().essentials().renameHome(id, oldName, newName);
                                    return newName.toLowerCase(java.util.Locale.ROOT);
                                }, renamed -> kit.messages().send(p, "home.renamed", "old", oldName.toLowerCase(java.util.Locale.ROOT), "name", renamed));
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .build(), "Byt namn på ett hem / Rename a home", List.of());

        commands.register(EssentialsKit.literal("warpinfo", "warpinfo")
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    var sender = c.getSource().getSender();
                    String name = StringArgumentType.getString(c, "name");
                    kit.svc().tasks().run(sender, () -> kit.svc().core().essentials().warp(name), (Spot spot) ->
                            kit.messages().send(sender, "warp.info", "name", name.toLowerCase(java.util.Locale.ROOT), "world", spot.world(),
                                    "x", String.valueOf((int) Math.floor(spot.x())), "y", String.valueOf((int) Math.floor(spot.y())),
                                    "z", String.valueOf((int) Math.floor(spot.z()))));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Information om en warp / Warp information", List.of());
    }

    // ---- random teleport

    private void randomTeleport(Player player) {
        long cooldown = teleporter.cooldownRemaining(player, EssentialsConfig.Kind.TPR);
        if (cooldown > 0) {
            kit.messages().send(player, "essentials.cooldown", "seconds", String.valueOf(cooldown));
            return;
        }
        World world = player.getWorld().getEnvironment() == World.Environment.NORMAL ? player.getWorld() : Bukkit.getWorlds().getFirst();
        kit.messages().send(player, "tpr.searching");
        search(player, world, TPR_ATTEMPTS);
    }

    private void search(Player player, World world, int attemptsLeft) {
        if (attemptsLeft <= 0 || !player.isOnline()) {
            if (player.isOnline()) {
                kit.messages().send(player, "tpr.failed");
            }
            return;
        }
        EssentialsConfig cfg = config.get();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0, Math.PI * 2);
        double distance = random.nextDouble(cfg.tprMinRadius(), cfg.tprMaxRadius());
        Location center = world.getSpawnLocation();
        int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            search(player, world, attemptsLeft - 1);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block ground = world.getBlockAt(x, y, z);
            boolean safe = !ground.isLiquid() && !ground.isEmpty() && !DANGEROUS.contains(ground.getType())
                    && world.getBlockAt(x, y + 1, z).isPassable() && world.getBlockAt(x, y + 2, z).isPassable()
                    && !world.getBlockAt(x, y + 1, z).isLiquid();
            if (safe) {
                teleporter.go(player, EssentialsConfig.Kind.TPR, new Location(world, x + 0.5, y + 1, z + 0.5), "essentials.arrived.tpr");
            } else {
                search(player, world, attemptsLeft - 1);
            }
        }));
    }
}
