package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import se.nordia.plugins.paper.gui.MainMenu;

import java.util.List;

/** {@code /meny}: opens the main menu. */
public final class MenuCommand {

    private final CommandServices svc;
    private final MainMenu menu;

    public MenuCommand(CommandServices svc, MainMenu menu) {
        this.svc = svc;
        this.menu = menu;
    }

    public void register(Commands commands) {
        commands.register(node(), "Open the main menu / Öppna huvudmenyn", List.of("menu"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("meny")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        menu.open(player);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
