package se.nordia.plugins.paper.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The flat creative build world ("bygg"): loaded at startup, sealed off from the economy and reachable only by ops.
 * Remembers where each player came from so {@code /tillbaka} returns them to the exact spot.
 */
public final class BuildWorld {

    public static final String NAME = "bygg";

    private final Logger log;
    private final Map<UUID, Location> returnPoints = new ConcurrentHashMap<>();

    public BuildWorld(Logger log) {
        this.log = log;
    }

    public static boolean isBuildWorld(World world) {
        return world != null && NAME.equals(world.getName());
    }

    /** Loads the world, creating it on first start. Must run on the main thread. */
    public World ensureLoaded() {
        World world = Bukkit.getWorld(NAME);
        if (world == null) {
            world = new WorldCreator(NAME)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .createWorld();
            if (world == null) {
                throw new IllegalStateException("Could not create build world " + NAME);
            }
            world.setSpawnLocation(0, world.getHighestBlockYAt(0, 0) + 1, 0);
            log.info("Created build world '" + NAME + "'");
        }
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6000);
        return world;
    }

    /** Plants the tree showcase row east of spawn. With {@code clear}, replaces any earlier row. */
    public void plantTrees(boolean clear) {
        World build = world();
        if (build != null) {
            new BuildTrees(build).plant(clear);
        }
    }

    /** Builds the ten livable houses south of the tree row. */
    public void buildHouses() {
        World build = world();
        if (build != null) {
            new BuildHouses(build).build(true);
        }
    }

    /** Builds the spawn showpieces south of the houses; returns a spot on their promenade, or null if not loaded. */
    public Location buildLandmarks() {
        World build = world();
        return build == null ? null : new BuildLandmarks(build).build();
    }

    public World world() {
        return Bukkit.getWorld(NAME);
    }

    /** Teleports to the build world, remembering the origin. Returns false if the world is not loaded. */
    public boolean enter(Player player) {
        World build = world();
        if (build == null) {
            return false;
        }
        if (!isBuildWorld(player.getWorld())) {
            returnPoints.put(player.getUniqueId(), player.getLocation());
        }
        player.teleportAsync(build.getSpawnLocation().add(0.5, 0, 0.5));
        return true;
    }

    /** Teleports back to where {@link #enter} was used, or the main world's spawn. */
    public void leave(Player player) {
        Location back = returnPoints.remove(player.getUniqueId());
        if (back == null || back.getWorld() == null) {
            back = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        player.teleportAsync(back);
    }
}
