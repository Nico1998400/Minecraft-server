package se.nordia.swedencore.paper.skills;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import se.nordia.swedencore.skills.Skill;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Building XP is granted only for blocks that are still standing after a maturity period.
 *
 * <p>This defeats place/break loops: breaking a block before it matures cancels its XP, and re-placing at the same
 * position restarts the timer. Combined with the hourly soft cap, building XP rewards actual construction.
 */
public final class BuildingXpListener implements Listener {

    private static final int MAX_PENDING_PER_PLAYER = 4_000;

    private record Position(UUID world, int x, int y, int z) {
    }

    private record Pending(UUID player, Material material, long placedAt) {
    }

    private final SkillTracker tracker;
    private final PlacedBlockTracker placed;
    private final ActivityTracker activity;
    private final XpTables tables;
    private final long maturityMillis;
    private final LinkedHashMap<Position, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Integer> pendingPerPlayer = new HashMap<>();

    public BuildingXpListener(SkillTracker tracker, PlacedBlockTracker placed, ActivityTracker activity, XpTables tables,
                              long maturityMillis) {
        this.tracker = tracker;
        this.placed = placed;
        this.activity = activity;
        this.tables = tables;
        this.maturityMillis = maturityMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL || !activity.isActive(player.getUniqueId())) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (tables.building(block.getType()) == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (pendingPerPlayer.getOrDefault(uuid, 0) >= MAX_PENDING_PER_PLAYER) {
            return;
        }
        Position pos = new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        Pending previous = pending.remove(pos);
        if (previous != null) {
            decrement(previous.player());
        }
        pending.put(pos, new Pending(uuid, block.getType(), System.currentTimeMillis()));
        pendingPerPlayer.merge(uuid, 1, Integer::sum);
    }

    /** Called periodically on the main thread. Entries are in insertion (time) order. */
    public void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Position, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Position, Pending> entry = it.next();
            Pending p = entry.getValue();
            if (now - p.placedAt() < maturityMillis) {
                break;
            }
            it.remove();
            decrement(p.player());
            Position pos = entry.getKey();
            World world = Bukkit.getWorld(pos.world());
            Player player = Bukkit.getPlayer(p.player());
            if (world == null || player == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Integer xp = tables.building(p.material());
            if (xp != null && block.getType() == p.material() && placed.isPlaced(block)) {
                tracker.award(player, Skill.BUILDING, xp, false);
            }
        }
    }

    private void decrement(UUID player) {
        pendingPerPlayer.computeIfPresent(player, (k, v) -> v <= 1 ? null : v - 1);
    }
}
