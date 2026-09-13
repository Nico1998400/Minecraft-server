package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.contracts.Contract;
import se.nordia.swedencore.contracts.ContractService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.DatabaseException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.skills.Skill;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/** {@code /contract} (alias {@code /kontrakt}) and {@code /contracts} board. */
public final class ContractCommands {

    private static final int PAGE_SIZE = 8;

    private final CommandServices svc;

    public ContractCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(contract(), "Kontrakt / Contracts", List.of("kontrakt"));
        commands.register(Commands.literal("contracts")
                .executes(c -> board(c.getSource().getSender(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                        .executes(c -> board(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "page"))))
                .build(), "Kontraktstavlan / Contract board", List.of("uppdrag"));
    }

    private ContractService contracts() {
        return svc.core().contracts();
    }

    private LiteralCommandNode<CommandSourceStack> contract() {
        return Commands.literal("contract")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "contract.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(createNode())
                .then(Commands.literal("info").then(idArg(this::info)))
                .then(Commands.literal("mine").executes(this::mine))
                .then(Commands.literal("accept").then(idArg(c -> simple(c, "contract.accepted", (u, id) -> contracts().accept(u, id)))))
                .then(Commands.literal("complete").then(idArg(c -> simple(c, "contract.completed", (u, id) -> contracts().complete(u, id)))))
                .then(Commands.literal("cancel").then(idArg(c -> simple(c, "contract.cancelled", (u, id) -> contracts().cancel(u, id)))))
                .then(Commands.literal("abandon").then(idArg(c -> simple(c, "contract.abandoned", (u, id) -> contracts().abandon(u, id)))))
                .then(Commands.literal("deliver").then(idArg(this::deliver)))
                .then(Commands.literal("skill").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .then(Commands.literal("none").executes(c -> skill(c, null, 1)))
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Skill s : Skill.values()) {
                                        if (s.key().startsWith(b.getRemainingLowerCase())) {
                                            b.suggest(s.key());
                                        }
                                    }
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                        .executes(c -> skill(c, StringArgumentType.getString(c, "skill"), IntegerArgumentType.getInteger(c, "level")))))))
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Long> idArg(com.mojang.brigadier.Command<CommandSourceStack> action) {
        return Commands.argument("id", LongArgumentType.longArg(1)).executes(action);
    }

    // ------------------------------------------------------------------ board & info

    private int board(CommandSender sender, int page) {
        svc.tasks().run(sender, () -> contracts().openContracts(PAGE_SIZE, (page - 1) * PAGE_SIZE), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "contracts.board.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "contracts.board.header", "page", page);
            for (Contract k : list) {
                Component line = svc.messages().render(locale, k.type() == Contract.Type.ITEM_DELIVERY
                                ? "contracts.board.entry_delivery" : "contracts.board.entry_service",
                        "id", k.id(), "title", k.title(), "issuer", k.issuerName(), "reward", k.reward(),
                        "quantity", k.remaining(), "material", material(k.material()), "deadline", k.deadline());
                sender.sendMessage(line.clickEvent(ClickEvent.runCommand("/contract info " + k.id()))
                        .hoverEvent(HoverEvent.showText(svc.messages().render(locale, "contracts.board.hover"))));
            }
            svc.messages().send(sender, "contracts.board.footer", "next", page + 1);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(sender, () -> contracts().find(id).orElseThrow(() -> new DomainException("contract.not_found")), k -> {
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "contract.info.header", "id", k.id(), "title", k.title(),
                    "status", svc.messages().render(locale, "contract.status." + k.status().name()));
            svc.messages().send(sender, "contract.info.issuer", "issuer", k.issuerName(), "reward", k.reward(),
                    "escrow", k.escrowRemaining(), "deadline", k.deadline());
            if (k.type() == Contract.Type.ITEM_DELIVERY) {
                svc.messages().send(sender, "contract.info.delivery", "material", material(k.material()),
                        "delivered", k.delivered(), "quantity", k.quantity());
            } else {
                svc.messages().send(sender, "contract.info.service");
            }
            if (k.requiredSkill() != null) {
                svc.messages().send(sender, "contract.info.skill", "skill", k.requiredSkill(), "level", k.requiredLevel(), "xp", k.xpReward());
            }
            if (k.contractor() != null) {
                svc.messages().send(sender, "contract.info.contractor", "player", k.contractorName());
            }
            if (k.status() == Contract.Status.OPEN) {
                sender.sendMessage(svc.messages().render(locale, "contract.info.accept_hint", "id", k.id())
                        .clickEvent(ClickEvent.suggestCommand("/contract accept " + k.id())));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int mine(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> contracts().involving(uuid), list -> {
            if (list.isEmpty()) {
                svc.messages().send(player, "contract.mine.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "contract.mine.header");
            for (Contract k : list) {
                String role = uuid.equals(k.contractor()) ? "contract.mine.role_contractor" : "contract.mine.role_issuer";
                svc.messages().send(player, "contract.mine.entry", "id", k.id(), "title", k.title(),
                        "status", svc.messages().render(locale, "contract.status." + k.status().name()),
                        "role", svc.messages().render(locale, role), "reward", k.reward(), "deadline", k.deadline());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ actions

    @FunctionalInterface
    private interface ContractAction {
        Contract apply(UUID actor, long id);
    }

    private int simple(CommandContext<CommandSourceStack> c, String successKey, ContractAction action) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> action.apply(uuid, id), k -> {
            svc.messages().send(player, successKey, "id", k.id(), "title", k.title(), "reward", k.reward());
            notifyCounterparty(player, k, successKey + "_notice");
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Tells the other side (issuer player / company owner, or the contractor) about a change, if online. */
    private void notifyCounterparty(Player actor, Contract k, String key) {
        svc.tasks().run(Bukkit.getConsoleSender(), () -> {
            if (actor.getUniqueId().equals(k.contractor()) || k.contractor() == null) {
                return k.issuerType() == Contract.IssuerType.PLAYER ? k.issuerPlayer()
                        : svc.core().companies().find(k.issuerCompanyId()).map(co -> co.ownerUuid()).orElse(null);
            }
            return k.contractor();
        }, target -> {
            if (target == null || target.equals(actor.getUniqueId())) {
                return;
            }
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                svc.messages().send(online, key, "id", k.id(), "title", k.title(), "player", actor.getName(), "reward", k.reward());
            }
        });
    }

    private int skill(CommandContext<CommandSourceStack> c, String skillInput, int level) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> {
            Skill skill = skillInput == null ? null
                    : Skill.parse(skillInput).orElseThrow(() -> new DomainException("skills.unknown_skill"));
            return contracts().setSkillRequirement(uuid, id, skill, level);
        }, k -> svc.messages().send(player, "contract.skill_set", "id", k.id(), "xp", k.xpReward()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Delivery: look up the contract, remove pristine matching items (main thread), record the delivery (async).
     * On failure the items are returned — unless the delivery token shows the delivery was in fact committed.
     */
    private int deliver(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> contracts().find(id).orElseThrow(() -> new DomainException("contract.not_found")), k -> {
            if (k.type() != Contract.Type.ITEM_DELIVERY) {
                svc.messages().sendError(player, new DomainException("contract.wrong_type"));
                return;
            }
            if (k.status() != Contract.Status.IN_PROGRESS || !uuid.equals(k.contractor())) {
                svc.messages().sendError(player, new DomainException("contract.not_contractor"));
                return;
            }
            Material material = Material.matchMaterial(k.material());
            if (material == null || !player.isOnline()) {
                return;
            }
            List<ItemStack> taken = ItemTransfer.takePristine(player, material, k.remaining());
            if (taken.isEmpty()) {
                svc.messages().send(player, "contract.deliver.no_items", "material", material(k.material()));
                return;
            }
            List<ItemStashService.StashItem> stashItems = ItemTransfer.toStash(taken);
            UUID token = UUID.randomUUID();
            svc.tasks().async(() -> contracts().deliver(uuid, id, stashItems, token)).whenComplete((result, error) -> {
                if (error == null) {
                    svc.tasks().sync(() -> {
                        svc.messages().send(player, result.completed() ? "contract.deliver.completed" : "contract.deliver.partial",
                                "amount", result.accepted(), "material", material(k.material()), "payout", result.payout(),
                                "remaining", result.contract().remaining(), "title", k.title());
                        notifyCounterparty(player, result.contract(), "contract.deliver.notice");
                    });
                    return;
                }
                Throwable cause = Tasks.unwrap(error);
                boolean committed = false;
                if (!(cause instanceof DomainException)) {
                    try {
                        committed = contracts().deliveryRecorded(token);
                    } catch (DatabaseException checkFailed) {
                        // Cannot tell. Keep the items in the player's stash rather than risk duplication or loss.
                        svc.core().logger().log(Level.SEVERE, "Delivery outcome unknown for token " + token, checkFailed);
                        committed = true;
                        safeStash(uuid, stashItems, token);
                    }
                }
                final boolean wasCommitted = committed;
                svc.tasks().sync(() -> {
                    if (wasCommitted) {
                        svc.messages().send(player, "contract.deliver.check_stash");
                        return;
                    }
                    if (player.isOnline()) {
                        ItemTransfer.giveOrDrop(player, taken);
                    } else {
                        svc.tasks().async("return delivery items to stash", () ->
                                svc.core().stash().deposit(ItemStashService.Owner.player(uuid), stashItems, "DELIVERY_RETURN", token.toString()));
                    }
                    if (cause instanceof DomainException domain) {
                        svc.messages().sendError(player, domain);
                    } else {
                        svc.core().logger().log(Level.SEVERE, "Delivery failed for contract " + id, cause);
                        svc.messages().send(player, "error.internal");
                    }
                });
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private void safeStash(UUID player, List<ItemStashService.StashItem> items, UUID token) {
        try {
            if (!contracts().deliveryRecorded(token)) {
                svc.core().stash().deposit(ItemStashService.Owner.player(player), items, "DELIVERY_RETURN", token.toString());
            }
        } catch (RuntimeException e) {
            svc.core().logger().log(Level.SEVERE, "Could not secure items for token " + token + ": " + items.size() + " stacks", e);
        }
    }

    // ------------------------------------------------------------------ creation

    LiteralArgumentBuilder<CommandSourceStack> createNode() {
        return Commands.literal("create")
                .then(deliveryBranch(false))
                .then(serviceBranch(false))
                .then(Commands.literal("company").then(deliveryBranch(true)).then(serviceBranch(true)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> deliveryBranch(boolean company) {
        return Commands.literal("delivery")
                .then(Commands.argument("material", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 1_000_000))
                                .then(Commands.argument("reward", StringArgumentType.word())
                                        .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                                        .executes(c -> create(c, Contract.Type.ITEM_DELIVERY, company)))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> serviceBranch(boolean company) {
        return Commands.literal("service")
                .then(Commands.argument("reward", StringArgumentType.word())
                        .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                        .executes(c -> create(c, Contract.Type.SERVICE, company)))));
    }

    private int create(CommandContext<CommandSourceStack> c, Contract.Type type, boolean company) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String rewardInput = StringArgumentType.getString(c, "reward");
        int hours = IntegerArgumentType.getInteger(c, "hours");
        String title = StringArgumentType.getString(c, "title");
        String materialName;
        Integer quantity;
        if (type == Contract.Type.ITEM_DELIVERY) {
            Material material = Material.matchMaterial(StringArgumentType.getString(c, "material").toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem() || material.isAir()) {
                svc.messages().sendError(player, new DomainException("contract.invalid_material"));
                return Command.SINGLE_SUCCESS;
            }
            materialName = material.name();
            quantity = IntegerArgumentType.getInteger(c, "quantity");
        } else {
            materialName = null;
            quantity = null;
        }
        Long selected = svc.sessions().get(uuid).map(PlayerSession::selectedCompanyId).orElse(null);
        svc.tasks().run(player, () -> {
            Money reward = Money.parsePositive(rewardInput);
            Long companyId = null;
            if (company) {
                companyId = selected != null ? selected
                        : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER).id();
            }
            return contracts().create(uuid, companyId,
                    new ContractService.CreateRequest(type, title, materialName, quantity, reward, hours, null, 1));
        }, k -> svc.messages().send(player, "contract.created", "id", k.id(), "title", k.title(), "reward", k.reward(),
                "fee", Money.ofOre(k.reward().ore() * contracts().config().feePercent() / 100)));
        return Command.SINGLE_SUCCESS;
    }

    /** Material name in the client's own language (vanilla translation). */
    private static Component material(String name) {
        if (name == null) {
            return Component.empty();
        }
        Material material = Material.matchMaterial(name);
        return material == null ? Component.text(name) : Component.translatable(material.translationKey());
    }
}
