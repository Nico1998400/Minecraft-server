package se.nordia.plugins.paper.gui;

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
import org.bukkit.inventory.meta.SkullMeta;
import se.nordia.plugins.paper.scheduler.Tasks;
import se.nordia.plugins.paper.text.Messages;

import java.util.List;
import java.util.Map;

/**
 * The main menu ({@code /meny}): one button per game system. A button closes the menu and runs the same command a player
 * could type, with the player's own permissions, so the menu can never do more than the commands. Every click in the
 * menu window is cancelled; nothing can be taken out of or put into it.
 */
public final class MainMenu implements Listener {

    private static final int SIZE = 45;
    private static final int INTRO_SLOT = 4;
    private static final int CLOSE_SLOT = 40;

    /** What a button shows and does: {@code key} names the language keys {@code menu.<key>.name} and {@code .desc}. */
    private record Button(int slot, String key, Material icon, String command) {
    }

    private static final List<Button> BUTTONS = List.of(
            new Button(10, "profile", Material.PLAYER_HEAD, "profile"),
            new Button(11, "balance", Material.GOLD_INGOT, "balance"),
            new Button(12, "skills", Material.EXPERIENCE_BOTTLE, "skills"),
            new Button(13, "jobs", Material.IRON_PICKAXE, "jobs"),
            new Button(14, "contracts", Material.WRITABLE_BOOK, "contracts"),
            new Button(15, "stash", Material.CHEST, "stash"),
            new Button(16, "language", Material.NAME_TAG, "language"),
            new Button(19, "company", Material.LECTERN, "company"),
            new Button(20, "production", Material.BLAST_FURNACE, "production"),
            new Button(21, "orders", Material.PAPER, "orders"),
            new Button(22, "market", Material.EMERALD, "market"),
            new Button(23, "transports", Material.CHEST_MINECART, "transports"),
            new Button(24, "property", Material.OAK_DOOR, "property"),
            new Button(25, "settlement", Material.RED_BANNER, "settlement"),
            new Button(29, "shares", Material.AMETHYST_SHARD, "shares"),
            new Button(31, "loan", Material.GOLD_NUGGET, "loan"),
            new Button(33, "spawn", Material.COMPASS, "spawn"),
            new Button(35, "texturepack", Material.PAINTING, "texturer"));

    private record Holder() implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("The menu inventory is looked up from the view");
        }
    }

    private final Messages messages;
    private final Tasks tasks;

    public MainMenu(Messages messages, Tasks tasks) {
        this.messages = messages;
        this.tasks = tasks;
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(new Holder(), SIZE, messages.render(player, "menu.title"));
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, pane);
        }
        inventory.setItem(INTRO_SLOT, button(player, "intro", Material.KNOWLEDGE_BOOK));
        for (Button button : BUTTONS) {
            ItemStack stack = button(player, button.key(), button.icon());
            if (stack.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(player);
                stack.setItemMeta(skull);
            }
            inventory.setItem(button.slot(), stack);
        }
        inventory.setItem(CLOSE_SLOT, button(player, "close", Material.BARRIER));
        player.openInventory(inventory);
    }

    private ItemStack button(Player player, String key, Material icon) {
        Component name = messages.render(player, "menu." + key + ".name");
        List<Component> lore = List.of(
                messages.render(player, "menu." + key + ".desc"),
                Component.empty(),
                messages.render(player, "menu.hint"));
        return item(icon, name, lore);
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
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == INTRO_SLOT) {
            player.closeInventory();
            for (String line : List.of("menu.intro.header", "menu.intro.money", "menu.intro.work", "menu.intro.companies",
                    "menu.intro.trade", "menu.intro.growth", "menu.intro.footer")) {
                messages.send(player, line);
            }
        } else {
            for (Button button : BUTTONS) {
                if (button.slot() == slot) {
                    player.closeInventory();
                    String command = button.command();
                    tasks.sync(() -> player.performCommand(command));
                    return;
                }
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
