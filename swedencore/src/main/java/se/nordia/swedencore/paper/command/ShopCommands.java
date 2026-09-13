package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.properties.ProtectionIndex;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.shops.ShopIndex;
import se.nordia.swedencore.paper.shops.ShopTransactions;
import se.nordia.swedencore.properties.Property;
import se.nordia.swedencore.shops.Shop;
import se.nordia.swedencore.shops.ShopListing;
import se.nordia.swedencore.shops.ShopService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/** {@code /shop} (alias {@code /butik}) for owners and customers, {@code /shops find} (alias {@code /butiker}). */
public final class ShopCommands {

    private static final double MAX_BUY_DISTANCE = 6.0;

    private final CommandServices svc;
    private final ShopIndex index;
    private final ProtectionIndex protection;

    public ShopCommands(CommandServices svc, ShopIndex index, ProtectionIndex protection) {
        this.svc = svc;
        this.index = index;
        this.protection = protection;
    }

    public void register(Commands commands) {
        commands.register(shop(), "Butiker / Shops", List.of("butik"));
        commands.register(Commands.literal("shops")
                .then(Commands.literal("find").then(Commands.argument("item", StringArgumentType.word()).executes(this::find)))
                .build(), "Hitta butiker / Find shops", List.of("butiker"));
    }

    private ShopService shops() {
        return svc.core().shops();
    }

    private LiteralCommandNode<CommandSourceStack> shop() {
        return Commands.literal("shop")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "shop.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::create)))
                .then(Commands.literal("info").executes(this::info))
                .then(Commands.literal("open").executes(c -> setOpen(c, true)))
                .then(Commands.literal("close").executes(c -> setOpen(c, false)))
                .then(Commands.literal("sell").then(Commands.argument("price", StringArgumentType.word())
                        .executes(c -> sell(c, 0))
                        .then(Commands.argument("bundle", IntegerArgumentType.integer(1, 64))
                                .executes(c -> sell(c, IntegerArgumentType.getInteger(c, "bundle"))))))
                .then(Commands.literal("unlist").executes(this::unlist))
                .then(Commands.literal("buy").then(Commands.argument("listing", LongArgumentType.longArg(1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(this::buy))))
                .build();
    }

    // ------------------------------------------------------------------ owner commands

    private Long propertyHere(Player player) {
        Location loc = player.getLocation();
        return protection.propertyAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())
                .map(a -> a.property().id()).orElse(null);
    }

    private int create(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Long propertyId = propertyHere(player);
        if (propertyId == null) {
            svc.messages().send(player, "shop.stand_in_property");
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String name = StringArgumentType.getString(c, "name");
        svc.tasks().run(player, () -> shops().create(uuid, propertyId, name),
                shop -> svc.messages().send(player, "shop.created", "name", shop.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Long propertyId = propertyHere(player);
        if (propertyId == null) {
            svc.messages().send(player, "shop.stand_in_property");
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(player, () -> {
            Shop shop = shops().atProperty(propertyId).orElseThrow(() -> new DomainException("shop.not_found"));
            return new ShopInfo(shop, shops().listingsOf(shop.id()).size(), shops().salesSummary(shop.id()));
        }, r -> {
            svc.messages().send(player, "shop.info.header", "name", r.shop().name(),
                    "status", svc.messages().render(player, r.shop().open() ? "shop.status.open" : "shop.status.closed"));
            svc.messages().send(player, "shop.info.details", "owner", r.shop().ownerName(), "listings", r.listings(),
                    "sales", r.summary().sales(), "revenue", r.summary().revenue());
        });
        return Command.SINGLE_SUCCESS;
    }

    private record ShopInfo(Shop shop, int listings, ShopService.SalesSummary summary) {
    }

    private int setOpen(CommandContext<CommandSourceStack> c, boolean open) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Long propertyId = propertyHere(player);
        if (propertyId == null) {
            svc.messages().send(player, "shop.stand_in_property");
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Shop shop = shops().atProperty(propertyId).orElseThrow(() -> new DomainException("shop.not_found"));
            return shops().setOpen(uuid, shop.id(), open);
        }, shop -> svc.messages().send(player, open ? "shop.opened" : "shop.closed", "name", shop.name()));
        return Command.SINGLE_SUCCESS;
    }

    /** Look at a container, hold the item to sell: {@code /shop sell <price per bundle> [bundle size]}. */
    private int sell(CommandContext<CommandSourceStack> c, int bundleArg) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Block target = player.getTargetBlockExact(5);
        if (target == null || !(target.getState() instanceof Container)) {
            svc.messages().send(player, "shop.look_at_container");
            return Command.SINGLE_SUCCESS;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            svc.messages().send(player, "shop.hold_item");
            return Command.SINGLE_SUCCESS;
        }
        var access = protection.propertyAt(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
        if (access.isEmpty()) {
            svc.messages().send(player, "shop.stand_in_property");
            return Command.SINGLE_SUCCESS;
        }
        int bundle = bundleArg > 0 ? bundleArg : Math.min(64, held.getAmount());
        ItemStack template = held.clone();
        template.setAmount(1);
        byte[] item = template.serializeAsBytes();
        String material = template.getType().name();
        String priceInput = StringArgumentType.getString(c, "price");
        long propertyId = access.get().property().id();
        String world = target.getWorld().getName();
        int x = target.getX();
        int y = target.getY();
        int z = target.getZ();
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Money price = Money.parsePositive(priceInput);
            Shop shop = shops().atProperty(propertyId).orElseThrow(() -> new DomainException("shop.not_found"));
            return shops().list(uuid, shop.id(), world, x, y, z, material, item, bundle, price);
        }, listing -> svc.messages().send(player, "shop.listed", "item", itemName(material), "bundle", listing.bundleSize(),
                "price", listing.price()));
        return Command.SINGLE_SUCCESS;
    }

    private int unlist(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Block target = player.getTargetBlockExact(5);
        ShopListing listing = target == null ? null : index.at(target.getLocation()).orElse(null);
        if (listing == null) {
            svc.messages().send(player, "shop.look_at_listing");
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            shops().unlist(uuid, listing.id());
            return listing;
        }, l -> svc.messages().send(player, "shop.unlisted"));
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ customers

    /**
     * Buy at the container: take stock (main thread) → charge and record (async, unique token) → hand over, or put the
     * stock back. Ambiguous failures are resolved by checking whether the sale was recorded.
     */
    private int buy(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        long listingId = LongArgumentType.getLong(c, "listing");
        int bundles = IntegerArgumentType.getInteger(c, "count");
        ShopListing listing = index.byId(listingId).orElse(null);
        if (listing == null) {
            svc.messages().sendError(player, new DomainException("shop.listing_not_found"));
            return Command.SINGLE_SUCCESS;
        }
        Location chestLoc = new Location(player.getWorld(), listing.x() + 0.5, listing.y() + 0.5, listing.z() + 0.5);
        if (!player.getWorld().getName().equals(listing.world()) || player.getLocation().distance(chestLoc) > MAX_BUY_DISTANCE) {
            svc.messages().send(player, "shop.too_far");
            return Command.SINGLE_SUCCESS;
        }
        Block block = chestLoc.getBlock();
        if (!(block.getState() instanceof Container container)) {
            svc.messages().sendError(player, new DomainException("shop.listing_not_found"));
            return Command.SINGLE_SUCCESS;
        }
        if (bundles > shops().config().maxBundlesPerPurchase()) {
            svc.messages().sendError(player, DomainException.of("shop.invalid_quantity", "max", shops().config().maxBundlesPerPurchase()));
            return Command.SINGLE_SUCCESS;
        }
        Inventory inventory = container.getInventory();
        ItemStack template = ShopTransactions.template(listing);
        int items = bundles * listing.bundleSize();
        List<ItemStack> taken = ShopTransactions.take(inventory, template, items);
        if (taken.isEmpty()) {
            svc.messages().send(player, "shop.out_of_stock");
            return Command.SINGLE_SUCCESS;
        }
        UUID buyer = player.getUniqueId();
        UUID token = UUID.randomUUID();
        svc.tasks().async(() -> shops().recordPurchase(buyer, listingId, bundles, listing.price(), token)).whenComplete((receipt, error) -> {
            boolean sold = error == null;
            Throwable cause = error == null ? null : Tasks.unwrap(error);
            if (!sold && !(cause instanceof DomainException)) {
                try {
                    sold = shops().saleRecorded(token);
                } catch (RuntimeException checkFailed) {
                    // Database unreachable: never hand out possibly unpaid goods. Stock goes back; if the sale did
                    // commit, the ledger (token below) lets an administrator refund the buyer.
                    svc.core().logger().log(Level.SEVERE, "[AUDIT] Shop sale outcome unknown, token " + token + ", buyer "
                            + buyer + ", listing " + listingId + "; stock returned to container", checkFailed);
                }
            }
            final boolean completed = sold;
            svc.tasks().sync(() -> {
                if (completed) {
                    if (player.isOnline()) {
                        ItemTransfer.giveOrDrop(player, taken);
                        svc.messages().send(player, "shop.bought", "count", items, "item", itemName(listing.material()),
                                "total", receipt != null ? receipt.total() : listing.price().times(bundles), "shop", listing.shopName());
                    } else {
                        svc.tasks().async("deliver shop purchase to stash", () -> svc.core().stash().deposit(
                                ItemStashService.Owner.player(buyer), ItemTransfer.toStash(taken), "SHOP_PURCHASE", token.toString()));
                    }
                    return;
                }
                returnStock(block, taken, listing);
                if (player.isOnline()) {
                    if (cause instanceof DomainException domain) {
                        svc.messages().sendError(player, domain);
                    } else {
                        svc.core().logger().log(Level.SEVERE, "Shop purchase failed for listing " + listingId, cause);
                        svc.messages().send(player, "error.internal");
                    }
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Puts stock back into the container; anything that no longer fits goes to the shop owner's stash. */
    private void returnStock(Block block, List<ItemStack> stock, ShopListing listing) {
        List<ItemStack> leftovers = new java.util.ArrayList<>(stock);
        if (block.getState() instanceof Container container) {
            leftovers = new java.util.ArrayList<>(container.getInventory().addItem(stock.toArray(ItemStack[]::new)).values());
        }
        if (leftovers.isEmpty()) {
            return;
        }
        List<ItemStashService.StashItem> items = ItemTransfer.toStash(leftovers);
        svc.tasks().async("return shop stock to owner stash", () -> {
            Shop shop = shops().find(listing.shopId()).orElseThrow();
            ItemStashService.Owner owner = shop.ownerType() == Property.OwnerType.COMPANY
                    ? ItemStashService.Owner.company(Long.parseLong(shop.ownerId()))
                    : ItemStashService.Owner.player(UUID.fromString(shop.ownerId()));
            svc.core().stash().deposit(owner, items, "SHOP_RETURN", Long.toString(listing.id()));
        });
    }

    private int find(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Material material = Material.matchMaterial(StringArgumentType.getString(c, "item").toUpperCase(Locale.ROOT));
        if (material == null) {
            svc.messages().sendError(sender, new DomainException("contract.invalid_material"));
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(sender, () -> shops().whereToBuy(material.name(), 10), listings -> {
            if (listings.isEmpty()) {
                svc.messages().send(sender, "shops.find.empty", "item", itemName(material.name()));
                return;
            }
            svc.messages().send(sender, "shops.find.header", "item", itemName(material.name()));
            for (ShopListing l : listings) {
                svc.messages().send(sender, "shops.find.entry", "shop", l.shopName(), "city", l.cityName() == null ? "-" : l.cityName(),
                        "bundle", l.bundleSize(), "price", l.price(), "x", l.x(), "y", l.y(), "z", l.z());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static Component itemName(String material) {
        Material m = Material.matchMaterial(material);
        return m == null ? Component.text(material) : Component.translatable(m.translationKey());
    }
}
