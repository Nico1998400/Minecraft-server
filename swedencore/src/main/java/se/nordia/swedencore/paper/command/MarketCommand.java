package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.market.MarketService;

import java.util.List;
import java.util.Locale;

/** {@code /market <item>} and {@code /market top} (alias {@code /marknad}): prices from real trades. */
public final class MarketCommand {

    private final CommandServices svc;

    public MarketCommand(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Marknadspriser / Market prices", List.of("marknad"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("market")
                .executes(c -> top(c.getSource().getSender()))
                .then(Commands.literal("top").executes(c -> top(c.getSource().getSender())))
                .then(Commands.argument("item", StringArgumentType.word())
                        .executes(c -> report(c.getSource().getSender(), StringArgumentType.getString(c, "item"))))
                .build();
    }

    private static Component itemName(String material) {
        Material m = Material.matchMaterial(material);
        return m == null ? Component.text(material) : Component.translatable(m.translationKey());
    }

    private Object priceOrDash(SupportedLocale locale, Money price) {
        return price == null ? svc.messages().render(locale, "market.no_data") : price;
    }

    private int report(CommandSender sender, String input) {
        Material material = Material.matchMaterial(input.toUpperCase(Locale.ROOT));
        if (material == null) {
            svc.messages().sendError(sender, new DomainException("contract.invalid_material"));
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(sender, () -> svc.core().market().report(material.name()), r -> {
            SupportedLocale locale = svc.messages().localeOf(sender);
            long days = svc.core().market().window().toDays();
            svc.messages().send(sender, "market.header", "item", itemName(r.material()), "days", days);
            svc.messages().send(sender, "market.average", "price", priceOrDash(locale, r.combined().averagePrice()),
                    "volume", r.combined().volume(),
                    "trend", r.trendPercent() == null ? svc.messages().render(locale, "market.no_data")
                            : svc.messages().render(locale, r.trendPercent() >= 0 ? "market.trend_up" : "market.trend_down",
                            "percent", Math.abs(r.trendPercent())));
            svc.messages().send(sender, "market.sources", "shops", priceOrDash(locale, r.shops().averagePrice()),
                    "orders", priceOrDash(locale, r.orders().averagePrice()));
            svc.messages().send(sender, "market.now", "shop", priceOrDash(locale, r.bestShopPrice()),
                    "order", priceOrDash(locale, r.bestOrderPrice()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int top(CommandSender sender) {
        svc.tasks().run(sender, () -> svc.core().market().topTraded(10), list -> {
            if (list.isEmpty()) {
                svc.messages().send(sender, "market.top.empty");
                return;
            }
            svc.messages().send(sender, "market.top.header", "days", svc.core().market().window().toDays());
            int rank = 1;
            for (MarketService.Traded t : list) {
                svc.messages().send(sender, "market.top.entry", "rank", rank++, "item", itemName(t.material()),
                        "volume", t.volume(), "value", t.value());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
