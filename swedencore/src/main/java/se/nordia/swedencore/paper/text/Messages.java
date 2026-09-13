package se.nordia.swedencore.paper.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.LocalizationService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.paper.session.PlayerSessions;
import se.nordia.swedencore.skills.Skill;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders localized MiniMessage templates.
 *
 * <p><b>Security:</b> every argument is inserted with {@link Placeholder#unparsed} (or as a pre-built component).
 * Player-controlled text such as names is therefore never interpreted as MiniMessage tags. Only templates from our
 * own language files are parsed.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LocalizationService localization;
    private final PlayerSessions sessions;
    private final SupportedLocale defaultLocale;
    private final ZoneId zone = ZoneId.systemDefault();

    public Messages(LocalizationService localization, PlayerSessions sessions, SupportedLocale defaultLocale) {
        this.localization = localization;
        this.sessions = sessions;
        this.defaultLocale = defaultLocale;
    }

    public SupportedLocale defaultLocale() {
        return defaultLocale;
    }

    public SupportedLocale localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            SupportedLocale preferred = sessions.get(player.getUniqueId()).map(PlayerSession::locale).orElse(null);
            if (preferred != null) {
                return preferred;
            }
        }
        return defaultLocale;
    }

    public String raw(SupportedLocale locale, String key) {
        return localization.template(locale, key);
    }

    public Component render(SupportedLocale locale, String key, Map<String, ?> args) {
        List<TagResolver> resolvers = new ArrayList<>(args.size() + 1);
        resolvers.add(Placeholder.parsed("prefix", localization.template(locale, "prefix")));
        for (Map.Entry<String, ?> entry : args.entrySet()) {
            resolvers.add(toResolver(locale, entry.getKey(), entry.getValue()));
        }
        return MINI.deserialize(localization.template(locale, key), TagResolver.resolver(resolvers));
    }

    public Component render(SupportedLocale locale, String key, Object... keyValues) {
        return render(locale, key, pairs(keyValues));
    }

    public Component render(CommandSender viewer, String key, Object... keyValues) {
        return render(localeOf(viewer), key, pairs(keyValues));
    }

    public void send(CommandSender sender, String key, Object... keyValues) {
        sender.sendMessage(render(localeOf(sender), key, pairs(keyValues)));
    }

    public void sendError(CommandSender sender, DomainException error) {
        sender.sendMessage(render(localeOf(sender), error.messageKey(), error.args()));
    }

    /** Formats a single value for a locale (money, numbers, instants). */
    public String format(SupportedLocale locale, Object value) {
        return switch (value) {
            case null -> "-";
            case Money money -> money.format(locale.javaLocale());
            case Integer i -> plainSpaces(NumberFormat.getIntegerInstance(locale.javaLocale()).format(i));
            case Long l -> plainSpaces(NumberFormat.getIntegerInstance(locale.javaLocale()).format(l));
            case Instant instant -> DATE_TIME.format(instant.atZone(zone));
            default -> String.valueOf(value);
        };
    }

    /** Swedish grouping uses (narrow) no-break spaces, which the Minecraft font renders poorly. */
    private static String plainSpaces(String s) {
        return s.replace('\u00A0', ' ').replace('\u202F', ' ');
    }

    private TagResolver toResolver(SupportedLocale locale, String name, Object value) {
        if (value instanceof Component component) {
            return Placeholder.component(name, component);
        }
        if (value instanceof Skill skill) {
            return Placeholder.component(name, render(locale, "skill." + skill.name()));
        }
        return Placeholder.unparsed(name, format(locale, value));
    }

    private static Map<String, Object> pairs(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Arguments must be key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
