package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import se.nordia.plugins.discord.DiscordConfig;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** {@code /discord} shows the invite, {@code /link} gives a code to send to the Discord bot, {@code /unlink} removes the link. */
public final class DiscordCommands {

    private final EssentialsKit kit;
    private final DiscordConfig config;
    private final BooleanSupplier botRunning;

    public DiscordCommands(EssentialsKit kit, DiscordConfig config, BooleanSupplier botRunning) {
        this.kit = kit;
        this.config = config;
        this.botRunning = botRunning;
    }

    public void register(Commands commands) {
        commands.register(EssentialsKit.literal("discord", "discord").executes(c -> {
            var sender = c.getSource().getSender();
            if (!config.hasInvite()) {
                kit.messages().send(sender, "discord.no_invite");
            } else {
                Component link = Component.text(config.invite(), NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(config.invite()));
                kit.messages().send(sender, "discord.invite", "link", link);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Visa Discord-länken / Show the Discord invite", List.of());

        commands.register(EssentialsKit.literal("link", "link").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p == null) {
                return Command.SINGLE_SUCCESS;
            }
            if (!botRunning.getAsBoolean()) {
                kit.messages().send(p, "discord.link_disabled");
                return Command.SINGLE_SUCCESS;
            }
            UUID id = p.getUniqueId();
            kit.svc().tasks().run(p, () -> kit.svc().core().discordLinks().createCode(id), code -> {
                Component copy = Component.text(code, NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.copyToClipboard(code));
                kit.messages().send(p, "discord.link_code", "code", copy, "minutes", 10);
            });
            return Command.SINGLE_SUCCESS;
        }).build(), "Länka ditt Discord-konto / Link your Discord account", List.of());

        commands.register(EssentialsKit.literal("unlink", "unlink").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p == null) {
                return Command.SINGLE_SUCCESS;
            }
            UUID id = p.getUniqueId();
            kit.svc().tasks().run(p, () -> {
                kit.svc().core().discordLinks().unlink(id);
                return Boolean.TRUE;
            }, done -> {
                kit.messages().send(p, "discord.unlinked");
                kit.log(p.getName() + " unlinked their Discord account");
            });
            return Command.SINGLE_SUCCESS;
        }).build(), "Ta bort länken till Discord / Remove your Discord link", List.of());
    }
}
