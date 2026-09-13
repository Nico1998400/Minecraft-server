package se.nordia.swedencore.paper.properties;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.paper.text.Messages;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces property and city-land protection.
 *
 * <p>Runs at LOW priority so later listeners (skill XP at MONITOR with ignoreCancelled) never reward blocked actions.
 * Environmental griefing is handled too: explosions, fire, liquids and pistons may not cross protection boundaries.
 */
public final class ProtectionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MILLIS = 2_000;

    private final ProtectionIndex index;
    private final Messages messages;
    private final boolean protectCityLand;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public ProtectionListener(ProtectionIndex index, Messages messages, boolean protectCityLand) {
        this.index = index;
        this.messages = messages;
        this.protectCityLand = protectCityLand;
    }

    private boolean deny(Player player, Block block) {
        if (player.hasPermission(Permissions.PROPERTY_BYPASS)) {
            return false;
        }
        ProtectionIndex.Decision decision = index.canBuild(player.getUniqueId(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), protectCityLand);
        if (decision == ProtectionIndex.Decision.ALLOWED) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last == null || now - last > MESSAGE_COOLDOWN_MILLIS) {
            lastMessage.put(player.getUniqueId(), now);
            player.sendActionBar(messages.render(player, "protection." + decision.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return true;
    }

    private void check(Cancellable event, Player player, Block block) {
        if (deny(player, block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        check(event, event.getPlayer(), event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        check(event, event.getPlayer(), event.getBlock());
    }

    /**
     * Containers, doors, levers, beds, crop trampling … inside someone else's property.
     * {@code isInteractable} is deprecated for being too broad (e.g. stairs); over-protecting is the safe side here.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        boolean relevant = event.getAction() == Action.PHYSICAL
                || (event.getAction() == Action.RIGHT_CLICK_BLOCK && block.getType().isInteractable());
        if (relevant && deny(event.getPlayer(), block)) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (event.getAction() == Action.PHYSICAL) {
                event.setCancelled(true);
            }
        }
    }

    /** Item frames, armor stands and other entities placed in a property. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Location loc = event.getRightClicked().getLocation();
        if (event.getRightClicked() instanceof Player) {
            return;
        }
        check(event, event.getPlayer(), loc.getBlock());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            check(event, player, event.getEntity().getLocation().getBlock());
        } else if (isProtected(event.getEntity().getLocation().getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            check(event, event.getPlayer(), event.getBlock());
        } else if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD && isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /** Liquids may not flow into a different protection area. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (areaKey(event.getBlock()) != areaKey(event.getToBlock()) && areaKey(event.getToBlock()) > 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesBoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesBoundary(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    private boolean crossesBoundary(Block piston, List<Block> moved, BlockFace direction) {
        long home = areaKey(piston);
        for (Block block : moved) {
            if (areaKey(block) != home || areaKey(block.getRelative(direction)) != home
                    || areaKey(block.getRelative(direction.getOppositeFace())) != home) {
                return true;
            }
        }
        return false;
    }

    /** True for blocks inside any property, or city land when it is protected (environmental damage protection). */
    private boolean isProtected(Block block) {
        long key = areaKey(block);
        return key > 0 || (key < 0 && protectCityLand);
    }

    private long areaKey(Block block) {
        return index.areaKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
