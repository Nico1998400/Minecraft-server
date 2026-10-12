package se.nordia.plugins.paper.world;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import se.nordia.plugins.paper.Permissions;

/** Keeps the build world op-only and inert. The permission is re-checked on every teleport, not just in the command. */
public final class BuildWorldListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || !BuildWorld.isBuildWorld(event.getTo().getWorld())
                || BuildWorld.isBuildWorld(event.getFrom().getWorld())) {
            return;
        }
        if (!event.getPlayer().hasPermission(Permissions.ADMIN_BUILDWORLD)) {
            event.setCancelled(true);
        }
    }

    /** Safety net: anyone who ends up in the build world without permission is sent out. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (BuildWorld.isBuildWorld(player.getWorld()) && !player.hasPermission(Permissions.ADMIN_BUILDWORLD)) {
            player.teleportAsync(event.getFrom().getSpawnLocation());
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (BuildWorld.isBuildWorld(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent event) {
        if (BuildWorld.isBuildWorld(event.getWorld()) && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpread(BlockSpreadEvent event) {
        if (BuildWorld.isBuildWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }
}
