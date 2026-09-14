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
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.orders.BuyOrder;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSession;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/** {@code /orders [item]} board and {@code /order create|fill|cancel|mine} (aliases {@code /bestallningar}, {@code /bestall}). */
public final class OrderCommands {

    private static final int PAGE_SIZE = 8;

    private final CommandServices svc;

    public OrderCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("orders")
                .executes(c -> board(c.getSource().getSender(), null))
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(c -> board(c.getSource().getSender(), StringArgumentType.getString(c, "item"))))
                .build(), "Köporder / Buy orders", List.of("bestallningar"));
        commands.register(order(), "Köporder / Buy orders", List.of("bestall"));
    }

    private LiteralCommandNode<CommandSourceStack> order() {
        return Commands.literal("order")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "order.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("create").then(createBranch(false)).then(Commands.literal("company").then(createBranch(true))))
                .then(Commands.literal("fill").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::fill)))
                .then(Commands.literal("cancel").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::cancel)))
                .then(Commands.literal("mine").executes(this::mine))
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> createBranch(boolean company) {
        return Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 1_000_000))
                        .then(Commands.argument("price", StringArgumentType.word())
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(c -> create(c, company)))));
    }

    private static Component itemName(String material) {
        Material m = Material.matchMaterial(material);
        return m == null ? Component.text(material) : Component.translatable(m.translationKey());
    }

    private int board(CommandSender sender, String itemInput) {
        String material = null;
        if (itemInput != null) {
            Material m = Material.matchMaterial(itemInput.toUpperCase(Locale.ROOT));
            if (m == null) {
                svc.messages().sendError(sender, new DomainException("contract.invalid_material"));
                return Command.SINGLE_SUCCESS;
            }
            material = m.name();
        }
        String filter = material;
        svc.tasks().run(sender, () -> svc.core().orders().board(filter, PAGE_SIZE, 0), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "orders.board.empty");
                return;
            }
            svc.messages().send(sender, "orders.board.header");
            for (BuyOrder o : list) {
                sender.sendMessage(svc.messages().render(sender, "orders.board.entry", "id", o.id(), "issuer", o.issuerName(),
                                "remaining", o.remaining(), "item", itemName(o.material()), "price", o.unitPrice(), "deadline", o.deadline())
                        .clickEvent(ClickEvent.suggestCommand("/order fill " + o.id())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> c, boolean company) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Material material = Material.matchMaterial(StringArgumentType.getString(c, "item").toUpperCase(Locale.ROOT));
        if (material == null || !material.isItem() || material.isAir()) {
            svc.messages().sendError(player, new DomainException("contract.invalid_material"));
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        int quantity = IntegerArgumentType.getInteger(c, "quantity");
        String priceInput = StringArgumentType.getString(c, "price");
        int hours = IntegerArgumentType.getInteger(c, "hours");
        Long selected = svc.sessions().get(uuid).map(PlayerSession::selectedCompanyId).orElse(null);
        svc.tasks().run(player, () -> {
            Long companyId = null;
            if (company) {
                companyId = selected != null ? selected : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER).id();
            }
            return svc.core().orders().create(uuid, companyId, material.name(), quantity, Money.parsePositive(priceInput), hours);
        }, o -> svc.messages().send(player, "order.created", "id", o.id(), "remaining", o.quantity(), "item", itemName(o.material()),
                "price", o.unitPrice(), "budget", o.unitPrice().times(o.quantity())));
        return Command.SINGLE_SUCCESS;
    }

    /** Sells pristine items from the inventory into an order; items are returned if the sale is not recorded. */
    private int fill(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> svc.core().orders().find(id).orElseThrow(() -> new DomainException("order.not_found")), order -> {
            if (order.status() != BuyOrder.Status.OPEN) {
                svc.messages().sendError(player, new DomainException("order.not_open"));
                return;
            }
            Material material = Material.matchMaterial(order.material());
            if (material == null || !player.isOnline()) {
                return;
            }
            List<ItemStack> taken = ItemTransfer.takePristine(player, material, order.remaining());
            if (taken.isEmpty()) {
                svc.messages().send(player, "contract.deliver.no_items", "material", itemName(order.material()));
                return;
            }
            List<ItemStashService.StashItem> stashItems = ItemTransfer.toStash(taken);
            UUID token = UUID.randomUUID();
            svc.tasks().async(() -> svc.core().orders().fill(uuid, id, stashItems, token)).whenComplete((result, error) -> {
                Throwable cause = error == null ? null : Tasks.unwrap(error);
                boolean recorded = error == null;
                if (!recorded && !(cause instanceof DomainException)) {
                    try {
                        recorded = svc.core().orders().fillRecorded(token);
                    } catch (RuntimeException checkFailed) {
                        svc.core().logger().log(Level.SEVERE, "[AUDIT] Order fill outcome unknown, token " + token
                                + "; items kept in seller stash", checkFailed);
                        svc.tasks().async("secure order items", () -> {
                            if (!svc.core().orders().fillRecorded(token)) {
                                svc.core().stash().deposit(ItemStashService.Owner.player(uuid), stashItems, "ORDER_RETURN", token.toString());
                            }
                        });
                        return;
                    }
                }
                final boolean sold = recorded;
                svc.tasks().sync(() -> {
                    if (sold) {
                        if (result != null) {
                            svc.messages().send(player, "order.filled", "amount", result.quantity(), "item", itemName(order.material()),
                                    "payout", result.payout(), "remaining", result.order().remaining());
                        }
                        return;
                    }
                    if (player.isOnline()) {
                        ItemTransfer.giveOrDrop(player, taken);
                    } else {
                        svc.tasks().async("return order items", () ->
                                svc.core().stash().deposit(ItemStashService.Owner.player(uuid), stashItems, "ORDER_RETURN", token.toString()));
                    }
                    if (cause instanceof DomainException domain) {
                        svc.messages().sendError(player, domain);
                    } else {
                        svc.core().logger().log(Level.SEVERE, "Order fill failed for order " + id, cause);
                        svc.messages().send(player, "error.internal");
                    }
                });
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> svc.core().orders().cancel(uuid, id),
                o -> svc.messages().send(player, "order.cancelled", "id", o.id()));
        return Command.SINGLE_SUCCESS;
    }

    private int mine(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().orders().issuedBy(uuid), list -> {
            if (list.isEmpty()) {
                svc.messages().send(player, "order.mine.empty");
                return;
            }
            svc.messages().send(player, "order.mine.header");
            for (BuyOrder o : list) {
                svc.messages().send(player, "orders.board.entry", "id", o.id(), "issuer", o.issuerName(), "remaining", o.remaining(),
                        "item", itemName(o.material()), "price", o.unitPrice(), "deadline", o.deadline());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
