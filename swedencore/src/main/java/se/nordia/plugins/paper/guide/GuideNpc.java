package se.nordia.plugins.paper.guide;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import se.nordia.plugins.guide.GuideContextBuilder;
import se.nordia.plugins.guide.GuideService;
import se.nordia.plugins.localization.SupportedLocale;
import se.nordia.plugins.paper.scheduler.Tasks;
import se.nordia.plugins.paper.text.Messages;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * The guide at spawn (Brage): a {@link Mannequin} players right-click to start talking. While talking, everything the
 * player types in chat goes to the guide instead of public chat. The conversation ends when the player clicks the guide
 * again, walks away, stays quiet for a few minutes, or leaves.
 *
 * <p>Until a player has talked to him he shows a gold symbol above his head (only that player sees it), and he walks a
 * route inside the building (recorded in game with {@code /brage rutt}) and stops while someone talks to him.
 *
 * <p>The figure is not persistent: it is created whenever its chunk is loaded and vanishes with it, so a restart or a
 * crash can never leave duplicates behind. The AI never sees or changes game state beyond the read-only facts in its
 * prompt, and its replies are shown as plain text (never parsed as MiniMessage).
 */
public final class GuideNpc implements Listener {

    private static final double TALK_DISTANCE = 8.0;
    private static final long IDLE_MILLIS = 3 * 60_000L;
    private static final double ACTIVE_DISTANCE = 48.0;
    private static final double RECORD_SPACING = 2.0;
    private static final int MAX_ROUTE_POINTS = 400;
    private static final double MARKER_HEIGHT = 2.65;
    /** Size of the symbol in blocks and how far it bobs. */
    private static final float EMBLEM_SCALE = 0.55f;
    private static final float BOB = 0.12f;
    /** Blocks he will not walk on or into. */
    private static final java.util.Set<Material> WALK_DANGER = java.util.Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.WITHER_ROSE);

    private final Plugin plugin;
    private final GuideService guide;
    private final GuideContextBuilder context;
    private final Messages messages;
    private final Tasks tasks;
    private final String skin;
    private volatile GuideBehaviour behaviour;
    private final NamespacedKey marker;
    private final NamespacedKey metKey;
    private final NamespacedKey stateKey;
    private final File locationFile;

    private final Map<UUID, Long> talking = new ConcurrentHashMap<>();
    private final List<Location> route = new CopyOnWriteArrayList<>();
    private volatile Location location;
    private UUID entityId;
    private final Map<Mark, UUID> markerIds = new java.util.EnumMap<>(Mark.class);
    private int routeIndex;
    private long pauseUntil;
    private long ticks;
    private boolean bobUp;
    private Location wanderTarget;
    private long lastWanderLog;
    private UUID recorder;
    private Location lastRecorded;

    public GuideNpc(Plugin plugin, GuideService guide, GuideContextBuilder context, Messages messages, Tasks tasks,
                    String skin, GuideBehaviour behaviour) {
        this.plugin = plugin;
        this.guide = guide;
        this.context = context;
        this.messages = messages;
        this.tasks = tasks;
        this.skin = skin == null ? "" : skin.strip();
        this.behaviour = behaviour;
        this.marker = new NamespacedKey(plugin, "guide_npc");
        this.metKey = new NamespacedKey(plugin, "guide_met");
        this.stateKey = new NamespacedKey(plugin, "guide_state");
        this.locationFile = new File(plugin.getDataFolder(), "guide-position.yml");
    }

    public String name() {
        return guide.config().name();
    }

    /** Starts the upkeep loops: one slow one (figure, conversations) and one fast one (walking, symbol). */
    public void start() {
        loadPosition();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::step, 40L, 2L);
    }

    public void shutdown() {
        talking.clear();
        removeFigure();
    }

    public boolean figurePresent() {
        Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
        return entity != null && entity.isValid();
    }

    public Location location() {
        return location == null ? null : location.clone();
    }

    /** Moves the guide's post to {@code target} and remembers it across restarts. */
    public void moveTo(Location target) {
        location = target.clone();
        savePosition();
        removeFigure();
        tick();
    }

    // ---- route (recorded in game)

    public int routeSize() {
        return route.size();
    }

    /** True when he has no recorded route and wanders around his post on his own. */
    public boolean wandering() {
        return behaviour.patrol() && behaviour.wander() && route.size() < 2;
    }

    public boolean patrolEnabled() {
        return behaviour.patrol();
    }

    /** Adds one point to the end of the route. */
    public int addRoutePoint(Location point) {
        if (route.size() < MAX_ROUTE_POINTS) {
            route.add(point.clone());
            savePosition();
        }
        return route.size();
    }

    public void clearRoute() {
        route.clear();
        recorder = null;
        routeIndex = 0;
        savePosition();
    }

    /** Starts recording: from now on every couple of blocks the admin walks becomes a route point. */
    public void startRecording(Player admin) {
        route.clear();
        routeIndex = 0;
        recorder = admin.getUniqueId();
        lastRecorded = null;
    }

    /** Stops recording and saves the route. Returns the number of points. */
    public int stopRecording() {
        recorder = null;
        lastRecorded = null;
        routeIndex = 0;
        savePosition();
        return route.size();
    }

    /**
     * Starts a player over with Brage: forgets that they have met him (the gold "!" returns), clears the symbol the quests set,
     * ends any conversation and wipes his memory of them. The quest system will hook its own reset in here.
     */
    public void resetProgress(Player player) {
        player.getPersistentDataContainer().remove(metKey);
        player.getPersistentDataContainer().remove(stateKey);
        talking.remove(player.getUniqueId());
        guide.forget(player.getUniqueId());
        refreshMarker(player);
    }

    /** Applies new walking, symbol and sound settings (from guide.yml) without a restart: he is rebuilt with them. */
    public void reloadBehaviour(GuideBehaviour fresh) {
        behaviour = fresh;
        wanderTarget = null;
        removeFigure();
        tick();
    }

    public boolean recording() {
        return recorder != null;
    }

    public void onQuit(UUID player) {
        talking.remove(player);
        guide.forget(player);
        if (player.equals(recorder)) {
            stopRecording();
        }
    }

    // ---- upkeep

    private void tick() {
        keepFigure();
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Long>> it = talking.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!isNear(player) || now - entry.getValue() > IDLE_MILLIS) {
                it.remove();
                farewell(player);
            }
        }
    }

    private void keepFigure() {
        if (!guide.config().enabled() || location == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }
        if (!figurePresent()) {
            Mannequin figure = world.spawn(location, Mannequin.class, this::dress);
            entityId = figure.getUniqueId();
            routeIndex = 0;
        }
        if (behaviour.marker() && !markerPresent()) {
            spawnMarker();
        }
    }

    private void dress(Mannequin figure) {
        figure.setPersistent(false);
        figure.setInvulnerable(true);
        figure.setImmovable(true);
        figure.setGravity(false);
        figure.setSilent(true);
        figure.customName(Component.text(name(), NamedTextColor.GOLD));
        figure.setCustomNameVisible(true);
        figure.setDescription(null);
        figure.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        if (!skin.isEmpty() && skin.matches("^[!-~]{1,16}$")) {
            figure.setProfile(ResolvableProfile.resolvableProfile().name(skin).build());
        }
        figure.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
        figure.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        figure.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
        figure.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
    }

    private void removeFigure() {
        Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
        entityId = null;
        removeMarkers();
    }

    private void removeMarkers() {
        for (UUID id : markerIds.values()) {
            Entity symbol = Bukkit.getEntity(id);
            if (symbol != null) {
                symbol.remove();
            }
        }
        markerIds.clear();
    }

    private Entity figure() {
        Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
        return entity != null && entity.isValid() ? entity : null;
    }

    // ---- the symbol above his head

    /** What a player sees above his head: a gold "!" (a quest to take), a green check (a quest ready to hand in) or nothing. */
    public enum Mark {
        AVAILABLE, READY, NONE
    }

    private static final Mark[] SHOWN = {Mark.AVAILABLE, Mark.READY};

    private boolean markerPresent() {
        for (Mark mark : SHOWN) {
            UUID id = markerIds.get(mark);
            Entity entity = id == null ? null : Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                return false;
            }
        }
        return true;
    }

    /** Spawns both symbols; each player is then shown only the one that matches their own quest state. */
    private void spawnMarker() {
        Entity figure = figure();
        if (figure == null) {
            return;
        }
        removeMarkers();
        Location at = figure.getLocation().add(0, MARKER_HEIGHT, 0);
        for (Mark mark : SHOWN) {
            Entity symbol = behaviour.emblem() ? spawnEmblem(figure, at, mark) : spawnTextSymbol(figure, at, mark);
            markerIds.put(mark, symbol.getUniqueId());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshMarker(player);
        }
    }

    /**
     * A floating item with a custom model value. With the NORDIA resource pack it becomes the gold "!" (a nether star) or the
     * green check (an emerald); without the pack players see the plain nether star or emerald.
     */
    private Entity spawnEmblem(Entity figure, Location at, Mark mark) {
        boolean ready = mark == Mark.READY;
        ItemStack stack = new ItemStack(ready ? Material.EMERALD : Material.NETHER_STAR);
        ItemMeta meta = stack.getItemMeta();
        CustomModelDataComponent model = meta.getCustomModelDataComponent();
        model.setStrings(List.of(ready ? "nordia:quest_ready" : "nordia:quest_available"));
        meta.setCustomModelDataComponent(model);
        stack.setItemMeta(meta);
        return figure.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setViewRange(0.5f);
            d.setTeleportDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(EMBLEM_SCALE, EMBLEM_SCALE, EMBLEM_SCALE), new AxisAngle4f()));
            d.setVisibleByDefault(false);
        });
    }

    private Entity spawnTextSymbol(Entity figure, Location at, Mark mark) {
        boolean ready = mark == Mark.READY;
        return figure.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Component.text(ready ? "✔" : behaviour.symbol(), ready ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                    TextDecoration.BOLD));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setPersistent(false);
            d.setViewRange(0.5f);
            d.setTeleportDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));
            d.setVisibleByDefault(false);
        });
    }

    /** Shows each player the symbol that matches their own state (or none) and hides the others. */
    private void refreshMarker(Player player) {
        Mark wanted = markOf(player);
        for (Map.Entry<Mark, UUID> entry : markerIds.entrySet()) {
            Entity symbol = Bukkit.getEntity(entry.getValue());
            if (symbol == null) {
                continue;
            }
            if (entry.getKey() == wanted) {
                player.showEntity(plugin, symbol);
            } else {
                player.hideEntity(plugin, symbol);
            }
        }
    }

    /**
     * What this player sees above him. A state set by the quest system wins; otherwise a new player sees the gold "!" until
     * they have talked to him once.
     */
    public Mark markOf(Player player) {
        String stored = player.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
        if (stored != null) {
            try {
                return Mark.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return hasMet(player) && behaviour.hideAfterTalk() ? Mark.NONE : Mark.AVAILABLE;
    }

    /** Sets the symbol a player sees (the quest system calls this when a quest is taken or finished). */
    public void setMark(Player player, Mark mark) {
        player.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, mark.name());
        refreshMarker(player);
    }

    private boolean hasMet(Player player) {
        return player.getPersistentDataContainer().has(metKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshMarker(player), 20L);
    }

    // ---- walking

    /**
     * Runs every 2 ticks: keeps the symbol on his head, records the route, and walks him. With a recorded route (2+ points) he
     * follows it; without one he wanders to random spots near his post, only on floor he can stand on and (by default) only
     * under a roof, so he stays inside the building.
     */
    private void step() {
        ticks += 2;
        Entity figure = figure();
        if (figure == null) {
            return;
        }
        Location current = figure.getLocation();
        moveMarker(current);
        recordRoute();
        boolean followRoute = route.size() >= 2;
        if (!behaviour.patrol() || recorder != null || (!followRoute && !behaviour.wander())) {
            return;
        }
        Player talker = nearestTalker(current);
        if (talker != null) {
            face(figure, current, talker.getLocation());
            wanderTarget = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (!anyoneNear(current) || now < pauseUntil) {
            return;
        }
        if (followRoute) {
            if (walkToward(figure, current, route.get(routeIndex % route.size()))) {
                routeIndex = (routeIndex + 1) % route.size();
                if (routeIndex == 0 && behaviour.pauseSeconds() > 0) {
                    pauseUntil = now + behaviour.pauseSeconds() * 1000L;
                }
            }
            return;
        }
        if (wanderTarget == null) {
            wanderTarget = pickWanderSpot(current);
            if (wanderTarget == null) {
                pauseUntil = now + 2_000L;
                return;
            }
        }
        if (walkToward(figure, current, wanderTarget)) {
            wanderTarget = null;
            pauseUntil = now + 3_000L + ThreadLocalRandom.current().nextLong(7_000L);
        }
    }

    /** One small step toward the target. Returns true when he has arrived. */
    private boolean walkToward(Entity figure, Location current, Location target) {
        if (!target.getWorld().equals(current.getWorld())) {
            figure.teleport(target);
            return true;
        }
        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double stride = behaviour.speed() * 0.1;
        if (distance <= stride) {
            figure.teleport(new Location(current.getWorld(), target.getX(), target.getY(), target.getZ(), current.getYaw(), 0f));
            return true;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        figure.teleport(new Location(current.getWorld(), current.getX() + dx / distance * stride,
                current.getY() + dy / distance * stride, current.getZ() + dz / distance * stride, yaw, 0f));
        return false;
    }

    // ---- wandering (no recorded route needed)

    /** A random reachable spot near his post, or null if a few tries found nothing. */
    private Location pickWanderSpot(Location current) {
        World world = current.getWorld();
        Location post = location != null && world.equals(location.getWorld()) ? location : current;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radius = behaviour.wanderRadius();
        // "Inside a building" only makes sense if his post is inside one. If the post is outdoors he may still wander there.
        boolean needRoof = behaviour.requireRoof() && roofed(world, post.getBlockX(), post.getBlockY(), post.getBlockZ());
        int noFloor = 0;
        int noRoof = 0;
        int blocked = 0;
        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = random.nextDouble(0, Math.PI * 2);
            double distance = 1.5 + random.nextDouble() * Math.max(0.1, radius - 1.5);
            int x = (int) Math.floor(post.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(post.getZ() + Math.sin(angle) * distance);
            Integer y = standingY(world, x, post.getBlockY(), z);
            if (y == null) {
                noFloor++;
                continue;
            }
            if (needRoof && !roofed(world, x, y, z)) {
                noRoof++;
                continue;
            }
            if (!pathClear(world, current, x, y, z, needRoof)) {
                blocked++;
                continue;
            }
            double lift = world.getBlockAt(x, y, z).getType().name().endsWith("_CARPET") ? 0.0625 : 0.0;
            return new Location(world, x + 0.5, y + lift, z + 0.5);
        }
        long now = System.currentTimeMillis();
        if (now - lastWanderLog > 60_000L) {
            lastWanderLog = now;
            plugin.getLogger().info("Brage found no place to walk (tried 14 spots: " + noFloor + " without floor, " + noRoof
                    + " without roof, " + blocked + " with a blocked way). His post is at " + post.getBlockX() + " " + post.getBlockY()
                    + " " + post.getBlockZ() + (needRoof ? " (inside, so he stays under a roof)" : " (outdoors)")
                    + ". Move him with /brage flytta, or change patrol.wander in guide.yml.");
        }
        return null;
    }

    /** The y a figure can stand at in this column, trying the level of the post first, then one up and one down. */
    private Integer standingY(World world, int x, int aroundY, int z) {
        for (int dy : new int[]{0, 1, -1}) {
            if (walkable(world, x, aroundY + dy, z)) {
                return aroundY + dy;
            }
        }
        return null;
    }

    /** Solid floor below, and free space (no liquid, no fire or cactus) for feet and head. */
    private boolean walkable(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Block floor = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        // A carpet has a collision box but is only a hair high: he walks on it, so it counts as free space for the feet.
        boolean feetFree = feet.isPassable() || feet.getType().name().endsWith("_CARPET");
        return !floor.isPassable() && !floor.isLiquid() && !WALK_DANGER.contains(floor.getType())
                && feetFree && !feet.isLiquid() && !WALK_DANGER.contains(feet.getType())
                && head.isPassable() && !head.isLiquid();
    }

    /** True if something solid is above within a few blocks (so the spot is inside a building). */
    private boolean roofed(World world, int x, int y, int z) {
        for (int h = 2; h <= 24; h++) {
            if (!world.getBlockAt(x, y + h, z).isPassable()) {
                return true;
            }
        }
        return false;
    }

    /** Walks the straight line in half-block steps and checks that every column on the way can be walked on (one-block steps are fine). */
    private boolean pathClear(World world, Location from, int tx, int ty, int tz, boolean needRoof) {
        double dx = tx + 0.5 - from.getX();
        double dz = tz + 0.5 - from.getZ();
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / 0.5);
        int y = from.getBlockY();
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            int sx = (int) Math.floor(from.getX() + dx * t);
            int sz = (int) Math.floor(from.getZ() + dz * t);
            if (sx == lastX && sz == lastZ) {
                continue;
            }
            Integer next = null;
            for (int candidate : new int[]{y, y + 1, y - 1}) {
                if (walkable(world, sx, candidate, sz)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null || (needRoof && !roofed(world, sx, next, sz))) {
                return false;
            }
            y = next;
            lastX = sx;
            lastZ = sz;
        }
        return Math.abs(y - ty) <= 1;
    }

    /**
     * Keeps the symbols above his head. They follow him only when he has actually moved (a short smooth glide instead of a
     * jump), and the gentle up-and-down is done by the client itself: once a second the symbol is told to slide to its new
     * height over a second. That is two small packets per second instead of twenty teleports.
     */
    private void moveMarker(Location figureLocation) {
        Location at = figureLocation.clone().add(0, MARKER_HEIGHT, 0);
        boolean bobNow = ticks % 20 == 0;
        if (bobNow) {
            bobUp = !bobUp;
        }
        for (UUID id : markerIds.values()) {
            Entity symbol = Bukkit.getEntity(id);
            if (symbol == null) {
                continue;
            }
            Location now = symbol.getLocation();
            if (!now.getWorld().equals(at.getWorld()) || now.distanceSquared(at) > 0.0004) {
                symbol.teleport(at);
            }
            if (bobNow && symbol instanceof Display display) {
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(20);
                display.setTransformation(new Transformation(new Vector3f(0f, bobUp ? BOB : 0f, 0f), new AxisAngle4f(),
                        new Vector3f(EMBLEM_SCALE, EMBLEM_SCALE, EMBLEM_SCALE), new AxisAngle4f()));
            }
        }
    }

    private void recordRoute() {
        if (recorder == null) {
            return;
        }
        Player admin = Bukkit.getPlayer(recorder);
        if (admin == null) {
            stopRecording();
            return;
        }
        Location here = admin.getLocation();
        if (lastRecorded == null || !lastRecorded.getWorld().equals(here.getWorld())
                || lastRecorded.distance(here) >= RECORD_SPACING) {
            if (route.size() < MAX_ROUTE_POINTS) {
                route.add(here.clone());
                lastRecorded = here.clone();
            }
        }
    }

    private Player nearestTalker(Location from) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID id : talking.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().equals(from.getWorld())) {
                double d = player.getLocation().distanceSquared(from);
                if (d < best) {
                    best = d;
                    nearest = player;
                }
            }
        }
        return nearest;
    }

    private boolean anyoneNear(Location from) {
        for (Player player : from.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(from) <= ACTIVE_DISTANCE * ACTIVE_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    private void face(Entity figure, Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        figure.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);
    }

    // ---- saved position and route

    private void savePosition() {
        Location post = location;
        if (post == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", post.getWorld().getName());
        yaml.set("x", post.getX());
        yaml.set("y", post.getY());
        yaml.set("z", post.getZ());
        yaml.set("yaw", (double) post.getYaw());
        List<String> points = new ArrayList<>();
        for (Location p : route) {
            points.add(p.getWorld().getName() + ";" + p.getX() + ";" + p.getY() + ";" + p.getZ());
        }
        yaml.set("route", points);
        try {
            yaml.save(locationFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the guide position", e);
        }
    }

    private void loadPosition() {
        route.clear();
        if (locationFile.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(locationFile);
            World world = Bukkit.getWorld(yaml.getString("world", ""));
            if (world != null) {
                location = new Location(world, yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                        (float) yaml.getDouble("yaw"), 0f);
                for (String line : yaml.getStringList("route")) {
                    String[] parts = line.split(";");
                    World pointWorld = parts.length == 4 ? Bukkit.getWorld(parts[0]) : null;
                    if (pointWorld != null) {
                        try {
                            route.add(new Location(pointWorld, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                                    Double.parseDouble(parts[3])));
                        } catch (NumberFormatException ignored) {
                            // skip a damaged line
                        }
                    }
                }
                return;
            }
        }
        if (!Bukkit.getWorlds().isEmpty()) {
            // Default: next to the world spawn. Admins fine-tune it with /brage flytta.
            location = Bukkit.getWorlds().getFirst().getSpawnLocation().add(2.5, 0, 0.5);
        }
    }

    private boolean isNear(Player player) {
        Entity figure = figure();
        Location target = figure != null ? figure.getLocation() : location;
        return target != null && player.getWorld().equals(target.getWorld())
                && player.getLocation().distanceSquared(target) <= TALK_DISTANCE * TALK_DISTANCE;
    }

    // ---- talking

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Mannequin figure)
                || !figure.getPersistentDataContainer().has(marker, PersistentDataType.BYTE)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        met(player);
        if (talking.remove(player.getUniqueId()) != null) {
            farewell(player);
            return;
        }
        if (!guide.aiAvailable()) {
            messages.send(player, "guide.greeting_noai", "name", name(), "player", player.getName());
            return;
        }
        talking.put(player.getUniqueId(), System.currentTimeMillis());
        messages.send(player, "guide.greeting", "name", name(), "player", player.getName());
        talkSound(player, 40);
    }

    /** Remembers that this player has spoken to him, so the symbol disappears for them. */
    private void met(Player player) {
        player.getPersistentDataContainer().set(metKey, PersistentDataType.BYTE, (byte) 1);
        refreshMarker(player);
    }

    /**
     * Chat from a player who is talking to the guide never reaches public chat. Cancelled first (LOWEST) so no other
     * plugin broadcasts it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!talking.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        talking.put(player.getUniqueId(), System.currentTimeMillis());
        tasks.sync(() -> ask(player, text));
    }

    private void ask(Player player, String text) {
        SupportedLocale locale = messages.localeOf(player);
        messages.send(player, "guide.you", "text", text);
        player.sendActionBar(messages.render(player, "guide.thinking", "name", name()));
        UUID id = player.getUniqueId();
        tasks.run(player, () -> guide.reply(id, locale, context.build(id, locale), text), answer -> {
            messages.send(player, "guide.say", "name", name(), "text", answer);
            talkSound(player, answer.length());
        });
    }

    /** A short burst of mumbling that is longer for longer answers (there is no real voice, only text). */
    private void talkSound(Player player, int length) {
        if (!behaviour.talkSound()) {
            return;
        }
        Entity figure = figure();
        if (figure == null) {
            return;
        }
        int syllables = Math.clamp(length / 20, 2, 8);
        for (int i = 0; i < syllables; i++) {
            float pitch = 0.75f + (float) ((i * 37) % 7) * 0.06f;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && figure.isValid()) {
                    player.playSound(figure.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.7f, pitch);
                }
            }, i * 3L);
        }
    }

    private void farewell(Player player) {
        messages.send(player, "guide.farewell", "name", name(), "player", player.getName());
    }
}
