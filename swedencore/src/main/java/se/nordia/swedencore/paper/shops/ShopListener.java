package se.nordia.swedencore.paper.shops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.paper.properties.ProtectionIndex;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.shops.ShopListing;

/**
 * Customers right-click a listed container to see the offer and buy with a click. Owners open it normally to restock.
 * Hoppers may not pull items out of property containers into another area.
 */
public final class ShopListener implements Listener {

    private final NordiaCore core;
    private final ShopIndex shops;
    private final ProtectionIndex protection;
    private final Messages messages;
    private final Tasks tasks;

    public ShopListener(NordiaCore core, ShopIndex shops, ProtectionIndex protection, Messages messages, Tasks tasks) {
        this.core = core;
        this.shops = shops;
        this.protection = protection;
        this.messages = messages;
        this.tasks = tasks;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        ShopListing listing = shops.at(block.getLocation()).orElse(null);
        if (listing == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean staff = player.hasPermission(Permissions.PROPERTY_BYPASS) || protection.canBuild(player.getUniqueId(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), true) == ProtectionIndex.Decision.ALLOWED;
        if (staff && !player.isSneaking()) {
            return; // owners and staff open the container to restock (sneak-click to preview as a customer)
        }
        event.setCancelled(true);
        showOffer(player, listing, block);
    }

    private void showOffer(Player player, ShopListing listing, Block block) {
        SupportedLocale locale = messages.localeOf(player);
        int stock = block.getState() instanceof Container container
                ? ShopTransactions.countMatching(container.getInventory(), ShopTransactions.template(listing)) / listing.bundleSize()
                : 0;
        Material material = Material.matchMaterial(listing.material());
        Component item = material == null ? Component.text(listing.material()) : Component.translatable(material.translationKey());
        messages.send(player, listing.shopOpen() ? "shop.offer" : "shop.offer_closed", "shop", listing.shopName(),
                "item", item, "bundle", listing.bundleSize(), "price", listing.price(), "stock", stock);
        if (!listing.shopOpen() || stock == 0) {
            return;
        }
        Component buttons = Component.empty();
        for (int n : new int[]{1, 4, 16}) {
            if (n > stock || n > core.shops().config().maxBundlesPerPurchase()) {
                break;
            }
            buttons = buttons.append(messages.render(locale, "shop.buy_button", "count", n, "total", listing.price().times(n))
                    .clickEvent(ClickEvent.runCommand("/shop buy " + listing.id() + " " + n))).append(Component.space());
        }
        player.sendMessage(buttons);
    }

    /** Breaking a listed container removes its listing. Protection already ensured only staff can break it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (shops.at(loc).isPresent()) {
            String world = loc.getWorld().getName();
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            tasks.async("remove listing at broken container", () -> core.shops().removeListingAt(world, x, y, z));
        }
    }

    /** Hoppers and hopper minecarts may not move items out of a property container into a different area. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        Location from = event.getSource().getLocation();
        Location to = event.getDestination().getLocation();
        if (from == null || to == null || from.getWorld() == null) {
            return;
        }
        long sourceArea = protection.areaKey(from.getWorld().getName(), from.getBlockX(), from.getBlockY(), from.getBlockZ());
        if (sourceArea <= 0) {
            return;
        }
        long targetArea = to.getWorld() == null ? 0
                : protection.areaKey(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (sourceArea != targetArea) {
            event.setCancelled(true);
        }
    }
}
