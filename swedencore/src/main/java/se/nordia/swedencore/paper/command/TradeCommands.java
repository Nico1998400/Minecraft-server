package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import se.nordia.swedencore.paper.trade.TradeManager;

import java.util.List;

/** {@code /trade <player>}, {@code /trade accept|deny <player>} (alias {@code /byt}). */
public final class TradeCommands {

    private final CommandServices svc;
    private final TradeManager trades;

    public TradeCommands(CommandServices svc, TradeManager trades) {
        this.svc = svc;
        this.trades = trades;
    }

    public void register(Commands commands) {
        commands.register(node(), "Byteshandel / Trade with a player", List.of("byt"));
    }

    @FunctionalInterface
    private interface Action {
        void run(Player self, Player other);
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("trade")
                .then(Commands.literal("accept").then(target((self, other) -> trades.accept(self, other))))
                .then(Commands.literal("deny").then(target((self, other) -> trades.deny(self, other))))
                .then(target(trades::request))
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> target(Action action) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(CommandServices.onlinePlayerNames())
                .executes((CommandContext<CommandSourceStack> c) -> {
                    Player self = svc.requirePlayer(c.getSource());
                    if (self == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Player other = Bukkit.getPlayerExact(StringArgumentType.getString(c, "player"));
                    if (other == null) {
                        svc.messages().send(self, "error.player.unknown");
                        return Command.SINGLE_SUCCESS;
                    }
                    action.run(self, other);
                    return Command.SINGLE_SUCCESS;
                });
    }
}
