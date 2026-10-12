package se.nordia.plugins.paper.tutorial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * The tutorial guide: a friendly {@link Vex} that hovers in place with only the word "Tutorial" above its head. Clicking it
 * opens the {@link TutorialMenu}. It cannot move, fight or be hurt, and it turns to look at players who come close.
 *
 * <p>An admin places one where they stand ({@code /tutorial skapa}) and moves or removes it with the same command; the places are
 * saved in {@code tutorial-boards.yml}. Like Brage the figures are not persistent: they are created whenever their chunk is
 * loaded, so a restart can never leave duplicates behind.
 */
public final class TutorialBoards implements Listener {

    private static final double HOVER = 0.5;
    private static final double LOOK_DISTANCE = 12.0;

    /** Where a figure stands (the floor under it) and the entity that currently is it. */
    private static final class Board {
        String world;
        double x;
        double y;
        double z;
        UUID entity;

        Board(String world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Plugin plugin;
    private final TutorialMenu menu;
    private final File boardFile;
    private final NamespacedKey marker;
    private final List<Board> boards = new CopyOnWriteArrayList<>();
    private Component name = Component.text("Tutorial");

    public TutorialBoards(Plugin plugin, TutorialMenu menu, ConfigurationSection config) {
        this.plugin = plugin;
        this.menu = menu;
        this.boardFile = new File(plugin.getDataFolder(), "tutorial-boards.yml");
        this.marker = new NamespacedKey(plugin, "tutorial_board");
        applyConfig(config);
    }

    /** Reads the name above the figure from tutorial.yml (MiniMessage written by the server owner). */
    private void applyConfig(ConfigurationSection config) {
        name = MiniMessage.miniMessage().deserialize(config.getString("board.title",
                "<bold><gradient:#8ff0ff:#3b82f6:#4338ca>Tutorial</gradient></bold>"));
    }

    public void start() {
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensureAll, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::lookAtPlayers, 20L, 10L);
    }

    public void shutdown() {
        boards.forEach(this::removeEntity);
    }

    /** Applies the new name and re-reads the saved places without a restart. */
    public void reload(ConfigurationSection config) {
        applyConfig(config);
        boards.forEach(this::removeEntity);
        load();
        ensureAll();
    }

    public int count() {
        return boards.size();
    }

    // ---- admin

    public void add(Location where) {
        Board board = new Board(where.getWorld().getName(), where.getX(), where.getY(), where.getZ());
        boards.add(board);
        save();
        ensure(board);
    }

    /** Moves the nearest figure (within {@code radius} blocks of {@code near}) to {@code to}. Returns false if there was none. */
    public boolean moveNearest(Location near, Location to, double radius) {
        Board board = nearest(near, radius);
        if (board == null) {
            return false;
        }
        removeEntity(board);
        board.world = to.getWorld().getName();
        board.x = to.getX();
        board.y = to.getY();
        board.z = to.getZ();
        save();
        ensure(board);
        return true;
    }

    /** Removes the nearest figure within {@code radius} blocks. Returns false if there was none. */
    public boolean removeNearest(Location near, double radius) {
        Board board = nearest(near, radius);
        if (board == null) {
            return false;
        }
        removeEntity(board);
        boards.remove(board);
        save();
        return true;
    }

    private Board nearest(Location near, double radius) {
        Board best = null;
        double bestDistance = radius * radius;
        for (Board board : boards) {
            World world = Bukkit.getWorld(board.world);
            if (world == null || !world.equals(near.getWorld())) {
                continue;
            }
            double d = new Location(world, board.x, board.y, board.z).distanceSquared(near);
            if (d <= bestDistance) {
                bestDistance = d;
                best = board;
            }
        }
        return best;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Board b : boards) {
            lines.add(b.world + " " + (int) Math.floor(b.x) + " " + (int) Math.floor(b.y) + " " + (int) Math.floor(b.z));
        }
        return lines;
    }

    // ---- the figure

    private void ensureAll() {
        boards.forEach(this::ensure);
    }

    private void ensure(Board board) {
        World world = Bukkit.getWorld(board.world);
        if (world == null || !world.isChunkLoaded((int) Math.floor(board.x) >> 4, (int) Math.floor(board.z) >> 4)) {
            return;
        }
        Entity existing = board.entity == null ? null : Bukkit.getEntity(board.entity);
        if (existing != null && existing.isValid()) {
            return;
        }
        removeEntity(board);
        Location at = new Location(world, board.x, board.y + HOVER, board.z);
        Vex vex = world.spawn(at, Vex.class, v -> {
            v.setAI(false);
            v.setCharging(false);
            v.setGravity(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setCanPickupItems(false);
            v.setRemoveWhenFarAway(false);
            v.setPersistent(false);
            v.customName(name);
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        });
        board.entity = vex.getUniqueId();
    }

    private void removeEntity(Board board) {
        Entity entity = board.entity == null ? null : Bukkit.getEntity(board.entity);
        if (entity != null) {
            entity.remove();
        }
        board.entity = null;
    }

    /** Turns each figure toward the nearest player within a few blocks so it seems to watch them. */
    private void lookAtPlayers() {
        for (Board board : boards) {
            Entity entity = board.entity == null ? null : Bukkit.getEntity(board.entity);
            if (entity == null || !entity.isValid()) {
                continue;
            }
            Player nearest = null;
            double best = LOOK_DISTANCE * LOOK_DISTANCE;
            for (Player player : entity.getWorld().getPlayers()) {
                double d = player.getLocation().distanceSquared(entity.getLocation());
                if (d < best) {
                    best = d;
                    nearest = player;
                }
            }
            if (nearest != null) {
                Location from = entity.getLocation();
                Location to = nearest.getEyeLocation();
                double dx = to.getX() - from.getX();
                double dz = to.getZ() - from.getZ();
                double dy = to.getY() - (from.getY() + 0.4);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                entity.setRotation(yaw, Math.clamp(pitch, -45f, 45f));
            }
        }
    }

    // ---- clicks

    private boolean isFigure(Entity entity) {
        return entity instanceof Vex && entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent event) {
        if (!isFigure(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            menu.open(event.getPlayer());
        }
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (isFigure(event.getEntity())) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                menu.open(player);
            }
        }
    }

    // ---- saved places

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Board b : boards) {
            lines.add(b.world + ";" + b.x + ";" + b.y + ";" + b.z);
        }
        yaml.set("boards", lines);
        try {
            yaml.save(boardFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the tutorial figures", e);
        }
    }

    private void load() {
        boards.clear();
        if (!boardFile.isFile()) {
            return;
        }
        for (String line : YamlConfiguration.loadConfiguration(boardFile).getStringList("boards")) {
            String[] parts = line.split(";");
            if (parts.length == 4) {
                try {
                    boards.add(new Board(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
                } catch (NumberFormatException ignored) {
                    // skip a damaged line
                }
            }
        }
    }
}
