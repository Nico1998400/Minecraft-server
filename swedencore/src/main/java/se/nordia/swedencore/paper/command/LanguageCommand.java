package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import se.nordia.swedencore.localization.SupportedLocale;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code /language [sv|en]} — alias {@code /sprak}. */
public final class LanguageCommand {

    private final CommandServices ctx;

    public LanguageCommand(CommandServices ctx) {
        this.ctx = ctx;
    }

    public void register(Commands commands) {
        commands.register(node(), "Byt språk / Change language", List.of("sprak", "lang"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("language")
                .executes(c -> {
                    Player player = ctx.requirePlayer(c.getSource());
                    if (player != null) {
                        SupportedLocale current = ctx.messages().localeOf(player);
                        ctx.messages().send(player, "language.current",
                                "language", ctx.messages().render(current, "language.name." + current.tag()));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("language", StringArgumentType.word())
                        .suggests((c, builder) -> {
                            builder.suggest("sv");
                            builder.suggest("en");
                            return builder.buildFuture();
                        })
                        .executes(c -> {
                            Player player = ctx.requirePlayer(c.getSource());
                            if (player == null) {
                                return Command.SINGLE_SUCCESS;
                            }
                            Optional<SupportedLocale> locale = SupportedLocale.parse(StringArgumentType.getString(c, "language"));
                            if (locale.isEmpty()) {
                                ctx.messages().send(player, "language.usage");
                                return Command.SINGLE_SUCCESS;
                            }
                            UUID uuid = player.getUniqueId();
                            SupportedLocale chosen = locale.get();
                            ctx.tasks().run(player, () -> {
                                ctx.core().players().setLocale(uuid, chosen);
                                return chosen;
                            }, saved -> {
                                ctx.sessions().get(uuid).ifPresent(s -> s.locale(saved));
                                ctx.messages().send(player, "language.changed",
                                        "language", ctx.messages().render(saved, "language.name." + saved.tag()));
                            });
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
