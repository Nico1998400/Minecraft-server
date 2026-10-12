package se.nordia.plugins.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import se.nordia.plugins.core.DomainException;

import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Discord side of {@code /link}: a bot with one slash command, {@code /link code:ABC123}. It needs no privileged
 * gateway intents. A player first runs {@code /link} in game to get a code, then sends it to this bot.
 */
public final class DiscordBot extends ListenerAdapter {

    private final DiscordConfig config;
    private final DiscordLinkService links;
    private final Consumer<UUID> onLinked;
    private final Logger logger;
    private volatile JDA jda;

    public DiscordBot(DiscordConfig config, DiscordLinkService links, Consumer<UUID> onLinked, Logger logger) {
        this.config = config;
        this.links = links;
        this.onLinked = onLinked;
        this.logger = logger;
    }

    /** Connects in the background; failures are logged and never stop the server. */
    public void start() {
        try {
            jda = JDABuilder.createLight(config.token(), EnumSet.noneOf(GatewayIntent.class)).addEventListeners(this).build();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not start the Discord bot (is NORDIA_DISCORD_TOKEN correct?): " + e.getMessage());
        }
    }

    public void stop() {
        JDA current = jda;
        if (current != null) {
            current.shutdown();
        }
    }

    public boolean running() {
        JDA current = jda;
        return current != null && current.getStatus() == JDA.Status.CONNECTED;
    }

    @Override
    public void onReady(ReadyEvent event) {
        CommandData link = Commands.slash("link", config.reply("command_description"))
                .addOption(OptionType.STRING, "code", config.reply("code_description"), true);
        if (!config.guildId().isEmpty()) {
            Guild guild = event.getJDA().getGuildById(config.guildId());
            if (guild != null) {
                guild.updateCommands().addCommands(link).queue();
                logger.info("Discord bot ready in " + guild.getName());
                return;
            }
            logger.warning("Discord guild-id " + config.guildId() + " was not found; registering the command globally instead");
        }
        event.getJDA().updateCommands().addCommands(link).queue();
        logger.info("Discord bot ready (global command; it can take a while to show up)");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("link")) {
            return;
        }
        var option = event.getOption("code");
        String code = option == null ? "" : option.getAsString();
        String reply;
        try {
            UUID player = links.completeLink(event.getUser().getId(), code);
            reply = config.reply("linked");
            onLinked.accept(player);
        } catch (DomainException e) {
            reply = switch (e.code()) {
                case "discord_code_invalid" -> config.reply("invalid_code");
                case "discord_already_linked" -> config.reply("already_linked");
                case "discord_account_taken" -> config.reply("account_taken");
                default -> config.reply("error");
            };
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Discord link failed", e);
            reply = config.reply("error");
        }
        event.reply(reply).setEphemeral(true).queue();
    }
}
