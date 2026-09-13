package se.nordia.swedencore.paper.skills;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import se.nordia.swedencore.skills.Skill;

/**
 * XP for Mining, Forestry, Farming, Herbalism and Fishing.
 *
 * <p>Listeners run at MONITOR with ignoreCancelled, so actions blocked by protection (e.g. someone else's property)
 * never give XP.
 */
public final class GatheringListener implements Listener {

    private final SkillTracker tracker;
    private final PlacedBlockTracker placed;
    private final XpTables tables;

    public GatheringListener(SkillTracker tracker, PlacedBlockTracker placed, XpTables tables) {
        this.tracker = tracker;
        this.placed = placed;
        this.tables = tables;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        boolean wasPlaced = tables.isTracked(type) && placed.isPlaced(block);
        if (tables.isTracked(type)) {
            placed.unmark(block);
        }
        Player player = event.getPlayer();
        BlockData data = block.getBlockData();

        boolean growthCrop = XpTables.GROWTH_CROPS.contains(type);
        if (growthCrop && !(data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge())) {
            return;
        }
        if (wasPlaced) {
            return;
        }
        Integer farming = tables.farming(type);
        if (farming != null) {
            tracker.award(player, Skill.FARMING, farming, false);
            return;
        }
        Integer herbalism = tables.herbalism(type);
        if (herbalism != null) {
            tracker.award(player, Skill.HERBALISM, herbalism, false);
            return;
        }
        Integer mining = tables.mining(type);
        if (mining != null) {
            if (block.isPreferredTool(player.getInventory().getItemInMainHand())) {
                tracker.award(player, Skill.MINING, mining, false);
            }
            return;
        }
        Integer forestry = tables.forestry(type);
        if (forestry != null) {
            tracker.award(player, Skill.FORESTRY, forestry, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        Integer xp = tables.herbalismHarvest(event.getHarvestedBlock().getType());
        if (xp != null && !event.getItemsHarvested().isEmpty()) {
            tracker.award(event.getPlayer(), Skill.HERBALISM, xp, false);
        }
    }

    /** Fishing requires recent real input: AFK fish farms are the classic exploit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        tracker.award(event.getPlayer(), Skill.FISHING, tables.fishing(item.getItemStack().getType()), true);
    }
}
