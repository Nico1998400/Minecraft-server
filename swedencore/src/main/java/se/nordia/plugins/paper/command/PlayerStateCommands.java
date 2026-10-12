package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import se.nordia.plugins.paper.essentials.EssentialsListener;
import se.nordia.plugins.paper.Permissions;

import java.util.List;
import java.util.Locale;

/**
 * Commands that change how you or another player are: {@code /heal /feed /fly /god /gms /gmc /gma /gmsp /speed /vanish
 * /suicide /burn /ext /lightning /hat /more /repair /afk}.
 */
public final class PlayerStateCommands {

    private static final float WALK_DEFAULT = 0.2f;
    private static final float FLY_DEFAULT = 0.1f;

    private final EssentialsKit kit;
    private final EssentialsListener listener;

    public PlayerStateCommands(EssentialsKit kit, EssentialsListener listener) {
        this.kit = kit;
        this.listener = listener;
    }

    public void register(Commands commands) {
        commands.register(kit.selfOrOther("heal", "heal", (s, t) -> {
            AttributeInstance max = t.getAttribute(Attribute.MAX_HEALTH);
            t.setHealth(max == null ? 20.0 : max.getValue());
            t.setFoodLevel(20);
            t.setSaturation(20f);
            t.setFireTicks(0);
            kit.notifyBoth(s, t, "ess.healed", "ess.healed_other");
        }).build(), "Hela / Heal", List.of());

        commands.register(kit.selfOrOther("feed", "feed", (s, t) -> {
            t.setFoodLevel(20);
            t.setSaturation(20f);
            kit.notifyBoth(s, t, "ess.fed", "ess.fed_other");
        }).build(), "Mätta / Feed", List.of("eat"));

        commands.register(kit.selfOrOther("fly", "fly", (s, t) -> {
            boolean on = !t.getAllowFlight();
            t.setAllowFlight(on);
            if (!on) {
                t.setFlying(false);
            }
            toggled(s, t, "ess.feature.fly", on);
        }).build(), "Flyg / Fly", List.of());

        commands.register(kit.selfOrOther("god", "god", (s, t) -> {
            boolean on = !t.isInvulnerable();
            t.setInvulnerable(on);
            toggled(s, t, "ess.feature.god", on);
        }).build(), "Odödlig / God mode", List.of("godmode"));

        mode(commands, "gms", GameMode.SURVIVAL);
        mode(commands, "gmc", GameMode.CREATIVE);
        mode(commands, "gma", GameMode.ADVENTURE);
        mode(commands, "gmsp", GameMode.SPECTATOR);

        commands.register(speed().build(), "Hastighet / Speed", List.of());

        commands.register(kit.selfOrOther("vanish", "vanish", (s, t) -> {
            boolean on = !kit.state().vanished.contains(t.getUniqueId());
            listener.setVanished(t, on);
            toggled(s, t, "ess.feature.vanish", on);
            kit.log(s.getName() + " set vanish " + (on ? "on" : "off") + " for " + t.getName());
        }).build(), "Osynlig / Vanish", List.of("v"));

        commands.register(EssentialsKit.literal("suicide", "suicide").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                p.setHealth(0);
                kit.messages().send(p, "ess.suicide");
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Ta ditt liv / End your life", List.of());

        commands.register(EssentialsKit.literal("burn", "burn")
                .then(EssentialsKit.playerArg()
                        .executes(c -> burn(c.getSource().getSender(), StringArgumentType.getString(c, "player"), 5))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                .executes(c -> burn(c.getSource().getSender(), StringArgumentType.getString(c, "player"),
                                        IntegerArgumentType.getInteger(c, "seconds")))))
                .build(), "Sätt eld på / Set on fire", List.of());

        commands.register(kit.selfOrOther("ext", "ext", (s, t) -> {
            t.setFireTicks(0);
            kit.notifyBoth(s, t, "ess.extinguished", "ess.extinguished_other");
        }).build(), "Släck eld / Extinguish", List.of("extinguish"));

        commands.register(EssentialsKit.literal("lightning", "lightning")
                .executes(c -> {
                    Player p = kit.executorPlayer(c);
                    if (p != null) {
                        var block = p.getTargetBlockExact(120);
                        p.getWorld().strikeLightning(block == null ? p.getLocation() : block.getLocation());
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(EssentialsKit.playerArg().executes(c -> {
                    Player target = kit.online(c.getSource().getSender(), StringArgumentType.getString(c, "player"));
                    if (target != null) {
                        target.getWorld().strikeLightning(target.getLocation());
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .build(), "Blixt / Lightning", List.of("smite", "thor"));

        commands.register(EssentialsKit.literal("hat", "hat").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                hat(p);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Bär föremålet som hatt / Wear held item", List.of());

        commands.register(EssentialsKit.literal("more", "more").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                ItemStack item = p.getInventory().getItemInMainHand();
                if (item.getType() == Material.AIR) {
                    kit.messages().send(p, "ess.no_item");
                } else {
                    item.setAmount(item.getMaxStackSize());
                    kit.messages().send(p, "ess.more_done");
                }
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Fyll stacken / Fill the stack", List.of());

        commands.register(EssentialsKit.literal("repair", "repair").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                repair(p);
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Laga föremålet / Repair held item", List.of("fix"));

        commands.register(EssentialsKit.literal("afk", "afk").executes(c -> {
            Player p = kit.executorPlayer(c);
            if (p != null) {
                boolean on = kit.state().afk.add(p.getUniqueId());
                if (!on) {
                    kit.state().afk.remove(p.getUniqueId());
                }
                for (Player other : p.getServer().getOnlinePlayers()) {
                    kit.messages().send(other, on ? "ess.afk_on" : "ess.afk_off", "player", p.getName());
                }
            }
            return Command.SINGLE_SUCCESS;
        }).build(), "Markera dig som borta / Mark yourself away", List.of("away"));
    }

    private void mode(Commands commands, String name, GameMode mode) {
        commands.register(kit.selfOrOther(name, name, (s, t) -> {
            t.setGameMode(mode);
            kit.notifyBoth(s, t, "ess.gamemode", "ess.gamemode_other",
                    "mode", mode.name().toLowerCase(Locale.ROOT));
            kit.log(s.getName() + " set gamemode " + mode + " for " + t.getName());
        }).build(), "Spelläge / Game mode", List.of());
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<io.papermc.paper.command.brigadier.CommandSourceStack> speed() {
        return EssentialsKit.literal("speed", "speed")
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 10))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                setSpeed(p, p.isFlying(), (float) DoubleArgumentType.getDouble(c, "amount"));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("walk").then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 10))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                setSpeed(p, false, (float) DoubleArgumentType.getDouble(c, "amount"));
                            }
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("fly").then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 10))
                        .executes(c -> {
                            Player p = kit.executorPlayer(c);
                            if (p != null) {
                                setSpeed(p, true, (float) DoubleArgumentType.getDouble(c, "amount"));
                            }
                            return Command.SINGLE_SUCCESS;
                        })));
    }

    /** Speed 1 is the normal speed, 10 the fastest Minecraft allows, 0 stands still. */
    private void setSpeed(Player player, boolean flying, float factor) {
        float normal = flying ? FLY_DEFAULT : WALK_DEFAULT;
        float real = factor < 1f ? normal * factor : ((factor - 1f) / 9f) * (1f - normal) + normal;
        real = Math.clamp(real, 0f, 1f);
        if (flying) {
            player.setFlySpeed(real);
        } else {
            player.setWalkSpeed(real);
        }
        kit.messages().send(player, "ess.speed", "feature", kit.messages().render(player, flying ? "ess.feature.flyspeed" : "ess.feature.walkspeed"),
                "value", String.valueOf(factor));
    }

    private int burn(CommandSender sender, String name, int seconds) {
        Player target = kit.online(sender, name);
        if (target != null) {
            target.setFireTicks(seconds * 20);
            kit.messages().send(sender, "ess.burned", "player", target.getName(), "seconds", String.valueOf(seconds));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void hat(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            kit.messages().send(player, "ess.no_item");
            return;
        }
        ItemStack old = player.getInventory().getHelmet();
        player.getInventory().setHelmet(held.clone());
        player.getInventory().setItemInMainHand(old == null ? new ItemStack(Material.AIR) : old);
        kit.messages().send(player, "ess.hat_set");
    }

    private void repair(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            damageable.setDamage(0);
            item.setItemMeta(meta);
            kit.messages().send(player, "ess.repaired");
        } else {
            kit.messages().send(player, "ess.repair_none");
        }
    }

    private void toggled(CommandSender sender, Player target, String featureKey, boolean on) {
        var state = kit.state(sender, on);
        if (sender.equals(target)) {
            kit.messages().send(target, "ess.toggle", "feature", kit.messages().render(target, featureKey), "state", state);
        } else {
            kit.messages().send(target, "ess.toggle", "feature", kit.messages().render(target, featureKey), "state", kit.state(target, on));
            kit.messages().send(sender, "ess.toggle_other", "feature", kit.messages().render(sender, featureKey),
                    "player", target.getName(), "state", state);
        }
    }

    /** Used so other classes can reference the permission without a typo. */
    static String permission(String command) {
        return Permissions.ess(command);
    }
}
