package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.EconomyService;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransferReceipt;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.player.NordiaPlayer;

import java.util.List;
import java.util.UUID;

/** {@code /eco ...} admin economy tools and {@code /nordia} status/reload. */
public final class AdminCommands {

    private final CommandServices svc;
    private final Runnable reloadLanguages;

    public AdminCommands(CommandServices svc, Runnable reloadLanguages) {
        this.svc = svc;
        this.reloadLanguages = reloadLanguages;
    }

    public void register(Commands commands) {
        commands.register(eco(), "NORDIA ekonomiadministration / economy administration", List.of());
        commands.register(nordia(), "NORDIA status", List.of());
    }

    private record PlayerBalance(String name, Money balance) {
    }

    private record AdminResult(String name, Money amount, Money balanceAfter) {
    }

    private LiteralCommandNode<CommandSourceStack> eco() {
        return Commands.literal("eco")
                .requires(CommandServices.permission(Permissions.ADMIN_ECONOMY))
                .then(Commands.literal("give").then(playerAmount("admin.eco.granted", (sender, target, amount) -> {
                    TransferReceipt r = svc.core().economy().adminGrant(actor(sender), target.uuid(), amount);
                    return new AdminResult(target.name(), r.amount(), r.toBalanceAfter());
                })))
                .then(Commands.literal("take").then(playerAmount("admin.eco.removed", (sender, target, amount) -> {
                    TransferReceipt r = svc.core().economy().adminRemove(actor(sender), target.uuid(), amount);
                    return new AdminResult(target.name(), r.amount(), r.fromBalanceAfter());
                })))
                .then(Commands.literal("balance").then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            svc.tasks().run(sender, () -> {
                                NordiaPlayer target = svc.core().players().requireByName(name);
                                return new PlayerBalance(target.name(), svc.core().economy().balance(AccountOwner.player(target.uuid())));
                            }, r -> svc.messages().send(sender, "admin.eco.balance", "player", r.name(), "balance", r.balance()));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("supply").executes(c -> {
                    CommandSender sender = c.getSource().getSender();
                    svc.tasks().run(sender, () -> svc.core().economy().moneySupply(),
                            supply -> svc.messages().send(sender, "admin.eco.supply", "supply", supply));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("audit").executes(c -> {
                    CommandSender sender = c.getSource().getSender();
                    svc.tasks().run(sender, () -> svc.core().economy().audit(), (EconomyService.LedgerAudit audit) -> {
                        if (audit.healthy()) {
                            svc.messages().send(sender, "admin.eco.audit_ok");
                        } else {
                            svc.messages().send(sender, "admin.eco.audit_failed",
                                    "total", Money.ofOre(audit.totalBalanceOre()),
                                    "accounts", audit.mismatchedAccountIds().toString());
                        }
                    });
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    @FunctionalInterface
    private interface AdminMoneyAction {
        AdminResult apply(CommandSender sender, NordiaPlayer target, Money amount);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> playerAmount(String successKey, AdminMoneyAction action) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(CommandServices.onlinePlayerNames())
                .then(Commands.argument("amount", StringArgumentType.greedyString())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            String amountInput = StringArgumentType.getString(c, "amount");
                            svc.tasks().run(sender, () -> {
                                Money amount = Money.parsePositive(amountInput);
                                NordiaPlayer target = svc.core().players().requireByName(name);
                                AdminResult result = action.apply(sender, target, amount);
                                svc.core().logger().info("[AUDIT] " + sender.getName() + " " + successKey + " "
                                        + amount + " -> " + target.name());
                                return result;
                            }, r -> svc.messages().send(sender, successKey,
                                    "player", r.name(), "amount", r.amount(), "balance", r.balanceAfter()));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private static UUID actor(CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    private LiteralCommandNode<CommandSourceStack> nordia() {
        return Commands.literal("nordia")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "nordia.info", "version", svc.pluginVersion());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("reload")
                        .requires(CommandServices.permission(Permissions.ADMIN_RELOAD))
                        .executes(c -> {
                            reloadLanguages.run();
                            svc.messages().send(c.getSource().getSender(), "nordia.reloaded");
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
