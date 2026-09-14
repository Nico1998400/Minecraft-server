package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.shares.ShareService;

import java.util.List;
import java.util.UUID;

/**
 * {@code /shares} (alias {@code /aktier}): company shares. Companies are given as {@code #id} or a one-word name;
 * owner actions ({@code issue}, {@code sell-treasury}, {@code dividend}) use the selected company or the only one owned.
 */
public final class ShareCommands {

    private final CommandServices svc;

    public ShareCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Aktier / Shares", List.of("aktier"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("shares")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "shares.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("info")
                        .executes(c -> info(c, null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> info(c, StringArgumentType.getString(c, "company")))))
                .then(Commands.literal("portfolio").executes(this::portfolio))
                .then(Commands.literal("market").executes(this::market))
                .then(Commands.literal("offers")
                        .executes(c -> offers(c, null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> offers(c, StringArgumentType.getString(c, "company")))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("company", StringArgumentType.word())
                                .then(sellArguments(false))))
                .then(Commands.literal("sell-treasury").then(sellArguments(true)))
                .then(Commands.literal("issue")
                        .then(Commands.argument("quantity", LongArgumentType.longArg(1)).executes(this::issue)))
                .then(Commands.literal("buy")
                        .then(Commands.argument("offer", LongArgumentType.longArg(1))
                                .then(Commands.argument("quantity", LongArgumentType.longArg(1)).executes(this::buy))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("offer", LongArgumentType.longArg(1)).executes(this::cancel)))
                .then(Commands.literal("dividend")
                        .then(Commands.argument("amount", StringArgumentType.word()).executes(this::dividend)))
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Long> sellArguments(boolean treasury) {
        return Commands.argument("quantity", LongArgumentType.longArg(1))
                .then(Commands.argument("price", StringArgumentType.word())
                        .executes(c -> sell(c, treasury, null))
                        .then(Commands.argument("buyer", StringArgumentType.word())
                                .suggests(CommandServices.onlinePlayerNames())
                                .executes(c -> sell(c, treasury, StringArgumentType.getString(c, "buyer")))));
    }

    /** The company an owner acts for: selected in the session, otherwise the only company they own. */
    private long ownedCompany(UUID uuid, Long selected) {
        return selected != null ? selected : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER).id();
    }

    private Long selected(UUID uuid) {
        return svc.sessions().get(uuid).map(PlayerSession::selectedCompanyId).orElse(null);
    }

    private int info(CommandContext<CommandSourceStack> c, String ref) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        Long selected = selected(uuid);
        svc.tasks().run(player, () -> {
            long companyId = ref != null ? svc.core().companies().requireByRef(ref).id()
                    : selected != null ? selected : svc.core().companies().resolveForActor(uuid, null).id();
            return new Object[]{svc.core().shares().valuation(companyId), svc.core().shares().capTable(companyId, 8)};
        }, result -> {
            ShareService.Valuation v = (ShareService.Valuation) result[0];
            @SuppressWarnings("unchecked")
            List<ShareService.Position> holders = (List<ShareService.Position>) result[1];
            svc.messages().send(player, "shares.info.header", "company", v.company().name(), "id", v.company().id());
            svc.messages().send(player, "shares.info.shares", "total", v.totalShares(), "outstanding", v.outstanding(),
                    "treasury", v.treasuryShares());
            svc.messages().send(player, "shares.info.book", "equity", v.equity(), "per_share", v.bookValuePerShare());
            if (v.lastPrice() == null) {
                svc.messages().send(player, "shares.info.no_trades");
            } else {
                svc.messages().send(player, "shares.info.market", "last", v.lastPrice(), "average", v.averagePrice30d() == null ? "-" : v.averagePrice30d(),
                        "volume", v.volume30d(), "market_cap", v.marketCap() == null ? "-" : v.marketCap());
            }
            svc.messages().send(player, "shares.info.dividends", "amount", v.dividends30d());
            for (ShareService.Position h : holders) {
                String name = h.holderType() == ShareService.HolderType.TREASURY
                        ? svc.messages().raw(svc.messages().localeOf(player), "shares.treasury") : h.holderName();
                svc.messages().send(player, "shares.info.holder", "holder", name == null ? "?" : name, "quantity", h.total(),
                        "listed", h.listed(), "percent", v.totalShares() == 0 ? 0 : h.total() * 100 / v.totalShares());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int portfolio(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().shares().portfolio(uuid), positions -> {
            if (positions.isEmpty()) {
                svc.messages().send(player, "shares.portfolio.empty");
                return;
            }
            svc.messages().send(player, "shares.portfolio.header");
            for (ShareService.Position p : positions) {
                svc.messages().send(player, "shares.portfolio.entry", "company", p.companyName(), "id", p.companyId(),
                        "quantity", p.total(), "listed", p.listed());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int market(CommandContext<CommandSourceStack> c) {
        var sender = c.getSource().getSender();
        svc.tasks().run(sender, () -> svc.core().shares().market(10), listings -> {
            if (listings.isEmpty()) {
                svc.messages().send(sender, "shares.market.empty");
                return;
            }
            svc.messages().send(sender, "shares.market.header");
            for (ShareService.Listing l : listings) {
                svc.messages().send(sender, "shares.market.entry", "company", l.companyName(), "id", l.companyId(),
                        "ask", l.bestAsk() == null ? "-" : l.bestAsk(), "ask_quantity", l.askQuantity(),
                        "last", l.lastPrice() == null ? "-" : l.lastPrice(), "volume", l.volume30d(), "turnover", l.turnover30d());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int offers(CommandContext<CommandSourceStack> c, String ref) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Long companyId = ref == null ? null : svc.core().companies().requireByRef(ref).id();
            return svc.core().shares().openOffers(uuid, companyId, 15);
        }, offers -> {
            if (offers.isEmpty()) {
                svc.messages().send(player, "shares.offers.empty");
                return;
            }
            svc.messages().send(player, "shares.offers.header");
            for (ShareService.Offer o : offers) {
                String seller = o.sellerType() == ShareService.HolderType.TREASURY
                        ? svc.messages().raw(svc.messages().localeOf(player), "shares.treasury") : o.sellerName();
                svc.messages().send(player, o.buyer() == null ? "shares.offers.entry" : "shares.offers.entry_private",
                        "id", o.id(), "company", o.companyName(), "quantity", o.remaining(), "price", o.pricePerShare(),
                        "seller", seller == null ? "?" : seller, "buyer", o.buyerName() == null ? "?" : o.buyerName(),
                        "expires", o.expiresAt());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int sell(CommandContext<CommandSourceStack> c, boolean treasury, String buyerName) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String ref = treasury ? null : StringArgumentType.getString(c, "company");
        long quantity = LongArgumentType.getLong(c, "quantity");
        String priceInput = StringArgumentType.getString(c, "price");
        Long selected = selected(uuid);
        svc.tasks().run(player, () -> {
            Money price = Money.parsePositive(priceInput);
            long companyId = treasury ? ownedCompany(uuid, selected) : svc.core().companies().requireByRef(ref).id();
            UUID buyer = buyerName == null ? null : svc.core().players().requireByName(buyerName).uuid();
            return svc.core().shares().offer(uuid, companyId, treasury, quantity, price, buyer,
                    svc.core().shares().config().maxOfferHours());
        }, offer -> {
            svc.messages().send(player, "shares.offered", "id", offer.id(), "quantity", offer.remaining(), "company", offer.companyName(),
                    "price", offer.pricePerShare());
            if (offer.buyer() != null) {
                Player online = Bukkit.getPlayer(offer.buyer());
                if (online != null) {
                    svc.messages().send(online, "shares.offer_notice", "seller", player.getName(), "quantity", offer.remaining(),
                            "company", offer.companyName(), "price", offer.pricePerShare(), "id", offer.id());
                }
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int issue(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long quantity = LongArgumentType.getLong(c, "quantity");
        Long selected = selected(uuid);
        svc.tasks().run(player, () -> svc.core().shares().issue(uuid, ownedCompany(uuid, selected), quantity),
                total -> svc.messages().send(player, "shares.issued", "quantity", quantity, "total", total));
        return Command.SINGLE_SUCCESS;
    }

    private int buy(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long offerId = LongArgumentType.getLong(c, "offer");
        long quantity = LongArgumentType.getLong(c, "quantity");
        svc.tasks().run(player, () -> svc.core().shares().buy(uuid, offerId, quantity),
                trade -> svc.messages().send(player, "shares.bought", "quantity", trade.quantity(), "total", trade.total(), "id", offerId));
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long offerId = LongArgumentType.getLong(c, "offer");
        svc.tasks().run(player, () -> svc.core().shares().cancel(uuid, offerId),
                offer -> svc.messages().send(player, "shares.cancelled", "id", offer.id()));
        return Command.SINGLE_SUCCESS;
    }

    private int dividend(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String amountInput = StringArgumentType.getString(c, "amount");
        Long selected = selected(uuid);
        svc.tasks().run(player, () -> {
            long companyId = ownedCompany(uuid, selected);
            Company company = svc.core().companies().find(companyId).orElseThrow();
            return new Object[]{company, svc.core().shares().declareDividend(uuid, companyId, Money.parsePositive(amountInput))};
        }, result -> {
            Company company = (Company) result[0];
            ShareService.Dividend d = (ShareService.Dividend) result[1];
            svc.messages().send(player, "shares.dividend_paid", "company", company.name(), "total", d.total(), "per_share", d.perShare(),
                    "recipients", d.recipients());
        });
        return Command.SINGLE_SUCCESS;
    }
}
