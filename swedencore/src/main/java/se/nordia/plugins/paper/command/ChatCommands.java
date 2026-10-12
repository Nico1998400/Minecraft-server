package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.essentials.EssentialsConfig;
import se.nordia.plugins.paper.Permissions;
import se.nordia.plugins.paper.essentials.EssentialsListener;
import se.nordia.plugins.player.NordiaPlayer;
import se.nordia.plugins.social.SocialService;
import se.nordia.plugins.util.Durations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Talking: {@code /msg /r /msgtoggle /me /broadcast /broadcastworld /helpop /ignore /socialspy /mute /unmute /motd /rules /info
 * /nick /realname}. Everything a player types is shown as plain text, never parsed as MiniMessage.
 */
public final class ChatCommands {

    private static final int MAX_TEXT = 256;

    private final EssentialsKit kit;
    private final Supplier<EssentialsConfig> config;

    public ChatCommands(EssentialsKit kit, Supplier<EssentialsConfig> config) {
        this.kit = kit;
        this.config = config;
    }

    public void register(Commands commands) {
        commands.register(EssentialsKit.literal("msg", "msg")
                .then(EssentialsKit.playerArg().then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(c -> {
                            message(c.getSource().getSender(), StringArgumentType.getString(c, "player"),
                                    StringArgumentType.getString(c, "message"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .build(), "Privat meddelande / Private message", List.of("w", "m", "t", "pm", "tell", "whisper"));

        commands.register(EssentialsKit.literal("r", "r")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
                    reply(c.getSource().getSender(), StringArgumentType.getString(c, "message"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Svara / Reply", List.of("reply"));

        commands.register(EssentialsKit.literal("msgtoggle", "msgtoggle").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                boolean on = kit.state().msgBlocked.add(p.getUniqueId());
                if (!on) {
                    kit.state().msgBlocked.remove(p.getUniqueId());
                }
                kit.messages().send(p, "ess.toggle", "feature", kit.messages().render(p, "ess.feature.msgblock"),
                        "state", kit.state(p, on));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Blockera privata meddelanden / Block private messages", List.of());

        commands.register(EssentialsKit.literal("me", "me")
                .then(Commands.argument("action", StringArgumentType.greedyString()).executes(c -> {
                    action(c.getSource().getSender(), StringArgumentType.getString(c, "action"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Beskriv en handling / Describe an action", List.of("action", "describe"));

        commands.register(EssentialsKit.literal("broadcast", "broadcast")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
                    String text = clip(StringArgumentType.getString(c, "message"));
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        kit.messages().send(p, "broadcast.format", "text", text);
                    }
                    kit.log(c.getSource().getSender().getName() + " broadcast: " + text);
                    return Command.SINGLE_SUCCESS;
                })).build(), "Meddelande till alla / Message everyone", List.of("bc", "bcast", "shout"));

        commands.register(EssentialsKit.literal("broadcastworld", "broadcastworld")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        String text = clip(StringArgumentType.getString(c, "message"));
                        for (Player other : p.getWorld().getPlayers()) {
                            kit.messages().send(other, "broadcast.format", "text", text);
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                })).build(), "Meddelande till din värld / Message your world", List.of("bcw", "shoutworld"));

        commands.register(EssentialsKit.literal("helpop", "helpop")
                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
                    helpop(c.getSource().getSender(), StringArgumentType.getString(c, "message"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Be personalen om hjälp / Ask staff for help", List.of("ac", "amsg"));

        commands.register(EssentialsKit.literal("ignore", "ignore")
                .then(EssentialsKit.playerArg().executes(c -> {
                    ignore(c.getSource(), StringArgumentType.getString(c, "player"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Ignorera en spelare / Ignore a player", List.of("unignore"));

        commands.register(EssentialsKit.literal("socialspy", "socialspy").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                boolean on = kit.state().socialSpies.add(p.getUniqueId());
                if (!on) {
                    kit.state().socialSpies.remove(p.getUniqueId());
                }
                kit.messages().send(p, "ess.toggle", "feature", kit.messages().render(p, "ess.feature.socialspy"),
                        "state", kit.state(p, on));
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Se privata meddelanden / See private messages", List.of());

        commands.register(EssentialsKit.literal("mute", "mute")
                .then(EssentialsKit.playerArg()
                        .executes(c -> {
                            mute(c.getSource().getSender(), StringArgumentType.getString(c, "player"), "");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("details", StringArgumentType.greedyString()).executes(c -> {
                            mute(c.getSource().getSender(), StringArgumentType.getString(c, "player"),
                                    StringArgumentType.getString(c, "details"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .build(), "Tysta en spelare / Mute a player", List.of());

        commands.register(EssentialsKit.literal("unmute", "mute")
                .then(EssentialsKit.playerArg().executes(c -> {
                    unmute(c.getSource().getSender(), StringArgumentType.getString(c, "player"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Häv en mute / Unmute a player", List.of());

        commands.register(EssentialsKit.literal("motd", "motd").executes(c -> text(c.getSource().getSender(), config.get().motd()))
                .build(), "Dagens meddelande / Message of the day", List.of());
        commands.register(EssentialsKit.literal("rules", "rules").executes(c -> text(c.getSource().getSender(), config.get().rulesText()))
                .build(), "Serverregler / Server rules", List.of("regler"));
        commands.register(EssentialsKit.literal("info", "info").executes(c -> text(c.getSource().getSender(), config.get().info()))
                .build(), "Serverinfo / Server info", List.of("about"));

        commands.register(EssentialsKit.literal("nick", "nick")
                .then(Commands.argument("first", StringArgumentType.word())
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                setNick(p, p, StringArgumentType.getString(c, "first"));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("second", StringArgumentType.word())
                                .requires(CommandServices.permission(Permissions.essOthers("nick")))
                                .executes(c -> {
                                    CommandSender sender = c.getSource().getSender();
                                    Player target = kit.online(sender, StringArgumentType.getString(c, "first"));
                                    if (target != null) {
                                        setNick(sender, target, StringArgumentType.getString(c, "second"));
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build(), "Byt smeknamn / Change nickname", List.of("nickname"));

        commands.register(Commands.literal("realname")
                .requires(CommandServices.permission(Permissions.ess("nick")))
                .then(Commands.argument("nick", StringArgumentType.word()).executes(c -> {
                    realName(c.getSource().getSender(), StringArgumentType.getString(c, "nick"));
                    return Command.SINGLE_SUCCESS;
                })).build(), "Visa riktigt namn / Show real name", List.of());
    }

    // ---- private messages

    private void message(CommandSender sender, String targetName, String raw) {
        Player target = kit.online(sender, targetName);
        if (target != null) {
            deliver(sender, target, clip(raw));
        }
    }

    private void reply(CommandSender sender, String raw) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return;
        }
        UUID last = kit.state().lastMessenger.get(player.getUniqueId());
        Player target = last == null ? null : Bukkit.getPlayer(last);
        if (target == null) {
            kit.messages().send(player, "msg.nobody");
            return;
        }
        deliver(player, target, clip(raw));
    }

    private void deliver(CommandSender sender, Player target, String text) {
        if (sender instanceof Player p && muted(p)) {
            return;
        }
        if (kit.state().msgBlocked.contains(target.getUniqueId()) && !sender.hasPermission(Permissions.ess("msg.bypass"))) {
            kit.messages().send(sender, "msg.blocked", "player", target.displayName());
            return;
        }
        Component senderName = sender instanceof Player p ? p.displayName() : Component.text(sender.getName());
        kit.messages().send(sender, "msg.to", "player", target.displayName(), "text", text);
        boolean ignored = sender instanceof Player p && kit.state().ignores(target.getUniqueId(), p.getUniqueId());
        if (!ignored) {
            kit.messages().send(target, "msg.from", "player", senderName, "text", text);
            if (sender instanceof Player p) {
                kit.state().lastMessenger.put(target.getUniqueId(), p.getUniqueId());
                kit.state().lastMessenger.put(p.getUniqueId(), target.getUniqueId());
            }
        }
        for (UUID spy : kit.state().socialSpies) {
            Player watcher = Bukkit.getPlayer(spy);
            if (watcher != null && !watcher.equals(target) && !watcher.equals(sender)) {
                kit.messages().send(watcher, "msg.spy", "player", senderName, "target", target.displayName(), "text", text);
            }
        }
    }

    private void action(CommandSender sender, String raw) {
        if (sender instanceof Player p && muted(p)) {
            return;
        }
        String text = clip(raw);
        Component name = sender instanceof Player p ? p.displayName() : Component.text(sender.getName());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player p && kit.state().ignores(viewer.getUniqueId(), p.getUniqueId())) {
                continue;
            }
            kit.messages().send(viewer, "me.format", "player", name, "text", text);
        }
    }

    private void helpop(CommandSender sender, String raw) {
        if (sender instanceof Player p && muted(p)) {
            return;
        }
        String text = clip(raw);
        int receivers = 0;
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(Permissions.HELPOP_RECEIVE)) {
                kit.messages().send(staff, "helpop.format", "player", sender.getName(), "text", text);
                receivers++;
            }
        }
        kit.log("helpop from " + sender.getName() + ": " + text);
        kit.messages().send(sender, receivers > 0 ? "helpop.sent" : "helpop.none_online");
    }

    /** True (and tells the player) if the player is muted right now. */
    private boolean muted(Player player) {
        SocialService.Mute mute = kit.state().mutes.get(player.getUniqueId());
        if (mute == null || player.hasPermission(Permissions.MUTE_EXEMPT)) {
            return false;
        }
        if (mute.until() != null && !mute.until().isAfter(Instant.now())) {
            kit.state().mutes.remove(player.getUniqueId());
            return false;
        }
        kit.messages().send(player, "mute.you_are_muted");
        return true;
    }

    // ---- ignore, mute

    private record IgnoreResult(UUID target, String name, boolean nowIgnored) {
    }

    private void ignore(io.papermc.paper.command.brigadier.CommandSourceStack source, String name) {
        Player player = kit.svc().requirePlayer(source);
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean currentlyIgnored = false;
        Optional<UUID> online = Optional.ofNullable(Bukkit.getPlayerExact(name)).map(Player::getUniqueId);
        if (online.isPresent()) {
            currentlyIgnored = kit.state().ignores(id, online.get());
        }
        boolean wasIgnored = currentlyIgnored;
        kit.svc().tasks().run(player, () -> {
            UUID target = kit.svc().core().social().findByNameOrNick(name)
                    .orElseThrow(() -> DomainException.of("player.unknown", "player", name));
            boolean remove = wasIgnored || kit.svc().core().social().ignored(id).contains(target);
            if (remove) {
                kit.svc().core().social().unignore(id, target);
            } else {
                kit.svc().core().social().ignore(id, target);
            }
            return new IgnoreResult(target, name, !remove);
        }, result -> {
            Set<UUID> set = kit.state().ignores.computeIfAbsent(id, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            if (result.nowIgnored()) {
                set.add(result.target());
            } else {
                set.remove(result.target());
            }
            kit.messages().send(player, result.nowIgnored() ? "ignore.on" : "ignore.off", "player", result.name());
        });
    }

    private void mute(CommandSender sender, String name, String details) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && online.hasPermission(Permissions.MUTE_EXEMPT)) {
            kit.messages().send(sender, "mute.exempt", "player", online.getName());
            return;
        }
        String[] parts = details.strip().isEmpty() ? new String[0] : details.strip().split("\\s+", 2);
        Optional<Duration> duration = parts.length > 0 ? Durations.parse(parts[0]) : Optional.empty();
        String reason = parts.length == 0 ? "" : duration.isPresent() ? (parts.length > 1 ? parts[1] : "") : details.strip();
        UUID by = sender instanceof Player p ? p.getUniqueId() : null;
        kit.svc().tasks().run(sender, () -> {
            UUID target = kit.svc().core().social().findByNameOrNick(name)
                    .orElseThrow(() -> DomainException.of("player.unknown", "player", name));
            return kit.svc().core().social().mute(target, by, reason, duration.orElse(null));
        }, mute -> {
            Player target = Bukkit.getPlayer(mute.player());
            Component time = duration.<Component>map(d -> Component.text(Durations.format(d))).orElseGet(() -> kit.messages().render(sender, "ess.permanent"));
            if (target != null) {
                kit.state().mutes.put(mute.player(), mute);
                kit.messages().send(target, "mute.muted_you", "time", time);
                if (!reason.isBlank()) {
                    kit.messages().send(target, "mute.reason", "reason", reason);
                }
            }
            kit.messages().send(sender, "mute.muted", "player", name, "time", time);
            kit.log(sender.getName() + " muted " + name + " (" + (duration.isPresent() ? Durations.format(duration.get()) : "permanent")
                    + ")" + (reason.isBlank() ? "" : ": " + reason));
        });
    }

    private void unmute(CommandSender sender, String name) {
        kit.svc().tasks().run(sender, () -> {
            UUID target = kit.svc().core().social().findByNameOrNick(name)
                    .orElseThrow(() -> DomainException.of("player.unknown", "player", name));
            kit.svc().core().social().unmute(target);
            return target;
        }, target -> {
            kit.state().mutes.remove(target);
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                kit.messages().send(online, "mute.unmuted_you");
            }
            kit.messages().send(sender, "mute.unmuted", "player", name);
            kit.log(sender.getName() + " unmuted " + name);
        });
    }

    // ---- texts, nicknames

    private int text(CommandSender sender, List<String> lines) {
        if (lines.isEmpty()) {
            kit.messages().send(sender, "ess.no_text");
            return Command.SINGLE_SUCCESS;
        }
        for (String line : lines) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(line));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void setNick(CommandSender sender, Player target, String raw) {
        boolean clear = raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("av");
        UUID id = target.getUniqueId();
        kit.svc().tasks().run(sender, () -> {
            if (clear) {
                kit.svc().core().social().clearNick(id);
                return "";
            }
            return kit.svc().core().social().setNick(id, raw);
        }, nick -> {
            if (nick.isEmpty()) {
                EssentialsListener.clearNick(target);
                kit.messages().send(target, "nick.cleared");
            } else {
                EssentialsListener.applyNick(target, nick);
                kit.messages().send(target, "nick.set", "nick", nick);
            }
            if (!sender.equals(target)) {
                kit.messages().send(sender, "nick.set_other", "player", target.getName(), "nick", nick.isEmpty() ? target.getName() : nick);
            }
        });
    }

    private void realName(CommandSender sender, String nick) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            String display = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(online.displayName());
            if (display.equalsIgnoreCase(nick)) {
                kit.messages().send(sender, "realname.result", "nick", display, "player", online.getName());
                return;
            }
        }
        kit.svc().tasks().run(sender, () -> kit.svc().core().social().findByNameOrNick(nick)
                        .flatMap(id -> kit.svc().core().players().find(id)).map(NordiaPlayer::name),
                name -> kit.messages().send(sender, name.isPresent() ? "realname.result" : "error.player.unknown",
                        "nick", nick, "player", name.orElse(nick)));
    }

    private static String clip(String text) {
        String clean = text.strip();
        return clean.length() <= MAX_TEXT ? clean : clean.substring(0, MAX_TEXT);
    }
}
