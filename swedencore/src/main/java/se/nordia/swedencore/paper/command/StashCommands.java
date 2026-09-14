package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.inventory.ItemStashService;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.session.PlayerSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /stash} (alias {@code /forrad}): items held for you or your company, e.g. delivered contract goods.
 *
 * <p>Claims are committed before items are handed out; the number of stacks claimed is limited to free inventory
 * slots so nothing needs to be dropped.
 */
public final class StashCommands {

    private final CommandServices svc;

    public StashCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Förråd / Stash", List.of("forrad"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("stash")
                .executes(c -> show(c.getSource(), false, null))
                .then(Commands.literal("claim").executes(c -> claim(c.getSource(), false, null)))
                .then(Commands.literal("give").then(Commands.literal("company")
                        .executes(c -> give(c.getSource(), null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> give(c.getSource(), StringArgumentType.getString(c, "company"))))))
                .then(Commands.literal("company")
                        .executes(c -> show(c.getSource(), true, null))
                        .then(Commands.literal("claim")
                                .executes(c -> claim(c.getSource(), true, null))
                                .then(Commands.argument("company", StringArgumentType.greedyString())
                                        .executes(c -> claim(c.getSource(), true, StringArgumentType.getString(c, "company")))))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> show(c.getSource(), true, StringArgumentType.getString(c, "company")))))
                .build();
    }

    private ItemStashService.Owner owner(UUID actor, boolean company, String ref) {
        if (!company) {
            return ItemStashService.Owner.player(actor);
        }
        Company target;
        if (ref != null) {
            target = svc.core().companies().requireByRef(ref);
        } else {
            Long selected = svc.sessions().get(actor).map(PlayerSession::selectedCompanyId).orElse(null);
            target = selected != null
                    ? svc.core().companies().find(selected).orElseThrow()
                    : svc.core().companies().resolveForActor(actor, null, CompanyRole.OWNER, CompanyRole.MANAGER);
        }
        return ItemStashService.Owner.company(target.id());
    }

    private int show(CommandSourceStack source, boolean company, String ref) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().stash().summary(owner(uuid, company, ref)), summary -> {
            if (summary.isEmpty()) {
                svc.messages().send(player, "stash.empty");
                return;
            }
            svc.messages().send(player, "stash.header");
            for (ItemStashService.MaterialCount entry : summary) {
                Material material = Material.matchMaterial(entry.material());
                Component name = material == null ? Component.text(entry.material()) : Component.translatable(material.translationKey());
                svc.messages().send(player, "stash.entry", "material", name, "amount", entry.total());
            }
            svc.messages().send(player, company ? "stash.claim_hint_company" : "stash.claim_hint");
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Hands the stack in the main hand to the company inventory. Returned to the player if it fails. */
    private int give(CommandSourceStack source, String ref) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            svc.messages().send(player, "shop.hold_item");
            return Command.SINGLE_SUCCESS;
        }
        ItemStack taken = held.clone();
        player.getInventory().setItemInMainHand(null);
        UUID uuid = player.getUniqueId();
        UUID token = UUID.randomUUID();
        Long selected = svc.sessions().get(uuid).map(se.nordia.swedencore.paper.session.PlayerSession::selectedCompanyId).orElse(null);
        List<ItemStashService.StashItem> items = ItemTransfer.toStash(List.of(taken));
        svc.tasks().async(() -> {
            Company company = ref != null ? svc.core().companies().requireByRef(ref)
                    : selected != null ? svc.core().companies().find(selected).orElseThrow()
                    : svc.core().companies().resolveForActor(uuid, null);
            svc.core().stash().giveToCompany(uuid, company.id(), items, token);
            return company;
        }).whenComplete((company, error) -> svc.tasks().sync(() -> {
            boolean stored = error == null;
            if (!stored && !(se.nordia.swedencore.paper.scheduler.Tasks.unwrap(error) instanceof se.nordia.swedencore.core.DomainException)) {
                try {
                    stored = svc.core().stash().depositRecorded(token);
                } catch (RuntimeException checkFailed) {
                    stored = true; // cannot tell: never risk duplicating; logged below
                    svc.core().logger().log(java.util.logging.Level.SEVERE, "[AUDIT] Company deposit outcome unknown, token " + token, checkFailed);
                }
            }
            if (stored) {
                svc.messages().send(player, "stash.given", "amount", taken.getAmount());
                return;
            }
            // Nothing was stored (the transaction rolled back): give the stack back.
            if (player.isOnline()) {
                ItemTransfer.giveOrDrop(player, List.of(taken));
            } else {
                svc.tasks().async("return company deposit", () ->
                        svc.core().stash().deposit(ItemStashService.Owner.player(uuid), items, "DEPOSIT_RETURN", null));
            }
            Throwable cause = se.nordia.swedencore.paper.scheduler.Tasks.unwrap(error);
            if (cause instanceof se.nordia.swedencore.core.DomainException domain) {
                svc.messages().sendError(player, domain);
            } else {
                svc.core().logger().log(java.util.logging.Level.SEVERE, "Company item deposit failed", cause);
                svc.messages().send(player, "error.internal");
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private int claim(CommandSourceStack source, boolean company, String ref) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        int free = ItemTransfer.freeStorageSlots(player);
        if (free == 0) {
            svc.messages().send(player, "stash.inventory_full");
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().stash().claim(uuid, owner(uuid, company, ref), free), entries -> {
            if (entries.isEmpty()) {
                svc.messages().send(player, "stash.empty");
                return;
            }
            List<ItemStack> stacks = new ArrayList<>();
            int total = 0;
            for (ItemStashService.StashEntry entry : entries) {
                ItemStack stack = ItemTransfer.fromStash(entry.data());
                stacks.add(stack);
                total += stack.getAmount();
            }
            // Claims are already committed: hand out even if the player's inventory changed in the meantime.
            ItemTransfer.giveOrDrop(player, stacks);
            svc.messages().send(player, "stash.claimed", "stacks", entries.size(), "amount", total);
        });
        return Command.SINGLE_SUCCESS;
    }
}
