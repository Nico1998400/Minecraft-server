package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.finance.Loan;
import se.nordia.swedencore.finance.LoanService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.session.PlayerSession;

import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * {@code /loan} (alias {@code /lan}). Borrowers are a player name or {@code #<company id>}.
 * {@code /loan offer-company ...} lends the money of the company you own (selected or only one).
 */
public final class LoanCommands {

    private final CommandServices svc;

    public LoanCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Lån / Loans", List.of("lan"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("loan")
                .executes(c -> {
                    svc.messages().send(c.getSource().getSender(), "loan.help");
                    return Command.SINGLE_SUCCESS;
                })
                .then(offerBranch("offer", false))
                .then(offerBranch("offer-company", true))
                .then(Commands.literal("list").executes(this::list))
                .then(idAction("accept", "loan.accepted", (uuid, id) -> svc.core().loans().accept(uuid, id)))
                .then(idAction("decline", "loan.declined", (uuid, id) -> svc.core().loans().decline(uuid, id)))
                .then(idAction("withdraw", "loan.withdrawn", (uuid, id) -> svc.core().loans().withdraw(uuid, id)))
                .then(idAction("repay", "loan.repaid", (uuid, id) -> svc.core().loans().repayInFull(uuid, id)))
                .build();
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> offerBranch(String literal, boolean company) {
        return Commands.literal(literal)
                .then(Commands.argument("borrower", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .then(Commands.argument("interest", IntegerArgumentType.integer(0, 1000))
                                        .then(Commands.argument("installments", IntegerArgumentType.integer(1, 1000))
                                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, 100_000))
                                                        .executes(c -> offer(c, company)))))));
    }

    private int offer(CommandContext<CommandSourceStack> c, boolean fromCompany) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String borrowerInput = StringArgumentType.getString(c, "borrower");
        String amountInput = StringArgumentType.getString(c, "amount");
        int interest = IntegerArgumentType.getInteger(c, "interest");
        int installments = IntegerArgumentType.getInteger(c, "installments");
        int hours = IntegerArgumentType.getInteger(c, "hours");
        Long selected = svc.sessions().get(uuid).map(PlayerSession::selectedCompanyId).orElse(null);
        svc.tasks().run(player, () -> {
            LoanService.Party lender = fromCompany
                    ? LoanService.Party.company(selected != null ? selected : svc.core().companies().resolveForActor(uuid, null, CompanyRole.OWNER).id())
                    : LoanService.Party.player(uuid);
            LoanService.Party borrower;
            if (borrowerInput.startsWith("#")) {
                Company company = svc.core().companies().requireByRef(borrowerInput);
                borrower = LoanService.Party.company(company.id());
            } else {
                borrower = LoanService.Party.player(svc.core().players().requireByName(borrowerInput).uuid());
            }
            return svc.core().loans().offer(uuid, lender, borrower, Money.parsePositive(amountInput), interest, installments, hours);
        }, loan -> {
            svc.messages().send(player, "loan.offered", "id", loan.id(), "borrower", loan.borrowerName(),
                    "principal", loan.principal(), "total", loan.totalRepayment());
            notifyBorrower(loan);
        });
        return Command.SINGLE_SUCCESS;
    }

    private void notifyBorrower(Loan loan) {
        svc.tasks().run(Bukkit.getConsoleSender(), () -> loan.borrowerType() == Loan.PartyType.PLAYER
                ? UUID.fromString(loan.borrowerId())
                : svc.core().companies().find(Long.parseLong(loan.borrowerId())).map(Company::ownerUuid).orElse(null), target -> {
            Player online = target == null ? null : Bukkit.getPlayer(target);
            if (online != null) {
                svc.messages().send(online, "loan.offer_notice", "lender", loan.lenderName(), "principal", loan.principal(),
                        "total", loan.totalRepayment(), "installments", loan.installments(), "id", loan.id());
            }
        });
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> idAction(String literal, String successKey,
                                                                                           BiFunction<UUID, Long, Loan> action) {
        return Commands.literal(literal).then(Commands.argument("id", LongArgumentType.longArg(1)).executes(c -> {
            Player player = svc.requirePlayer(c.getSource());
            if (player == null) {
                return Command.SINGLE_SUCCESS;
            }
            UUID uuid = player.getUniqueId();
            long id = LongArgumentType.getLong(c, "id");
            svc.tasks().run(player, () -> action.apply(uuid, id), loan -> {
                if (loan.status() == Loan.Status.WITHDRAWN && successKey.equals("loan.accepted")) {
                    svc.messages().sendError(player, new se.nordia.swedencore.core.DomainException("loan.not_offered"));
                    return;
                }
                svc.messages().send(player, successKey, "id", loan.id(), "principal", loan.principal());
            });
            return Command.SINGLE_SUCCESS;
        }));
    }

    private int list(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().loans().involving(uuid), loans -> {
            if (loans.isEmpty()) {
                svc.messages().send(player, "loan.list.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "loan.list.header");
            for (Loan loan : loans) {
                svc.messages().send(player, "loan.list.entry", "id", loan.id(), "lender", loan.lenderName(), "borrower", loan.borrowerName(),
                        "repaid", loan.repaid(), "total", loan.totalRepayment(),
                        "status", svc.messages().render(locale, "loan.status." + loan.status().name()),
                        "next", loan.nextDueAt() == null ? "-" : loan.nextDueAt());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
