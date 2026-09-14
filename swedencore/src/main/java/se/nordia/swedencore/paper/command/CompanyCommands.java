package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.companies.Employee;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.jobs.JobApplication;
import se.nordia.swedencore.jobs.JobPosition;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.session.PlayerSession;
import se.nordia.swedencore.player.NordiaPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code /company} (alias {@code /foretag}): founding, finances, staff, positions and applications.
 *
 * <p>Commands that act on a company accept an optional trailing company reference (id or name). Without one, the
 * company selected via {@code /company use} is used, or the only company in which the player has a suitable role.
 */
public final class CompanyCommands {

    private static final long CONFIRM_WINDOW_MILLIS = 30_000;

    private final CommandServices svc;

    public CompanyCommands(CommandServices svc) {
        this.svc = svc;
    }

    public void register(Commands commands) {
        commands.register(node(), "Företag / Companies", List.of("foretag", "co"));
    }

    private CompanyService companies() {
        return svc.core().companies();
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("company")
                .executes(c -> help(c.getSource()))
                .then(Commands.literal("help").executes(c -> help(c.getSource())))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::create)))
                .then(Commands.literal("list").executes(this::list))
                .then(withCompany(Commands.literal("info"), this::info))
                .then(Commands.literal("use").then(Commands.argument("company", StringArgumentType.greedyString()).executes(this::use)))
                .then(Commands.literal("deposit").then(amountWithCompany(this::deposit)))
                .then(Commands.literal("withdraw").then(amountWithCompany(this::withdraw)))
                .then(withCompany(Commands.literal("employees"), this::employees))
                .then(Commands.literal("fire").then(playerWithCompany(this::fire)))
                .then(Commands.literal("promote").then(playerWithCompany((c, target, ref) -> setRole(c, target, ref, CompanyRole.MANAGER))))
                .then(Commands.literal("demote").then(playerWithCompany((c, target, ref) -> setRole(c, target, ref, CompanyRole.EMPLOYEE))))
                .then(Commands.literal("salary").then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(c -> salary(c, null))
                                .then(Commands.argument("company", StringArgumentType.greedyString())
                                        .executes(c -> salary(c, StringArgumentType.getString(c, "company")))))))
                .then(withCompany(Commands.literal("leave"), this::leave))
                .then(Commands.literal("dissolve")
                        .executes(c -> dissolve(c, null, false))
                        .then(Commands.literal("confirm").executes(c -> dissolve(c, null, true)))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> dissolve(c, StringArgumentType.getString(c, "company"), false))))
                .then(Commands.literal("bankrupt")
                        .executes(c -> bankrupt(c, false))
                        .then(Commands.literal("confirm").executes(c -> bankrupt(c, true))))
                .then(withCompany(Commands.literal("positions"), this::positions))
                .then(Commands.literal("position")
                        .then(Commands.literal("create")
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            svc.core().jobs().jobs().forEach(j -> {
                                                String id = j.id().toLowerCase(Locale.ROOT);
                                                if (id.startsWith(b.getRemainingLowerCase())) {
                                                    b.suggest(id);
                                                }
                                            });
                                            return b.buildFuture();
                                        })
                                        .then(Commands.argument("salary", StringArgumentType.word())
                                                .then(Commands.argument("openings", IntegerArgumentType.integer(1, 100))
                                                        .then(Commands.argument("level", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("title", StringArgumentType.greedyString())
                                                                        .executes(this::createPosition)))))))
                        .then(Commands.literal("close")
                                .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::closePosition))))
                .then(withCompany(Commands.literal("applications"), this::applications))
                .then(Commands.literal("accept").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(c -> decide(c, true))))
                .then(Commands.literal("reject").then(Commands.argument("id", LongArgumentType.longArg(1)).executes(c -> decide(c, false))))
                .build();
    }

    // ------------------------------------------------------------------ argument helpers

    @FunctionalInterface
    private interface CompanyAction {
        int run(CommandContext<CommandSourceStack> c, String companyRef);
    }

    @FunctionalInterface
    private interface PlayerCompanyAction {
        int run(CommandContext<CommandSourceStack> c, String targetName, String companyRef);
    }

    private LiteralArgumentBuilder<CommandSourceStack> withCompany(LiteralArgumentBuilder<CommandSourceStack> literal, CompanyAction action) {
        return literal.executes(c -> action.run(c, null))
                .then(Commands.argument("company", StringArgumentType.greedyString())
                        .executes(c -> action.run(c, StringArgumentType.getString(c, "company"))));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> amountWithCompany(CompanyAction action) {
        return Commands.argument("amount", StringArgumentType.word())
                .executes(c -> action.run(c, null))
                .then(Commands.argument("company", StringArgumentType.greedyString())
                        .executes(c -> action.run(c, StringArgumentType.getString(c, "company"))));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerWithCompany(PlayerCompanyAction action) {
        return Commands.argument("player", StringArgumentType.word())
                .suggests(CommandServices.onlinePlayerNames())
                .executes(c -> action.run(c, StringArgumentType.getString(c, "player"), null))
                .then(Commands.argument("company", StringArgumentType.greedyString())
                        .executes(c -> action.run(c, StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "company"))));
    }

    /** Resolution order: explicit reference → selected company → the single company where the actor has a role. */
    private Company resolve(UUID actor, String ref, CompanyRole... roles) {
        if (ref != null && !ref.isBlank()) {
            return companies().requireByRef(ref);
        }
        Long selected = svc.sessions().get(actor).map(PlayerSession::selectedCompanyId).orElse(null);
        if (selected != null) {
            Optional<Company> company = companies().find(selected);
            if (company.isPresent() && company.get().active()) {
                return company.get();
            }
        }
        return companies().resolveForActor(actor, null, roles);
    }

    // ------------------------------------------------------------------ commands

    private int help(CommandSourceStack source) {
        svc.messages().send(source.getSender(), "company.help");
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(c, "name");
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> companies().found(uuid, name), company -> {
            svc.sessions().get(uuid).ifPresent(s -> s.selectedCompanyId(company.id()));
            svc.messages().send(player, "company.created", "name", company.name(), "id", company.id(),
                    "fee", companies().config().registrationFee());
        });
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> companies().memberships(uuid), memberships -> {
            if (memberships.isEmpty()) {
                svc.messages().send(player, "company.list.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "company.list.header");
            for (CompanyService.Membership m : memberships) {
                svc.messages().send(player, "company.list.entry", "id", m.company().id(), "name", m.company().name(),
                        "role", role(locale, m.role()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> c, String ref) {
        CommandSender sender = c.getSource().getSender();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
        if (actor == null && ref == null) {
            svc.messages().send(sender, "error.players_only");
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(sender, () -> companies().summary(resolve(actor, ref).id()), s -> {
            svc.messages().send(sender, "company.info.header", "name", s.company().name(), "id", s.company().id());
            svc.messages().send(sender, "company.info.details", "owner", s.ownerName(), "employees", s.employeeCount(),
                    "reputation", s.company().reputation(), "founded", s.company().foundedAt());
            svc.messages().send(sender, "company.info.balance", "balance", s.balance());
            if (s.arrears().isPositive()) {
                svc.messages().send(sender, "company.info.arrears", "arrears", s.arrears());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int use(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String ref = StringArgumentType.getString(c, "company");
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Company company = companies().requireByRef(ref);
            boolean member = companies().memberships(uuid).stream().anyMatch(m -> m.company().id() == company.id());
            if (!member) {
                throw new DomainException("company.not_member");
            }
            return company;
        }, company -> {
            svc.sessions().get(uuid).ifPresent(s -> s.selectedCompanyId(company.id()));
            svc.messages().send(player, "company.selected", "name", company.name());
        });
        return Command.SINGLE_SUCCESS;
    }

    private record MoneyResult(String company, Money amount, Money balance) {
    }

    private int deposit(CommandContext<CommandSourceStack> c, String ref) {
        return moneyAction(c, ref, "company.deposited", (uuid, company, amount) -> {
            companies().deposit(uuid, company.id(), amount);
            return new MoneyResult(company.name(), amount, companies().balance(company.id()));
        }, CompanyRole.values());
    }

    private int withdraw(CommandContext<CommandSourceStack> c, String ref) {
        return moneyAction(c, ref, "company.withdrew", (uuid, company, amount) -> {
            companies().withdraw(uuid, company.id(), amount);
            return new MoneyResult(company.name(), amount, companies().balance(company.id()));
        }, CompanyRole.OWNER);
    }

    @FunctionalInterface
    private interface MoneyOperation {
        MoneyResult apply(UUID actor, Company company, Money amount);
    }

    private int moneyAction(CommandContext<CommandSourceStack> c, String ref, String successKey, MoneyOperation op, CompanyRole... roles) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String amountInput = StringArgumentType.getString(c, "amount");
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Money amount = Money.parsePositive(amountInput);
            return op.apply(uuid, resolve(uuid, ref, roles), amount);
        }, r -> svc.messages().send(player, successKey, "amount", r.amount(), "name", r.company(), "balance", r.balance()));
        return Command.SINGLE_SUCCESS;
    }

    private int employees(CommandContext<CommandSourceStack> c, String ref) {
        CommandSender sender = c.getSource().getSender();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
        svc.tasks().run(sender, () -> {
            Company company = resolve(actor, ref);
            return new Staff(company, companies().employees(company.id()));
        }, r -> {
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "company.employees.header", "name", r.company().name(), "count", r.staff().size());
            for (Employee e : r.staff()) {
                svc.messages().send(sender, "company.employees.entry", "player", e.playerName(), "role", role(locale, e.role()),
                        "position", e.positionTitle() == null ? "-" : e.positionTitle(), "salary", e.salaryPerHour());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private record Staff(Company company, List<Employee> staff) {
    }

    private record StaffResult(String company, String target, UUID targetUuid) {
    }

    private int staffAction(CommandContext<CommandSourceStack> c, String targetName, String ref, String successKey,
                            Function<StaffContext, Void> op, Object... extraArgs) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Company company = resolve(uuid, ref, CompanyRole.OWNER, CompanyRole.MANAGER);
            NordiaPlayer target = svc.core().players().requireByName(targetName);
            op.apply(new StaffContext(uuid, company, target));
            return new StaffResult(company.name(), target.name(), target.uuid());
        }, r -> {
            Object[] args = new Object[4 + extraArgs.length];
            args[0] = "player";
            args[1] = r.target();
            args[2] = "company";
            args[3] = r.company();
            System.arraycopy(extraArgs, 0, args, 4, extraArgs.length);
            svc.messages().send(player, successKey, args);
            Player targetOnline = Bukkit.getPlayer(r.targetUuid());
            if (targetOnline != null && !targetOnline.equals(player)) {
                svc.messages().send(targetOnline, successKey + "_notice", args);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private record StaffContext(UUID actor, Company company, NordiaPlayer target) {
    }

    private int fire(CommandContext<CommandSourceStack> c, String targetName, String ref) {
        return staffAction(c, targetName, ref, "company.fired", ctx -> {
            companies().terminate(ctx.actor(), ctx.company().id(), ctx.target().uuid());
            return null;
        });
    }

    private int setRole(CommandContext<CommandSourceStack> c, String targetName, String ref, CompanyRole role) {
        SupportedLocale locale = c.getSource().getSender() instanceof Player p ? svc.messages().localeOf(p) : svc.messages().defaultLocale();
        return staffAction(c, targetName, ref, "company.role_changed", ctx -> {
            companies().setRole(ctx.actor(), ctx.company().id(), ctx.target().uuid(), role);
            return null;
        }, "role", role(locale, role));
    }

    private int salary(CommandContext<CommandSourceStack> c, String ref) {
        String targetName = StringArgumentType.getString(c, "player");
        String amountInput = StringArgumentType.getString(c, "amount");
        Money salary;
        try {
            salary = amountInput.equals("0") ? Money.ZERO : Money.parsePositive(amountInput);
        } catch (DomainException e) {
            svc.messages().sendError(c.getSource().getSender(), e);
            return Command.SINGLE_SUCCESS;
        }
        return staffAction(c, targetName, ref, "company.salary_changed", ctx -> {
            companies().setSalary(ctx.actor(), ctx.company().id(), ctx.target().uuid(), salary);
            return null;
        }, "salary", salary);
    }

    private int leave(CommandContext<CommandSourceStack> c, String ref) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Company company = resolve(uuid, ref, CompanyRole.MANAGER, CompanyRole.EMPLOYEE);
            companies().leave(uuid, company.id());
            return company;
        }, company -> svc.messages().send(player, "company.left", "name", company.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int dissolve(CommandContext<CommandSourceStack> c, String ref, boolean confirm) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = svc.sessions().get(uuid).orElse(null);
        if (session == null) {
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        if (confirm) {
            PlayerSession.PendingConfirmation pending = session.pendingConfirmation();
            if (pending == null || !pending.matches("dissolve", now)) {
                svc.messages().send(player, "company.dissolve.nothing_to_confirm");
                return Command.SINGLE_SUCCESS;
            }
            session.pendingConfirmation(null);
            long companyId = pending.targetId();
            svc.tasks().run(player, () -> {
                Company company = companies().find(companyId).orElseThrow(() -> new DomainException("company.not_found"));
                return new MoneyResult(company.name(), companies().dissolve(uuid, companyId), Money.ZERO);
            }, r -> svc.messages().send(player, "company.dissolved", "name", r.company(), "payout", r.amount()));
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(player, () -> resolve(uuid, ref, CompanyRole.OWNER), company -> {
            session.pendingConfirmation(new PlayerSession.PendingConfirmation("dissolve", company.id(), now + CONFIRM_WINDOW_MILLIS));
            svc.messages().send(player, "company.dissolve.confirm", "name", company.name());
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Voluntary bankruptcy for an indebted company (two-step confirmation). */
    private int bankrupt(CommandContext<CommandSourceStack> c, boolean confirm) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = svc.sessions().get(uuid).orElse(null);
        if (session == null) {
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        if (!confirm) {
            svc.tasks().run(player, () -> resolve(uuid, null, CompanyRole.OWNER), company -> {
                session.pendingConfirmation(new PlayerSession.PendingConfirmation("bankrupt", company.id(), now + CONFIRM_WINDOW_MILLIS));
                svc.messages().send(player, "bankruptcy.confirm", "name", company.name());
            });
            return Command.SINGLE_SUCCESS;
        }
        PlayerSession.PendingConfirmation pending = session.pendingConfirmation();
        if (pending == null || !pending.matches("bankrupt", now)) {
            svc.messages().send(player, "company.dissolve.nothing_to_confirm");
            return Command.SINGLE_SUCCESS;
        }
        session.pendingConfirmation(null);
        long companyId = pending.targetId();
        svc.tasks().run(player, () -> {
            Company company = companies().find(companyId).orElseThrow();
            svc.core().bankruptcy().declare(companyId, se.nordia.swedencore.finance.BankruptcyService.Reason.VOLUNTARY, uuid);
            return company;
        }, company -> {
            // The server-wide announcement is sent by the CompanyBankrupt event listener (covers loan defaults too).
        });
        return Command.SINGLE_SUCCESS;
    }

    private int positions(CommandContext<CommandSourceStack> c, String ref) {
        CommandSender sender = c.getSource().getSender();
        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;
        svc.tasks().run(sender, () -> {
            Company company = resolve(actor, ref);
            return java.util.Map.entry(company, svc.core().jobs().companyPositions(company.id()));
        }, r -> {
            if (r.getValue().isEmpty()) {
                svc.messages().send(sender, "company.positions.empty", "name", r.getKey().name());
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "company.positions.header", "name", r.getKey().name());
            for (JobPosition p : r.getValue()) {
                svc.messages().send(sender, "company.positions.entry", "id", p.id(), "title", p.title(),
                        "job", job(locale, p.jobId()), "level", p.requiredLevel(), "salary", p.salaryPerHour(),
                        "filled", p.filled(), "openings", p.openings());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int createPosition(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        String jobId = StringArgumentType.getString(c, "job");
        String salaryInput = StringArgumentType.getString(c, "salary");
        int openings = IntegerArgumentType.getInteger(c, "openings");
        int level = IntegerArgumentType.getInteger(c, "level");
        String title = StringArgumentType.getString(c, "title");
        svc.tasks().run(player, () -> {
            Money salary = salaryInput.equals("0") ? Money.ZERO : Money.parsePositive(salaryInput);
            Company company = resolve(uuid, null, CompanyRole.OWNER, CompanyRole.MANAGER);
            return svc.core().jobs().createPosition(uuid, company.id(), jobId, title, level, salary, openings);
        }, p -> svc.messages().send(player, "company.position.created", "id", p.id(), "title", p.title(),
                "salary", p.salaryPerHour(), "name", p.companyName()));
        return Command.SINGLE_SUCCESS;
    }

    private int closePosition(CommandContext<CommandSourceStack> c) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> {
            svc.core().jobs().closePosition(uuid, id);
            return id;
        }, closed -> svc.messages().send(player, "company.position.closed", "id", closed));
        return Command.SINGLE_SUCCESS;
    }

    private int applications(CommandContext<CommandSourceStack> c, String ref) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            Company company = resolve(uuid, ref, CompanyRole.OWNER, CompanyRole.MANAGER);
            return svc.core().jobs().pendingApplications(uuid, company.id());
        }, apps -> {
            if (apps.isEmpty()) {
                svc.messages().send(player, "company.applications.empty");
                return;
            }
            svc.messages().send(player, "company.applications.header", "count", apps.size());
            for (JobApplication a : apps) {
                svc.messages().send(player, "company.applications.entry", "id", a.id(), "player", a.applicantName(),
                        "title", a.positionTitle(), "message", a.message() == null ? "-" : a.message());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int decide(CommandContext<CommandSourceStack> c, boolean accept) {
        Player player = svc.requirePlayer(c.getSource());
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        long id = LongArgumentType.getLong(c, "id");
        svc.tasks().run(player, () -> accept ? svc.core().jobs().accept(uuid, id) : svc.core().jobs().reject(uuid, id), app -> {
            String key = accept ? "company.application.accepted" : "company.application.rejected";
            svc.messages().send(player, key, "player", app.applicantName(), "title", app.positionTitle());
            Player applicant = Bukkit.getPlayer(app.applicantUuid());
            if (applicant != null) {
                svc.messages().send(applicant, key + "_notice", "company", app.companyName(), "title", app.positionTitle());
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ rendering helpers

    private Component role(SupportedLocale locale, CompanyRole role) {
        return svc.messages().render(locale, "company.role." + role.name());
    }

    private Component job(SupportedLocale locale, String jobId) {
        return svc.messages().render(locale, "job.name." + jobId);
    }
}
