package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import se.nordia.swedencore.core.NordiaCore;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.session.PlayerSessions;
import se.nordia.swedencore.paper.text.Messages;

import java.util.Locale;
import java.util.function.Predicate;

/** Shared dependencies and helpers for command implementations. */
public record CommandServices(NordiaCore core, Messages messages, Tasks tasks, PlayerSessions sessions, String pluginVersion) {

    public static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    public static SuggestionProvider<CommandSourceStack> onlinePlayerNames() {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(player.getName());
                }
            }
            return builder.buildFuture();
        };
    }

    /** Returns the executing player, or sends an error and returns null. */
    public Player requirePlayer(CommandSourceStack source) {
        if (source.getExecutor() instanceof Player player) {
            return player;
        }
        messages.send(source.getSender(), "error.players_only");
        return null;
    }
}
