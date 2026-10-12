package se.nordia.plugins.discord;

import java.util.Map;

/**
 * Settings from discord.yml. The bot token comes only from the environment ({@code NORDIA_DISCORD_TOKEN}), never from a
 * file, and is hidden from {@link #toString()}.
 */
public record DiscordConfig(String invite, boolean botEnabled, String token, String guildId, Map<String, String> replies) {

    public DiscordConfig {
        invite = invite == null ? "" : invite.strip();
        token = token == null ? "" : token.strip();
        guildId = guildId == null ? "" : guildId.strip();
        replies = Map.copyOf(replies);
    }

    /** Only https links are shown to players and made clickable. */
    public boolean hasInvite() {
        return invite.startsWith("https://");
    }

    /** The bot starts only when it is switched on and a token exists. */
    public boolean botCanRun() {
        return botEnabled && !token.isEmpty();
    }

    public String reply(String key) {
        return replies.getOrDefault(key, key);
    }

    @Override
    public String toString() {
        return "DiscordConfig[botEnabled=" + botEnabled + ", token=" + (token.isEmpty() ? "<not set>" : "<hidden>")
                + ", guildId=" + guildId + "]";
    }
}
