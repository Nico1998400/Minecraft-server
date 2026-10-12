package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import se.nordia.plugins.paper.Permissions;

import java.util.List;

/**
 * {@code /spawn} teleports to the overworld spawn. {@code /setspawn} (op) sets that spawn to the player's position.
 */
public final class SpawnCommands {

    private final CommandServices svc;
    private final se.nordia.plugins.paper.essentials.Teleporter teleporter;

    public SpawnCommands(CommandServices svc, se.nordia.plugins.paper.essentials.Teleporter teleporter) {
        this.teleporter = teleporter;
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(spawn(), "Teleport to spawn / Teleportera till spawn", List.of());
        commands.register(setSpawn(), "Set the server spawn / Sätt serverns spawn", List.of("sattspawn"));
    }

    private LiteralCommandNode<CommandSourceStack> spawn() {
        return Commands.literal("spawn")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Location spawn = overworldSpawn();
                    teleporter.go(player, se.nordia.plugins.essentials.EssentialsConfig.Kind.SPAWN, spawn, "spawn.teleported");
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> setSpawn() {
        return Commands.literal("setspawn")
                .requires(CommandServices.permission(Permissions.ADMIN_SPAWN))
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Location loc = player.getLocation();
                    loc.getWorld().setSpawnLocation(loc);
                    player.setRespawnLocation(loc, true);
                    svc.messages().send(player, "spawn.set",
                            "world", loc.getWorld().getName(),
                            "x", loc.getBlockX(),
                            "y", loc.getBlockY(),
                            "z", loc.getBlockZ());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static Location overworldSpawn() {
        World overworld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (overworld == null) {
            throw new IllegalStateException("No worlds loaded");
        }
        return overworld.getSpawnLocation();
    }
}
