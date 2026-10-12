package se.nordia.plugins.paper.guide;

import org.bukkit.configuration.ConfigurationSection;

/**
 * How Brage behaves in the world, from guide.yml: walking his route, the symbol above his head and his talking sound.
 * Values are clamped so a typo cannot make him sprint or freeze the server.
 */
public record GuideBehaviour(boolean patrol, double speed, int pauseSeconds, boolean wander, double wanderRadius, boolean requireRoof, boolean marker, boolean emblem, String symbol,
                             boolean hideAfterTalk, boolean talkSound) {

    public GuideBehaviour {
        speed = Math.clamp(speed, 0.2, 4.0);
        wanderRadius = Math.clamp(wanderRadius, 2.0, 30.0);
        pauseSeconds = Math.clamp(pauseSeconds, 0, 600);
        symbol = symbol == null || symbol.isBlank() ? "!" : symbol.strip();
        if (symbol.length() > 4) {
            symbol = symbol.substring(0, 4);
        }
    }

    public static GuideBehaviour defaults() {
        return new GuideBehaviour(true, 1.3, 8, true, 7.0, true, true, true, "!", true, true);
    }

    public static GuideBehaviour fromYaml(ConfigurationSection s) {
        GuideBehaviour d = defaults();
        return new GuideBehaviour(
                s.getBoolean("patrol.enabled", d.patrol()),
                s.getDouble("patrol.speed", d.speed()),
                s.getInt("patrol.pause-seconds", d.pauseSeconds()),
                s.getBoolean("patrol.wander.enabled", d.wander()),
                s.getDouble("patrol.wander.radius", d.wanderRadius()),
                s.getBoolean("patrol.wander.require-roof", d.requireRoof()),
                s.getBoolean("marker.enabled", d.marker()),
                !"text".equalsIgnoreCase(s.getString("marker.style", "emblem")),
                s.getString("marker.symbol", d.symbol()),
                s.getBoolean("marker.hide-after-talk", d.hideAfterTalk()),
                s.getBoolean("sound.talk", d.talkSound()));
    }
}
