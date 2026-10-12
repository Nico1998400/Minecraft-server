package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.player.NordiaPlayer;
import se.nordia.plugins.util.Durations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Keeping order: {@code /tempban} and {@code /kickall}. (Permanent bans, kicks and pardons are the normal Minecraft commands.) */
public final class ModerationCommands {

    private final EssentialsKit kit;

    public ModerationCommands(EssentialsKit kit) {
        this.kit = kit;
    }

    public void register(Commands commands) {
        commands.register(EssentialsKit.literal("tempban", "tempban")
                .then(EssentialsKit.playerArg().then(Commands.argument("details", StringArgumentType.greedyString()).executes(c -> {
                    tempBan(c.getSource().getSender(), StringArgumentType.getString(c, "player"),
                            StringArgumentType.getString(c, "details"));
                    return Command.SINGLE_SUCCESS;
                }))).build(), "Tillfällig avstängning / Temporary ban", List.of());

        commands.register(EssentialsKit.literal("kickall", "kickall")
                .executes(c -> kickAll(c.getSource().getSender(), ""))
                .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c ->
                        kickAll(c.getSource().getSender(), StringArgumentType.getString(c, "reason"))))
                .build(), "Sparka alla / Kick everyone", List.of());
    }

    private void tempBan(CommandSender sender, String name, String details) {
        String[] parts = details.strip().split("\\s+", 2);
        Optional<Duration> duration = Durations.parse(parts[0]);
        if (duration.isEmpty()) {
            kit.messages().send(sender, "ess.bad_value", "value", parts[0]);
            return;
        }
        String reason = parts.length > 1 ? parts[1] : "";
        kit.svc().tasks().run(sender, () -> {
            var core = kit.svc().core();
            return core.social().findByNameOrNick(name).flatMap(id -> core.players().find(id))
                    .orElseThrow(() -> DomainException.of("player.unknown", "player", name));
        }, target -> ban(sender, target, duration.get(), reason));
    }

    private void ban(CommandSender sender, NordiaPlayer target, Duration duration, String reason) {
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null && online.hasPermission("nordia.essentials.tempban.exempt")) {
            kit.messages().send(sender, "ess.cannot_target", "player", target.name());
            return;
        }
        var profile = Bukkit.createProfile(target.uuid(), target.name());
        ProfileBanList list = Bukkit.getServer().getBanList(BanListType.PROFILE);
        list.addBan(profile, reason.isBlank() ? null : reason, duration, sender.getName());
        if (online != null) {
            online.kick(kit.messages().render(online, "ess.tempban_kick", "time", Durations.format(duration),
                    "reason", reason.isBlank() ? "-" : reason));
        }
        kit.messages().send(sender, "ess.tempbanned", "player", target.name(), "time", Durations.format(duration));
        kit.log(sender.getName() + " tempbanned " + target.name() + " for " + Durations.format(duration)
                + (reason.isBlank() ? "" : ": " + reason));
    }

    private int kickAll(CommandSender sender, String reason) {
        int kicked = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(sender)) {
                continue;
            }
            Component message = kit.messages().render(p, "ess.kickall_kick", "reason", reason.isBlank() ? "-" : reason);
            p.kick(message);
            kicked++;
        }
        kit.messages().send(sender, "ess.kicked_all", "count", kicked);
        kit.log(sender.getName() + " kicked everyone (" + kicked + ")");
        return Command.SINGLE_SUCCESS;
    }
}
