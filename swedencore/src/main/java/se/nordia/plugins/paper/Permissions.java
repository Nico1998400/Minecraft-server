package se.nordia.plugins.paper;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.List;

/** Permission nodes. The admin ones are declared in paper-plugin.yml; the essentials commands are registered in code. */
public final class Permissions {

    public static final String ADMIN = "nordia.admin";
    public static final String ADMIN_ECONOMY = "nordia.admin.economy";
    public static final String ADMIN_RELOAD = "nordia.admin.reload";
    public static final String BALANCE_OTHERS = "nordia.balance.others";
    public static final String ADMIN_PROPERTY = "nordia.admin.property";
    public static final String ADMIN_CITY = "nordia.admin.city";
    public static final String ADMIN_SPAWN = "nordia.admin.spawn";
    public static final String ADMIN_BUILDWORLD = "nordia.admin.buildworld";
    public static final String ADMIN_GUIDE = "nordia.admin.guide";
    public static final String ADMIN_TUTORIAL = "nordia.admin.tutorial";
    public static final String ESSENTIALS_BYPASS = "nordia.essentials.bypass";
    public static final String ESSENTIALS_WARP_MANAGE = "nordia.essentials.warp.manage";
    public static final String PROPERTY_BYPASS = "nordia.admin.property.bypass";

    /** Essentials commands every player may use by default. Everything else is for operators (or whoever is granted it). */
    private static final List<String> ESSENTIALS_PUBLIC = List.of(
            "msg", "r", "msgtoggle", "me", "ignore", "online", "seen", "near", "getpos", "compass", "depth", "ping",
            "playtime", "motd", "rules", "info", "discord", "link", "unlink", "tpr", "helpop", "suicide", "afk");

    /** Seeing vanished players and receiving social spy are separate permissions, op by default. */
    public static final String VANISH_SEE = "nordia.essentials.vanish.see";
    public static final String HELPOP_RECEIVE = "nordia.essentials.helpop.receive";
    public static final String MUTE_EXEMPT = "nordia.essentials.mute.exempt";

    private Permissions() {
    }

    /** The node for an essentials command, e.g. {@code ess("heal")} = {@code nordia.essentials.heal}. */
    public static String ess(String command) {
        return "nordia.essentials." + command;
    }

    /** The node that lets a player use a command on other players, e.g. {@code nordia.essentials.heal.others}. */
    public static String essOthers(String command) {
        return ess(command) + ".others";
    }

    /** Registers the essentials nodes so they have the right defaults (players: public ones only; ops: everything). */
    public static void registerEssentials(PluginManager manager, List<String> allCommands) {
        for (String command : allCommands) {
            register(manager, ess(command), ESSENTIALS_PUBLIC.contains(command) ? PermissionDefault.TRUE : PermissionDefault.OP);
            register(manager, essOthers(command), PermissionDefault.OP);
        }
        register(manager, VANISH_SEE, PermissionDefault.OP);
        register(manager, HELPOP_RECEIVE, PermissionDefault.OP);
        register(manager, MUTE_EXEMPT, PermissionDefault.OP);
    }

    private static void register(PluginManager manager, String node, PermissionDefault def) {
        if (manager.getPermission(node) == null) {
            manager.addPermission(new Permission(node, def));
        }
    }
}
