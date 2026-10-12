package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.essentials.EssentialsState;
import se.nordia.plugins.paper.text.Messages;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.logging.Logger;

/** Small helpers shared by the essentials command classes, so each command stays a few lines. */
public final class EssentialsKit {

    /** What a command does once the sender and (optional) target are known. */
    @FunctionalInterface
    public interface PlayerAction {
        void run(CommandSender sender, Player target);
    }

    private final CommandServices svc;
    private final EssentialsState state;
    private final Logger logger;

    public EssentialsKit(CommandServices svc, EssentialsState state, Logger logger) {
        this.svc = svc;
        this.state = state;
        this.logger = logger;
    }

    public CommandServices svc() {
        return svc;
    }

    public Messages messages() {
        return svc.messages();
    }

    public EssentialsState state() {
        return state;
    }

    public Logger logger() {
        return logger;
    }

    public static Predicate<CommandSourceStack> allowed(String command) {
        return CommandServices.permission(Permissions.ess(command));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name, String command) {
        return Commands.literal(name).requires(allowed(command));
    }

    public static RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("player", StringArgumentType.word()).suggests(CommandServices.onlinePlayerNames());
    }

    public static SuggestionProvider<CommandSourceStack> suggest(String... values) {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String value : values) {
                if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    public Player executorPlayer(CommandContext<CommandSourceStack> c) {
        return svc.requirePlayer(c.getSource());
    }

    /** Finds an online player by real name or nickname (any case), or tells the sender and returns null. */
    public Player online(CommandSender sender, String name) {
        Player match = Bukkit.getPlayerExact(name);
        if (match == null) {
            String wanted = name.toLowerCase(Locale.ROOT);
            for (Player candidate : Bukkit.getOnlinePlayers()) {
                String display = PlainTextComponentSerializer.plainText().serialize(candidate.displayName()).toLowerCase(Locale.ROOT);
                if (candidate.getName().toLowerCase(Locale.ROOT).equals(wanted) || display.equals(wanted)) {
                    match = candidate;
                    break;
                }
            }
        }
        if (match == null || (state.vanished.contains(match.getUniqueId()) && !sender.hasPermission(Permissions.VANISH_SEE)
                && !sender.equals(match))) {
            messages().send(sender, "error.player.unknown");
            return null;
        }
        return match;
    }

    /**
     * Registers the usual pair: {@code /name} acts on yourself, {@code /name <player>} on someone else (needs the
     * {@code .others} permission).
     */
    public LiteralArgumentBuilder<CommandSourceStack> selfOrOther(String name, String command, PlayerAction action) {
        return literal(name, command)
                .executes(c -> {
                    Player self = executorPlayer(c);
                    if (self != null) {
                        action.run(self, self);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(playerArg()
                        .requires(CommandServices.permission(Permissions.essOthers(command)))
                        .executes(c -> {
                            Player target = online(c.getSource().getSender(), StringArgumentType.getString(c, "player"));
                            if (target != null) {
                                action.run(c.getSource().getSender(), target);
                            }
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** Sends the same confirmation to the sender and, when it is someone else, to the target. */
    public void notifyBoth(CommandSender sender, Player target, String keySelf, String keyOther, Object... args) {
        if (sender.equals(target)) {
            messages().send(target, keySelf, args);
        } else {
            messages().send(target, keySelf, args);
            Object[] withTarget = new Object[args.length + 2];
            System.arraycopy(args, 0, withTarget, 0, args.length);
            withTarget[args.length] = "player";
            withTarget[args.length + 1] = target.getName();
            messages().send(sender, keyOther, withTarget);
        }
    }

    public Component state(CommandSender viewer, boolean on) {
        return messages().render(viewer, on ? "ess.on" : "ess.off");
    }

    public void log(String line) {
        logger.info("[Essentials] " + line);
    }
}
