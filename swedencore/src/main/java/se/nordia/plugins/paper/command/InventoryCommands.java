package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.Bukkit;
import se.nordia.plugins.paper.Permissions;

import java.util.List;
import java.util.Locale;

/**
 * Items and inventories: portable crafting tables ({@code /workbench /anvil /grindstone /loom /cartographytable /smithingtable
 * /stonecutter /disposal}), {@code /enderchest /invsee /item /skull /itemname /itemlore}. Names and lore are plain text.
 */
public final class InventoryCommands {

    private static final int MAX_ITEMS = 2_304;

    private final EssentialsKit kit;

    public InventoryCommands(EssentialsKit kit) {
        this.kit = kit;
    }

    public void register(Commands commands) {
        menu(commands, "workbench", p -> MenuType.CRAFTING.builder().checkReachable(false).build(p), "Arbetsbänk / Workbench", List.of("craft", "wb"));
        menu(commands, "anvil", p -> MenuType.ANVIL.builder().checkReachable(false).build(p), "Städ / Anvil", List.of());
        menu(commands, "grindstone", p -> MenuType.GRINDSTONE.builder().checkReachable(false).build(p), "Slipsten / Grindstone", List.of());
        menu(commands, "loom", p -> MenuType.LOOM.builder().checkReachable(false).build(p), "Vävstol / Loom", List.of());
        menu(commands, "cartographytable", p -> MenuType.CARTOGRAPHY_TABLE.builder().checkReachable(false).build(p), "Kartbord / Cartography table", List.of("carttable"));
        menu(commands, "smithingtable", p -> MenuType.SMITHING.builder().checkReachable(false).build(p), "Smidesbord / Smithing table", List.of("smithtable"));
        menu(commands, "stonecutter", p -> MenuType.STONECUTTER.builder().checkReachable(false).build(p), "Stenskärare / Stonecutter", List.of());

        commands.register(EssentialsKit.literal("disposal", "disposal").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                p.openInventory(Bukkit.createInventory(null, 36, kit.messages().render(p, "ess.disposal_title")));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Papperskorg / Trash can", List.of("trash"));

        commands.register(EssentialsKit.literal("enderchest", "enderchest")
                .executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        p.openInventory(p.getEnderChest());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(EssentialsKit.playerArg().requires(CommandServices.permission(Permissions.essOthers("enderchest")))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                Player target = kit.online(p, StringArgumentType.getString(c, "player"));
                                if (target != null) {
                                    p.openInventory(target.getEnderChest());
                                    kit.log(p.getName() + " opened the ender chest of " + target.getName());
                                }
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .build(), "Öppna enderkista / Open an ender chest", List.of("echest", "ec"));

        commands.register(EssentialsKit.literal("invsee", "invsee")
                .then(EssentialsKit.playerArg().executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        Player target = kit.online(p, StringArgumentType.getString(c, "player"));
                        if (target != null) {
                            p.openInventory(target.getInventory());
                            kit.log(p.getName() + " opened the inventory of " + target.getName());
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Titta i ett förråd / Look in an inventory", List.of());

        commands.register(EssentialsKit.literal("item", "item")
                .then(Commands.argument("material", StringArgumentType.word()).suggests((c, b) -> {
                            String rest = b.getRemainingLowerCase();
                            for (Material m : Material.values()) {
                                if (m.isItem() && !m.isLegacy() && m.name().toLowerCase(Locale.ROOT).startsWith(rest)) {
                                    b.suggest(m.name().toLowerCase(Locale.ROOT));
                                }
                            }
                            return b.buildFuture();
                        })
                        .executes(c -> give(c.getSource().getSender(), StringArgumentType.getString(c, "material"), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_ITEMS)).executes(c ->
                                give(c.getSource().getSender(), StringArgumentType.getString(c, "material"),
                                        IntegerArgumentType.getInteger(c, "count")))))
                .build(), "Ge dig ett föremål / Give yourself an item", List.of("i"));

        commands.register(EssentialsKit.literal("skull", "skull")
                .executes(c -> skull(c.getSource().getSender(), null))
                .then(EssentialsKit.playerArg().executes(c -> skull(c.getSource().getSender(), StringArgumentType.getString(c, "player"))))
                .build(), "Spelarhuvud / Player head", List.of());

        commands.register(EssentialsKit.literal("itemname", "itemname")
                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        edit(p, meta -> meta.displayName(plain(StringArgumentType.getString(c, "name"))), "ess.item_named");
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Döp föremålet / Rename the held item", List.of("iname"));

        commands.register(EssentialsKit.literal("itemlore", "itemlore")
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        edit(p, meta -> meta.lore(List.of(plain(StringArgumentType.getString(c, "text")))), "ess.item_lore");
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Beskrivning på föremålet / Set item lore", List.of("lore"));
    }

    private void menu(Commands commands, String name, java.util.function.Function<Player, org.bukkit.inventory.InventoryView> open, String description, List<String> aliases) {
        commands.register(EssentialsKit.literal(name, name).executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                p.openInventory(open.apply(p));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), description, aliases);
    }

    private int give(CommandSender sender, String rawMaterial, int count) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        Material material = Material.matchMaterial(rawMaterial);
        if (material == null || !material.isItem() || material.isAir()) {
            kit.messages().send(player, "ess.bad_value", "value", rawMaterial);
            return Command.SINGLE_SUCCESS;
        }
        int left = count;
        while (left > 0) {
            int stack = Math.min(left, material.getMaxStackSize());
            player.getInventory().addItem(new ItemStack(material, stack))
                    .values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            left -= stack;
        }
        kit.messages().send(player, "ess.item_given", "count", count, "item", material.name().toLowerCase(Locale.ROOT));
        kit.log(player.getName() + " spawned " + count + " " + material);
        return Command.SINGLE_SUCCESS;
    }

    private int skull(CommandSender sender, String owner) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        String name = owner == null ? player.getName() : owner;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            if (target != null) {
                meta.setOwningPlayer(target);
            } else if (owner == null) {
                meta.setOwningPlayer(player);
            } else {
                kit.messages().send(player, "error.player.unknown");
                return Command.SINGLE_SUCCESS;
            }
            head.setItemMeta(meta);
        }
        player.getInventory().addItem(head).values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        kit.messages().send(player, "ess.skull_given", "player", name);
        return Command.SINGLE_SUCCESS;
    }

    private void edit(Player player, java.util.function.Consumer<ItemMeta> change, String doneKey) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            kit.messages().send(player, "ess.no_item");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        change.accept(meta);
        item.setItemMeta(meta);
        kit.messages().send(player, doneKey);
    }

    /** Admin-typed text becomes plain, non-italic text; it is never parsed as MiniMessage. */
    private static Component plain(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }
}
