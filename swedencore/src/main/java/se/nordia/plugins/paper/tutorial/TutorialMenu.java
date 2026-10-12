package se.nordia.plugins.paper.tutorial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import se.nordia.plugins.paper.text.Messages;

import java.util.List;

/**
 * The tutorial menu: one button per topic. Clicking a topic closes the menu and writes the explanation in chat (with a link
 * back). All texts live in the language files ({@code tutorial.<topic>.name / .desc / .text}), so they are easy to edit.
 * Every click in the window is cancelled; nothing can be taken out of or put into it.
 */
public final class TutorialMenu implements Listener {

    private static final int SIZE = 36;
    private static final int CLOSE_SLOT = 31;
    private static final int MAIN_SLOT = 29;

    /** A topic: {@code key} names the language keys, {@code slot} is where its button sits. */
    private record Topic(int slot, String key, Material icon) {
    }

    private static final List<Topic> TOPICS = List.of(
            new Topic(11, "welcome", Material.KNOWLEDGE_BOOK),
            new Topic(12, "money", Material.GOLD_INGOT),
            new Topic(13, "jobs", Material.IRON_PICKAXE),
            new Topic(14, "companies", Material.LECTERN),
            new Topic(15, "contracts", Material.WRITABLE_BOOK),
            new Topic(20, "trade", Material.EMERALD),
            new Topic(21, "property", Material.OAK_DOOR),
            new Topic(22, "skills", Material.EXPERIENCE_BOTTLE),
            new Topic(23, "travel", Material.COMPASS),
            new Topic(24, "help", Material.BELL));

    private record Holder() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("The menu inventory is looked up from the view");
        }
    }

    private final Messages messages;
    private final java.util.function.Consumer<Player> openMainMenu;

    /** @param openMainMenu what the "All functions" button does (opens the main action menu) */
    public TutorialMenu(Messages messages, java.util.function.Consumer<Player> openMainMenu) {
        this.openMainMenu = openMainMenu;
        this.messages = messages;
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE, messages.render(player, "tutorial.title"));
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, pane);
        }
        for (Topic topic : TOPICS) {
            inventory.setItem(topic.slot(), item(topic.icon(), messages.render(player, "tutorial." + topic.key() + ".name"),
                    List.of(messages.render(player, "tutorial." + topic.key() + ".desc"), Component.empty(),
                            messages.render(player, "tutorial.hint"))));
        }
        inventory.setItem(MAIN_SLOT, item(Material.NETHER_STAR, messages.render(player, "tutorial.main.name"),
                List.of(messages.render(player, "tutorial.main.desc"), Component.empty(), messages.render(player, "tutorial.hint"))));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, messages.render(player, "menu.close.name"),
                List.of(messages.render(player, "menu.close.desc"))));
        player.openInventory(inventory);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == MAIN_SLOT) {
            player.closeInventory();
            openMainMenu.accept(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        for (Topic topic : TOPICS) {
            if (topic.slot() == slot) {
                player.closeInventory();
                messages.send(player, "tutorial.divider");
                messages.send(player, "tutorial." + topic.key() + ".text");
                messages.send(player, "tutorial.back");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
