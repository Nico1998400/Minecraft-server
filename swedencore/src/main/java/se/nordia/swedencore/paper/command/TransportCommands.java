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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.logistics.TransportService;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /transports} board and {@code /transport create|pickup|deliver|cancel|mine} (alias {@code /frakt}).
 * Pickup and delivery require standing at the respective point.
 */
public final class TransportCommands {

    private static final double ARRIVAL_RADIUS = 8.0;

    private final CommandServices svc;

    public TransportCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("transports").executes(c -> board(c.getSource().getSender())).build(),
                "Fraktuppdrag / Transport jobs", List.of("frakter"));
        commands.register(node(), "Frakt / Transport", List.of("frakt"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("transport")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "transport.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("create").then(createBranch(false)).then(Commands.literal("company").then(createBranch(true))))
                .then(Commands.literal("pickup").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::pickup)))
                .then(Commands.literal("deliver").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::deliver)))
                .then(Commands.literal("cancel").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::cancel)))
                .then(Commands.literal("mine").executes(this::mine))
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> createBranch(boolean company) {
        return Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 100_000))
                        .then(Commands.argument("reward", StringArgumentType.word())
                                .then(Commands.argument("collateral", StringArgumentType.word())
                                        .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(c -> create(c, company))))))));
    }

    private static Component itemName(String material) {
        Material m = Material.matchMaterial(material);
        return m == null ? Component.text(material) : Component.translatable(m.translationKey());
    }

    private static boolean near(Player player, TransportService.Point point) {
        Location loc = player.getLocation();
        if (!loc.getWorld().getName().equals(point.world())) {
            return false;
        }
        double dx = loc.getX() - point.x();
        double dz = loc.getZ() - point.z();
        return dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS;
    }

    private int board(CommandSender sender) {
        svc.tasks().run(sender, () -> svc.core().transports().open(10), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "transport.board.empty");
                return;
            }
            svc.messages().send(sender, "transport.board.header");
            for (TransportService.Transport t : list) {
                sender.sendMessage(svc.messages().render(sender, "transport.board.entry", "id", t.id(), "issuer", t.issuerName(),
                                "quantity", t.quantity(), "item", itemName(t.material()), "reward", t.reward(), "collateral", t.collateral(),
                                "distance", t.distance(), "px", t.pickup().x(), "pz", t.pickup().z(), "dx", t.destination().x(),
                                "dz", t.destination().z())
                        .clickEvent(ClickEvent.suggestCommand("/transport pickup " + t.id())));
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
        if (material == null || !material.isItem()) {
            svc.messages().sendError(player, new DomainException("contract.invalid_material"));
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        Location loc = player.getLocation();
        TransportService.Point pickup = new TransportService.Point(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        TransportService.Point dest = new TransportService.Point(loc.getWorld().getName(), IntegerArgumentType.getInteger(c, "x"), 0,
                IntegerArgumentType.getInteger(c, "z"));
        int quantity = IntegerArgumentType.getInteger(c, "quantity");
        String rewardInput = StringArgumentType.getString(c, "reward");
        String collateralInput = StringArgumentType.getString(c, "collateral");
        int hours = IntegerArgumentType.getInteger(c, "hours");
        Long selected = svc.sessions().get(uuid).map(PlayerSession::selectedCompanyId).orElse(null);
        svc.tasks().run(player, () -> {
            Long companyId = company ? (selected != null ? selected
                    : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER, CompanyRole.MANAGER).id()) : null;
            Money collateral = collateralInput.equals("0") ? Money.ZERO : Money.parsePositive(collateralInput);
            return svc.core().transports().create(uuid, companyId, material.name(), quantity, Money.parsePositive(rewardInput),
                    collateral, pickup, dest, hours, ItemTransfer.CODEC);
        }, t -> svc.messages().send(player, "transport.created", "id", t.id(), "quantity", t.quantity(), "item", itemName(t.material()),
                "distance", t.distance(), "reward", t.reward()));
        return Command.SINGLE_SUCCESS;
    }

    private int pickup(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> svc.core().transports().find(id).orElseThrow(() -> new DomainException("transport.not_found")), t -> {
            if (!near(player, t.pickup())) {
                svc.messages().send(player, "transport.go_to", "x", t.pickup().x(), "z", t.pickup().z());
                return;
            }
            UUID token = UUID.randomUUID();
            svc.tasks().async(() -> svc.core().transports().pickUp(uuid, id, token)).whenComplete((picked, error) -> {
                Throwable cause = error == null ? null : Tasks.unwrap(error);
                boolean recorded = error == null;
                if (!recorded && !(cause instanceof DomainException)) {
                    try {
                        recorded = svc.core().transports().pickupRecorded(token);
                    } catch (RuntimeException e) {
                        svc.core().logger().log(Level.SEVERE, "[AUDIT] Transport pickup outcome unknown, token " + token, e);
                    }
                }
                final boolean done = recorded;
                svc.tasks().sync(() -> {
                    if (!done) {
                        if (cause instanceof DomainException domain) {
                            svc.messages().sendError(player, domain);
                        } else {
                            svc.messages().send(player, "error.internal");
                        }
                        return;
                    }
                    List<ItemStack> stacks = new ArrayList<>();
                    Material material = Material.valueOf(t.material());
                    int left = t.quantity();
                    while (left > 0) {
                        int size = Math.min(material.getMaxStackSize(), left);
                        stacks.add(ItemStack.of(material, size));
                        left -= size;
                    }
                    if (player.isOnline()) {
                        ItemTransfer.giveOrDrop(player, stacks);
                        svc.messages().send(player, "transport.picked_up", "id", id, "dx", t.destination().x(), "dz", t.destination().z());
                    } else {
                        svc.tasks().async("transport cargo to stash", () -> svc.core().stash().deposit(
                                ItemStashService.Owner.player(uuid), ItemTransfer.toStash(stacks), "TRANSPORT_CARGO", Long.toString(id)));
                    }
                });
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int deliver(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> svc.core().transports().find(id).orElseThrow(() -> new DomainException("transport.not_found")), t -> {
            if (!near(player, t.destination())) {
                svc.messages().send(player, "transport.go_to", "x", t.destination().x(), "z", t.destination().z());
                return;
            }
            Material material = Material.valueOf(t.material());
            if (ItemTransfer.countPristine(player, material) < t.quantity()) {
                svc.messages().sendError(player, DomainException.of("transport.incomplete_cargo", "required", t.quantity(),
                        "delivered", ItemTransfer.countPristine(player, material)));
                return;
            }
            List<ItemStack> taken = ItemTransfer.takePristine(player, material, t.quantity());
            List<ItemStashService.StashItem> items = ItemTransfer.toStash(taken);
            UUID token = UUID.randomUUID();
            svc.tasks().async(() -> svc.core().transports().deliver(uuid, id, items, token)).whenComplete((done, error) -> {
                Throwable cause = error == null ? null : Tasks.unwrap(error);
                boolean recorded = error == null;
                if (!recorded && !(cause instanceof DomainException)) {
                    try {
                        recorded = svc.core().transports().deliveryRecorded(token);
                    } catch (RuntimeException e) {
                        recorded = true; // cannot tell: never duplicate cargo
                        svc.core().logger().log(Level.SEVERE, "[AUDIT] Transport delivery outcome unknown, token " + token, e);
                    }
                }
                final boolean delivered = recorded;
                svc.tasks().sync(() -> {
                    if (delivered) {
                        svc.messages().send(player, "transport.delivered", "id", id, "reward", t.reward(), "xp", t.xpReward());
                        return;
                    }
                    ItemTransfer.giveOrDrop(player, taken);
                    if (cause instanceof DomainException domain) {
                        svc.messages().sendError(player, domain);
                    } else {
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
        svc.tasks().run(player, () -> svc.core().transports().cancel(uuid, id, ItemTransfer.CODEC),
                t -> svc.messages().send(player, "transport.cancelled", "id", t.id()));
        return Command.SINGLE_SUCCESS;
    }

    private int mine(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().transports().involving(uuid), list -> {
            if (list.isEmpty()) {
                svc.messages().send(player, "transport.mine.empty");
                return;
            }
            svc.messages().send(player, "transport.mine.header");
            for (TransportService.Transport t : list) {
                svc.messages().send(player, "transport.mine.entry", "id", t.id(), "quantity", t.quantity(), "item", itemName(t.material()),
                        "status", svc.messages().render(player, "transport.status." + t.status().name()), "dx", t.destination().x(),
                        "dz", t.destination().z(), "deadline", t.deadline());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
