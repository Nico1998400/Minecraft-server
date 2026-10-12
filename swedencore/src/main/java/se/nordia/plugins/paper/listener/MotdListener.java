package se.nordia.plugins.paper.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shows the server list message (two lines) from config.yml. The lines are MiniMessage written by the server owner, so
 * gradients and hex colours work, unlike the plain {@code motd} in server.properties.
 */
public final class MotdListener implements Listener {

    private final Component motd;

    public MotdListener(String line1, String line2, Logger logger) {
        Component parsed = null;
        try {
            parsed = MiniMessage.miniMessage().deserialize(line1).appendNewline()
                    .append(MiniMessage.miniMessage().deserialize(line2));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Invalid motd in config.yml; keeping the server.properties motd", e);
        }
        this.motd = parsed;
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (motd != null) {
            event.motd(motd);
        }
    }
}
