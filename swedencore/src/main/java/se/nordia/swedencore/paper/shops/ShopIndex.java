package se.nordia.swedencore.paper.shops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.events.DomainEvent;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.shops.ShopListing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Listings by block location plus their floating labels (TextDisplay entities tagged with the listing id).
 *
 * <p>Reloaded wholesale on shop/property changes — listings change rarely compared with how often they are read.
 * Main-thread confined.
 */
public final class ShopIndex implements Listener {

    public record BlockKey(String world, int x, int y, int z) {
        public static BlockKey of(Location loc) {
            return new BlockKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private final NordiaCore core;
    private final Tasks tasks;
    private final NamespacedKey labelKey;
    private final SupportedLocale labelLocale;
    private Map<BlockKey, ShopListing> byLocation = new HashMap<>();
    private Map<Long, ShopListing> byId = new HashMap<>();
    private boolean reloadQueued;

    public ShopIndex(Plugin plugin, NordiaCore core, Tasks tasks) {
        this.core = core;
        this.tasks = tasks;
        this.labelKey = new NamespacedKey(plugin, "shop_listing");
        this.labelLocale = core.config().defaultLocale();
        core.events().subscribe(event -> {
            if (event instanceof DomainEvent.ShopChanged || event instanceof DomainEvent.PropertyChanged) {
                tasks.sync(this::queueReload);
            }
        });
    }

    public Optional<ShopListing> at(Location location) {
        return Optional.ofNullable(byLocation.get(BlockKey.of(location)));
    }

    public Optional<ShopListing> byId(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Coalesces bursts of events into one reload. */
    public void queueReload() {
        if (reloadQueued) {
            return;
        }
        reloadQueued = true;
        tasks.async(() -> core.shops().allListings()).whenComplete((listings, error) -> tasks.sync(() -> {
            reloadQueued = false;
            if (error != null) {
                core.logger().log(Level.SEVERE, "Failed to load shop listings", Tasks.unwrap(error));
                return;
            }
            apply(listings);
        }));
    }

    private void apply(List<ShopListing> listings) {
        Map<BlockKey, ShopListing> location = new HashMap<>();
        Map<Long, ShopListing> ids = new HashMap<>();
        for (ShopListing listing : listings) {
            location.put(new BlockKey(listing.world(), listing.x(), listing.y(), listing.z()), listing);
            ids.put(listing.id(), listing);
        }
        Map<Long, ShopListing> previous = byId;
        byLocation = location;
        byId = ids;
        for (ShopListing old : previous.values()) {
            if (!ids.containsKey(old.id())) {
                removeLabel(old);
            }
        }
        for (ShopListing listing : listings) {
            syncLabel(listing);
        }
    }

    // ------------------------------------------------------------------ labels

    private Location labelLocation(ShopListing listing) {
        World world = Bukkit.getWorld(listing.world());
        return world == null ? null : new Location(world, listing.x() + 0.5, listing.y() + 1.25, listing.z() + 0.5);
    }

    private Component labelText(ShopListing listing) {
        Material material = Material.matchMaterial(listing.material());
        Component item = material == null ? Component.text(listing.material()) : Component.translatable(material.translationKey());
        Component top = item.color(NamedTextColor.WHITE).append(Component.text(" ×" + listing.bundleSize(), NamedTextColor.GRAY));
        Component price = Component.text(listing.price().format(labelLocale.javaLocale()),
                listing.shopOpen() ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY);
        return top.appendNewline().append(price);
    }

    private void syncLabel(ShopListing listing) {
        Location loc = labelLocation(listing);
        if (loc == null || !loc.getWorld().isChunkLoaded(listing.x() >> 4, listing.z() >> 4)) {
            return;
        }
        TextDisplay existing = findLabel(loc, listing.id());
        if (existing != null) {
            existing.text(labelText(listing));
            return;
        }
        loc.getWorld().spawn(loc, TextDisplay.class, display -> {
            display.text(labelText(listing));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(true);
            display.getPersistentDataContainer().set(labelKey, PersistentDataType.LONG, listing.id());
        });
    }

    private void removeLabel(ShopListing listing) {
        Location loc = labelLocation(listing);
        if (loc != null && loc.getWorld().isChunkLoaded(listing.x() >> 4, listing.z() >> 4)) {
            TextDisplay label = findLabel(loc, listing.id());
            if (label != null) {
                label.remove();
            }
        }
    }

    private TextDisplay findLabel(Location loc, long listingId) {
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, 0.6, 0.6, 0.6, e -> e instanceof TextDisplay)) {
            Long tag = entity.getPersistentDataContainer().get(labelKey, PersistentDataType.LONG);
            if (tag != null && tag == listingId) {
                return (TextDisplay) entity;
            }
        }
        return null;
    }

    /** Labels persist with chunks; clean up labels of listings removed while the chunk was unloaded. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            Long tag = display.getPersistentDataContainer().get(labelKey, PersistentDataType.LONG);
            if (tag == null) {
                continue;
            }
            ShopListing listing = byId.get(tag);
            if (listing == null) {
                display.remove();
            } else {
                display.text(labelText(listing));
            }
        }
    }
}
