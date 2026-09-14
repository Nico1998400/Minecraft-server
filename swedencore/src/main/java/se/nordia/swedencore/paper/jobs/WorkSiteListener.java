package se.nordia.swedencore.paper.jobs;

import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.companies.Employee;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.properties.ProtectionIndex;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.properties.PropertyService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Work sites: blocks broken by an on-duty employee inside a production property owned by their employer drop into the
 * company inventory instead of the employee's pockets. The company pays the wage and owns the output.
 *
 * <p>Drops are buffered per (company, employee) and stored in batches; the database re-checks employment on commit.
 * A crash can lose at most one flush interval of drops, never duplicate them.
 */
public final class WorkSiteListener implements Listener {

    private static final Set<Property.Type> WORK_SITES = Set.of(
            Property.Type.MINE, Property.Type.FARM, Property.Type.FACTORY, Property.Type.INDUSTRIAL_LAND);
    private static final int FLUSH_AT_STACKS = 27;

    private record Key(long companyId, UUID employee) {
    }

    private final NordiaCore core;
    private final WorkTracker work;
    private final ProtectionIndex protection;
    private final Messages messages;
    private final Tasks tasks;
    private final Map<Key, List<ItemStack>> buffer = new HashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    public WorkSiteListener(NordiaCore core, WorkTracker work, ProtectionIndex protection, Messages messages, Tasks tasks) {
        this.core = core;
        this.work = work;
        this.protection = protection;
        this.messages = messages;
        this.tasks = tasks;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Employee employee = work.dutyOf(player.getUniqueId()).orElse(null);
        if (employee == null || event.getItems().isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        PropertyService.Access access = protection.propertyAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .orElse(null);
        if (access == null) {
            return;
        }
        Property property = access.property();
        if (property.occupantType() != Property.OwnerType.COMPANY || !WORK_SITES.contains(property.type())
                || !property.occupantId().equals(Long.toString(employee.companyId()))) {
            return;
        }
        Key key = new Key(employee.companyId(), player.getUniqueId());
        List<ItemStack> stacks = buffer.computeIfAbsent(key, k -> new ArrayList<>());
        for (Item item : event.getItems()) {
            stacks.add(item.getItemStack().clone());
        }
        event.getItems().clear();
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last == null || now - last > 10_000) {
            lastNotice.put(player.getUniqueId(), now);
            player.sendActionBar(messages.render(player, "jobs.work_site", "company", employee.companyName()));
        }
        if (stacks.size() >= FLUSH_AT_STACKS) {
            flush(key);
        }
    }

    public void flushAll() {
        for (Key key : List.copyOf(buffer.keySet())) {
            flush(key);
        }
    }

    private void flush(Key key) {
        List<ItemStack> stacks = buffer.remove(key);
        if (stacks == null || stacks.isEmpty()) {
            return;
        }
        List<ItemStashService.StashItem> items = ItemTransfer.toStash(stacks);
        tasks.async(() -> {
            core.stash().depositWorkOutput(key.companyId(), key.employee(), items);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                core.logger().log(Level.SEVERE, "[AUDIT] Failed to store work output for company " + key.companyId()
                        + " by " + key.employee() + " (" + stacks.size() + " stacks)", Tasks.unwrap(error));
            }
        });
    }

    /** Blocking flush during shutdown. */
    public void flushAllBlocking() {
        for (Map.Entry<Key, List<ItemStack>> entry : buffer.entrySet()) {
            try {
                core.stash().depositWorkOutput(entry.getKey().companyId(), entry.getKey().employee(), ItemTransfer.toStash(entry.getValue()));
            } catch (RuntimeException e) {
                core.logger().log(Level.SEVERE, "[AUDIT] Failed to store work output on shutdown for " + entry.getKey(), e);
            }
        }
        buffer.clear();
    }
}
