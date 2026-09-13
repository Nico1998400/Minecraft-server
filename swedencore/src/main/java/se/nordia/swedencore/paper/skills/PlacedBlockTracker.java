package se.nordia.swedencore.paper.skills;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which blocks were placed by players (or created artificially, e.g. cobblestone generators and pistons),
 * so that breaking them grants no XP. The core anti-exploit for gathering skills.
 *
 * <p>Markers are stored in each chunk's PersistentDataContainer as a packed int array, so they persist with the world
 * and cost nothing for chunks without placed blocks. Loaded lazily, written back on chunk unload and world save.
 *
 * <p>Only materials relevant to XP are tracked. Failure modes are deliberately one-sided: a stale marker can only deny
 * XP, never grant it.
 */
public final class PlacedBlockTracker implements Listener {

    private static final Set<Material> GENERATED_STONE = Set.of(
            Material.COBBLESTONE, Material.STONE, Material.BASALT, Material.OBSIDIAN, Material.COBBLED_DEEPSLATE);

    private record ChunkId(UUID world, long key) {
    }

    private static final class Marks {
        final IntOpenHashSet positions;
        boolean dirty;

        Marks(int[] stored) {
            positions = stored == null ? new IntOpenHashSet() : new IntOpenHashSet(stored);
        }
    }

    private final NamespacedKey key;
    private final XpTables tables;
    private final Map<ChunkId, Marks> cache = new HashMap<>();

    public PlacedBlockTracker(Plugin plugin, XpTables tables) {
        this.key = new NamespacedKey(plugin, "placed_blocks");
        this.tables = tables;
    }

    static int pack(Block block) {
        return ((block.getY() + 4096) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private Marks marks(Chunk chunk) {
        return cache.computeIfAbsent(new ChunkId(chunk.getWorld().getUID(), chunk.getChunkKey()),
                id -> new Marks(chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY)));
    }

    public boolean isPlaced(Block block) {
        return marks(block.getChunk()).positions.contains(pack(block));
    }

    public void mark(Block block) {
        Marks m = marks(block.getChunk());
        if (m.positions.add(pack(block))) {
            m.dirty = true;
        }
    }

    public void unmark(Block block) {
        Chunk chunk = block.getChunk();
        ChunkId id = new ChunkId(chunk.getWorld().getUID(), chunk.getChunkKey());
        Marks m = cache.get(id);
        if (m == null && !chunk.getPersistentDataContainer().has(key, PersistentDataType.INTEGER_ARRAY)) {
            return;
        }
        m = marks(chunk);
        if (m.positions.remove(pack(block))) {
            m.dirty = true;
        }
    }

    // ------------------------------------------------------------------ listeners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (tables.isTracked(event.getBlockPlaced().getType())) {
            mark(event.getBlockPlaced());
        }
    }

    /** Cobblestone/stone/basalt generators create infinite "natural" blocks; treat them as placed. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (GENERATED_STONE.contains(event.getNewState().getType())) {
            mark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (tables.isTracked(event.getTo())) {
            mark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        markMoved(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        markMoved(event.getBlocks(), event.getDirection());
    }

    /** Anything moved by a piston is artificial. Both axis neighbours are marked to be direction-agnostic. */
    private void markMoved(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            if (tables.isTracked(block.getType())) {
                mark(block.getRelative(direction));
                mark(block.getRelative(direction.getOppositeFace()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::unmark);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::unmark);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Marks m = cache.remove(new ChunkId(chunk.getWorld().getUID(), chunk.getChunkKey()));
        if (m != null && m.dirty) {
            write(chunk.getPersistentDataContainer(), m);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldSave(WorldSaveEvent event) {
        saveWorld(event.getWorld());
    }

    public void saveAll(Iterable<World> worlds) {
        for (World world : worlds) {
            saveWorld(world);
        }
    }

    private void saveWorld(World world) {
        UUID uid = world.getUID();
        Iterator<Map.Entry<ChunkId, Marks>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkId, Marks> entry = it.next();
            if (!entry.getKey().world().equals(uid) || !entry.getValue().dirty) {
                continue;
            }
            long chunkKey = entry.getKey().key();
            int x = (int) chunkKey;
            int z = (int) (chunkKey >> 32);
            if (world.isChunkLoaded(x, z)) {
                write(world.getChunkAt(x, z).getPersistentDataContainer(), entry.getValue());
            }
        }
    }

    private void write(PersistentDataContainer pdc, Marks marks) {
        if (marks.positions.isEmpty()) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, marks.positions.toIntArray());
        }
        marks.dirty = false;
    }
}
