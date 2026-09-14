package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.paper.inventory.ItemTransfer;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.production.ProductionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /production recipes|start|status} (alias {@code /produktion}). */
public final class ProductionCommands {

    private final CommandServices svc;

    public ProductionCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Produktion / Production", List.of("produktion"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("production")
                .executes(c -> recipes(c.getSource().getSender()))
                .then(Commands.literal("recipes").executes(c -> recipes(c.getSource().getSender())))
                .then(Commands.literal("start").then(Commands.argument("recipe", StringArgumentType.word())
                        .suggests((c, b) -> {
                            svc.core().production().config().recipes().keySet().stream()
                                    .filter(id -> id.startsWith(b.getRemainingLowerCase())).sorted().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(c -> start(c, 1))
                        .then(Commands.argument("batches", IntegerArgumentType.integer(1, 64))
                                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "batches"))))))
                .then(Commands.literal("status").executes(this::status))
                .build();
    }

    private static Component materials(Map<String, Integer> items) {
        Component result = Component.empty();
        boolean first = true;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (!first) {
                result = result.append(Component.text(", "));
            }
            Material m = Material.matchMaterial(e.getKey());
            result = result.append(Component.text(e.getValue() + "× "))
                    .append(m == null ? Component.text(e.getKey()) : Component.translatable(m.translationKey()));
            first = false;
        }
        return result;
    }

    private int recipes(CommandSender sender) {
        svc.messages().send(sender, "production.recipes.header");
        svc.core().production().config().recipes().values().stream()
                .sorted(java.util.Comparator.comparing(ProductionService.Recipe::id))
                .forEach(r -> svc.messages().send(sender, "production.recipes.entry", "id", r.id(), "inputs", materials(r.inputs()),
                        "outputs", materials(r.outputs()), "seconds", r.seconds(), "level", r.engineeringLevel()));
        return Command.SINGLE_SUCCESS;
    }

    private Company company(UUID player) {
        Long selected = svc.sessions().get(player).map(PlayerSession::selectedCompanyId).orElse(null);
        return selected != null ? svc.core().companies().find(selected).orElseThrow()
                : svc.core().companies().resolveForActor(player, null);
    }

    private int start(CommandContext<CommandSourceStack> c, int batches) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String recipe = StringArgumentType.getString(c, "recipe");
        svc.tasks().run(player, () -> svc.core().production().start(uuid, company(uuid).id(), recipe, batches, ItemTransfer.CODEC),
                run -> svc.messages().send(player, "production.started", "recipe", run.recipe(), "batches", run.batches(),
                        "finishes", run.finishesAt()));
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().production().runs(company(uuid).id(), 10), runs -> {
            if (runs.isEmpty()) {
                svc.messages().send(player, "production.status.empty");
                return;
            }
            svc.messages().send(player, "production.status.header");
            for (ProductionService.Run run : runs) {
                svc.messages().send(player, run.completed() ? "production.status.done" : "production.status.running",
                        "id", run.id(), "recipe", run.recipe(), "batches", run.batches(), "finishes", run.finishesAt());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
