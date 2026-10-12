package se.nordia.plugins.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import se.nordia.plugins.paper.gui.TexturePack;

import java.util.List;

/** {@code /texturer}: switches the optional texture pack on or off. */
public final class TexturePackCommand {

    private final CommandServices svc;
    private final TexturePack texturePack;

    public TexturePackCommand(CommandServices svc, TexturePack texturePack) {
        this.svc = svc;
        this.texturePack = texturePack;
    }

    public void register(Commands commands) {
        commands.register(node(), "Switch the texture pack on or off / Slå på eller av texturepaketet", List.of("texturepack"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("texturer")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        texturePack.toggle(player);
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
