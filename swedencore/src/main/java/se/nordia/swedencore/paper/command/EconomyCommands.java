package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.economy.AccountOwner;
import se.nordia.swedencore.economy.LedgerEntry;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.economy.TransferReceipt;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.Permissions;
import se.nordia.swedencore.player.NordiaPlayer;

import java.util.List;
import java.util.UUID;

/** {@code /balance}, {@code /pay}, {@code /transactions}. */
public final class EconomyCommands {

    private final CommandServices ctx;

    public EconomyCommands(CommandServices ctx) {
        this.ctx = ctx;
    }

    public void register(Commands commands) {
        commands.register(balance(), "Visa ditt saldo / Show your balance", List.of("saldo", "bal", "money"));
        commands.register(pay(), "Betala en spelare / Pay a player", List.of("betala"));
        commands.register(transactions(), "Visa transaktioner / Show transactions", List.of("transaktioner", "tx"));
    }

    private LiteralCommandNode<CommandSourceStack> balance() {
        return Commands.literal("balance")
                .executes(c -> {
                    Player player = ctx.requirePlayer(c.getSource());
                    if (player != null) {
                        UUID uuid = player.getUniqueId();
                        ctx.tasks().run(player, () -> ctx.core().economy().balance(AccountOwner.player(uuid)),
                                balance -> ctx.messages().send(player, "economy.balance.self", "balance", balance));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(CommandServices.permission(Permissions.BALANCE_OTHERS))
                        .suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            ctx.tasks().run(sender, () -> {
                                NordiaPlayer target = ctx.core().players().requireByName(name);
                                return new PlayerBalance(target.name(), ctx.core().economy().balance(AccountOwner.player(target.uuid())));
                            }, result -> ctx.messages().send(sender, "economy.balance.other",
                                    "player", result.name(), "balance", result.balance()));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> pay() {
        return Commands.literal("pay")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .then(Commands.argument("amount", StringArgumentType.greedyString())
                                .executes(c -> {
                                    Player player = ctx.requirePlayer(c.getSource());
                                    if (player == null) {
                                        return Command.SINGLE_SUCCESS;
                                    }
                                    String targetName = StringArgumentType.getString(c, "player");
                                    String amountInput = StringArgumentType.getString(c, "amount");
                                    Player onlineTarget = Bukkit.getPlayerExact(targetName);
                                    UUID onlineTargetId = onlineTarget == null ? null : onlineTarget.getUniqueId();
                                    UUID payer = player.getUniqueId();
                                    // One idempotency key per command invocation: retries inside the service never
                                    // double-charge, while a deliberate second /pay is a new payment.
                                    String key = "pay:" + UUID.randomUUID();
                                    ctx.tasks().run(player, () -> {
                                        Money amount = Money.parsePositive(amountInput);
                                        NordiaPlayer target = onlineTargetId != null
                                                ? ctx.core().players().find(onlineTargetId).orElseThrow()
                                                : ctx.core().players().requireByName(targetName);
                                        TransferReceipt receipt = ctx.core().economy().pay(payer, target.uuid(), amount, key);
                                        return new PayResult(target, receipt);
                                    }, result -> {
                                        ctx.messages().send(player, "economy.pay.sent",
                                                "amount", result.receipt().amount(),
                                                "player", result.target().name(),
                                                "balance", result.receipt().fromBalanceAfter());
                                        Player recipient = Bukkit.getPlayer(result.target().uuid());
                                        if (recipient != null) {
                                            ctx.messages().send(recipient, "economy.pay.received",
                                                    "amount", result.receipt().amount(), "player", player.getName());
                                        }
                                    });
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private record PayResult(NordiaPlayer target, TransferReceipt receipt) {
    }

    private record PlayerBalance(String name, Money balance) {
    }

    private LiteralCommandNode<CommandSourceStack> transactions() {
        return Commands.literal("transactions")
                .executes(c -> showTransactions(c.getSource(), 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(c -> showTransactions(c.getSource(), IntegerArgumentType.getInteger(c, "count"))))
                .build();
    }

    private int showTransactions(CommandSourceStack source, int count) {
        Player player = ctx.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        ctx.tasks().run(player, () -> ctx.core().economy().history(AccountOwner.player(uuid), count), entries -> {
            if (entries.isEmpty()) {
                ctx.messages().send(player, "economy.transactions.empty");
                return;
            }
            SupportedLocale locale = ctx.messages().localeOf(player);
            ctx.messages().send(player, "economy.transactions.header", "count", entries.size());
            for (LedgerEntry entry : entries) {
                String key = entry.signedAmount().isNegative() ? "economy.transactions.entry_out" : "economy.transactions.entry_in";
                ctx.messages().send(player, key,
                        "date", entry.createdAt(),
                        "type", ctx.messages().render(locale, "transaction.type." + entry.type().name()),
                        "amount", entry.signedAmount().isNegative() ? entry.signedAmount().negate() : entry.signedAmount(),
                        "counterparty", counterparty(locale, entry),
                        "balance", entry.balanceAfter());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private Component counterparty(SupportedLocale locale, LedgerEntry entry) {
        if (entry.counterpartyName() != null) {
            return Component.text(entry.counterpartyName());
        }
        return ctx.messages().render(locale, "economy.counterparty." + entry.counterparty().type().name());
    }
}
