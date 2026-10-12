package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import java.util.List;
import java.util.Locale;

/** The world around you: {@code /sun /rain /storm /day /night /ptime /pweather /butcher /remove /spawnmob}. */
public final class WorldCommands {

    private static final long DAY = 1_000L;
    private static final long NIGHT = 13_000L;
    private static final int MAX_RADIUS = 1_000;
    private static final int MAX_SPAWN = 20;

    private final EssentialsKit kit;

    public WorldCommands(EssentialsKit kit) {
        this.kit = kit;
    }

    public void register(Commands commands) {
        weather(commands, "sun", "ess.weather.sun", false, false);
        weather(commands, "rain", "ess.weather.rain", true, false);
        weather(commands, "storm", "ess.weather.storm", true, true);

        commands.register(EssentialsKit.literal("day", "time").executes(c -> setTime(c.getSource().getSender(), DAY, "ess.time.day"))
                .build(), "Sätt dag / Set day", List.of());
        commands.register(EssentialsKit.literal("night", "time").executes(c -> setTime(c.getSource().getSender(), NIGHT, "ess.time.night"))
                .build(), "Sätt natt / Set night", List.of());

        commands.register(EssentialsKit.literal("ptime", "ptime")
                .then(Commands.argument("time", StringArgumentType.word())
                        .suggests(EssentialsKit.suggest("day", "night", "noon", "midnight", "reset"))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                playerTime(p, StringArgumentType.getString(c, "time"));
                            }
                            return Command.SINGLE_SUCCESS;
                        })).build(), "Egen tid / Personal time", List.of("playertime"));

        commands.register(EssentialsKit.literal("pweather", "pweather")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests(EssentialsKit.suggest("clear", "rain", "reset"))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                playerWeather(p, StringArgumentType.getString(c, "type"));
                            }
                            return Command.SINGLE_SUCCESS;
                        })).build(), "Eget väder / Personal weather", List.of("playerweather"));

        commands.register(EssentialsKit.literal("butcher", "butcher")
                .executes(c -> remove(c.getSource().getSender(), "hostile", 100))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(c -> remove(c.getSource().getSender(), "hostile", IntegerArgumentType.getInteger(c, "radius"))))
                .build(), "Ta bort fientliga varelser / Remove hostile mobs", List.of());

        commands.register(EssentialsKit.literal("remove", "remove")
                .then(Commands.argument("kind", StringArgumentType.word()).suggests(EssentialsKit.suggest("hostile", "animals", "items", "all"))
                        .executes(c -> remove(c.getSource().getSender(), StringArgumentType.getString(c, "kind"), 100))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS)).executes(c ->
                                remove(c.getSource().getSender(), StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "radius")))))
                .build(), "Ta bort varelser eller föremål / Remove mobs or items", List.of("killall"));

        commands.register(EssentialsKit.literal("spawnmob", "spawnmob")
                .then(Commands.argument("type", StringArgumentType.word())
                        .executes(c -> spawnMob(c.getSource().getSender(), StringArgumentType.getString(c, "type"), 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_SPAWN)).executes(c ->
                                spawnMob(c.getSource().getSender(), StringArgumentType.getString(c, "type"), IntegerArgumentType.getInteger(c, "count")))))
                .build(), "Skapa varelser / Spawn mobs", List.of("mob"));
    }

    private void weather(Commands commands, String name, String messageKey, boolean storm, boolean thunder) {
        commands.register(EssentialsKit.literal(name, "weather")
                .executes(c -> setWeather(c.getSource().getSender(), messageKey, storm, thunder, 0))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86_400)).executes(c ->
                        setWeather(c.getSource().getSender(), messageKey, storm, thunder, IntegerArgumentType.getInteger(c, "seconds"))))
                .build(), "Sätt väder / Set weather", List.of());
    }

    private World worldOf(CommandSender sender) {
        return sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().getFirst();
    }

    private int setWeather(CommandSender sender, String messageKey, boolean storm, boolean thunder, int seconds) {
        World world = worldOf(sender);
        world.setStorm(storm);
        world.setThundering(thunder);
        if (seconds > 0) {
            world.setWeatherDuration(seconds * 20);
            world.setThunderDuration(seconds * 20);
        }
        kit.messages().send(sender, "ess.weather_set", "weather", kit.messages().render(sender, messageKey), "world", world.getName());
        kit.log(sender.getName() + " set weather in " + world.getName() + ": " + messageKey.substring(messageKey.lastIndexOf('.') + 1));
        return Command.SINGLE_SUCCESS;
    }

    private int setTime(CommandSender sender, long time, String messageKey) {
        World world = worldOf(sender);
        world.setTime(time);
        kit.messages().send(sender, "ess.time_set", "time", kit.messages().render(sender, messageKey), "world", world.getName());
        return Command.SINGLE_SUCCESS;
    }

    private void playerTime(Player player, String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "reset" -> player.resetPlayerTime();
            case "day" -> player.setPlayerTime(DAY, false);
            case "noon" -> player.setPlayerTime(6_000L, false);
            case "night" -> player.setPlayerTime(NIGHT, false);
            case "midnight" -> player.setPlayerTime(18_000L, false);
            default -> {
                kit.messages().send(player, "ess.bad_value", "value", raw);
                return;
            }
        }
        kit.messages().send(player, "ess.ptime_set", "value", raw.toLowerCase(Locale.ROOT));
    }

    private void playerWeather(Player player, String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "reset" -> player.resetPlayerWeather();
            case "clear" -> player.setPlayerWeather(WeatherType.CLEAR);
            case "rain" -> player.setPlayerWeather(WeatherType.DOWNFALL);
            default -> {
                kit.messages().send(player, "ess.bad_value", "value", raw);
                return;
            }
        }
        kit.messages().send(player, "ess.pweather_set", "value", raw.toLowerCase(Locale.ROOT));
    }

    /** Removes mobs or dropped items around the sender. Players, named mobs, pets and leashed animals are never touched. */
    private int remove(CommandSender sender, String kind, int radius) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        String what = kind.toLowerCase(Locale.ROOT);
        if (!List.of("hostile", "animals", "items", "all").contains(what)) {
            kit.messages().send(sender, "ess.bad_value", "value", kind);
            return Command.SINGLE_SUCCESS;
        }
        int removed = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Player) {
                continue;
            }
            boolean match = switch (what) {
                case "items" -> entity instanceof Item;
                case "hostile" -> entity instanceof Monster;
                case "animals" -> entity instanceof Animals;
                default -> entity instanceof Item || entity instanceof LivingEntity;
            };
            if (!match || protectedMob(entity)) {
                continue;
            }
            entity.remove();
            removed++;
        }
        kit.messages().send(sender, "ess.removed", "count", removed, "radius", String.valueOf(radius));
        kit.log(sender.getName() + " removed " + removed + " (" + what + ") within " + radius + " blocks");
        return Command.SINGLE_SUCCESS;
    }

    private static boolean protectedMob(Entity entity) {
        if (entity instanceof Item) {
            return false;
        }
        return entity.customName() != null
                || (entity instanceof Tameable tameable && tameable.isTamed())
                || (entity instanceof LivingEntity living && living.isLeashed());
    }

    private int spawnMob(CommandSender sender, String rawType, int count) {
        if (!(sender instanceof Player player)) {
            kit.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            kit.messages().send(player, "ess.bad_value", "value", rawType);
            return Command.SINGLE_SUCCESS;
        }
        if (!type.isSpawnable() || !type.isAlive() || type == EntityType.PLAYER) {
            kit.messages().send(player, "ess.bad_value", "value", rawType);
            return Command.SINGLE_SUCCESS;
        }
        var block = player.getTargetBlockExact(50);
        Location at = block == null ? player.getLocation() : block.getLocation().add(0.5, 1, 0.5);
        for (int i = 0; i < count; i++) {
            player.getWorld().spawnEntity(at, type);
        }
        kit.messages().send(player, "ess.spawned", "count", count, "type", type.name().toLowerCase(Locale.ROOT));
        kit.log(player.getName() + " spawned " + count + " " + type);
        return Command.SINGLE_SUCCESS;
    }
}
