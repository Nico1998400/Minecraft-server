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
