package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Company;
import se.nordia.swedencore.companies.Employee;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.jobs.JobApplication;
import se.nordia.swedencore.jobs.JobPosition;
import se.nordia.swedencore.jobs.JobService;
import se.nordia.swedencore.jobs.PayrollService;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.jobs.WorkTracker;

import java.util.List;
import java.util.UUID;

/** {@code /jobs} (alias {@code /jobb}): job board, applications, duty and payslips. */
public final class JobCommands {

    private static final int PAGE_SIZE = 8;

    private final CommandServices svc;
    private final WorkTracker work;

    public JobCommands(CommandServices svc, WorkTracker work) {
        this.svc = svc;
        this.work = work;
    }

    public void register(Commands commands) {
        commands.register(node(), "Jobb / Jobs", List.of("jobb"));
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("jobs")
                .executes(c -> board(c.getSource().getSender(), 1))
                .then(Commands.literal("list")
                        .executes(c -> board(c.getSource().getSender(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                                .executes(c -> board(c.getSource().getSender(), IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.literal("catalog").executes(c -> catalog(c.getSource().getSender())))
                .then(Commands.literal("apply").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(c -> apply(c.getSource(), LongArgumentType.getLong(c, "id"), null))
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(c -> apply(c.getSource(), LongArgumentType.getLong(c, "id"), StringArgumentType.getString(c, "message"))))))
                .then(Commands.literal("applications").executes(c -> myApplications(c.getSource())))
                .then(Commands.literal("withdraw").then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(c -> withdraw(c.getSource(), LongArgumentType.getLong(c, "id")))))
                .then(Commands.literal("duty")
                        .executes(c -> duty(c.getSource(), null))
                        .then(Commands.argument("company", StringArgumentType.greedyString())
                                .executes(c -> duty(c.getSource(), StringArgumentType.getString(c, "company")))))
                .then(Commands.literal("payslips").executes(c -> payslips(c.getSource())))
                .build();
    }

    private int board(CommandSender sender, int page) {
        svc.tasks().run(sender, () -> svc.core().jobs().openPositions(PAGE_SIZE, (page - 1) * PAGE_SIZE), positions -> {
            if (positions.isEmpty()) {
                svc.messages().send(sender, "jobs.board.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(sender);
            svc.messages().send(sender, "jobs.board.header", "page", page);
            for (JobPosition p : positions) {
                Component skill = p.skill() == null
                        ? svc.messages().render(locale, "skill.none")
                        : svc.messages().render(locale, "skill." + p.skill().name());
                Component line = svc.messages().render(locale, "jobs.board.entry", "id", p.id(), "title", p.title(),
                        "company", p.companyName(), "reputation", p.companyReputation(), "salary", p.salaryPerHour(),
                        "skill", skill, "level", p.requiredLevel(), "filled", p.filled(), "openings", p.openings());
                sender.sendMessage(line
                        .clickEvent(ClickEvent.suggestCommand("/jobs apply " + p.id() + " "))
                        .hoverEvent(HoverEvent.showText(svc.messages().render(locale, "jobs.board.hover"))));
            }
            svc.messages().send(sender, "jobs.board.footer", "next", page + 1);
        });
        return Command.SINGLE_SUCCESS;
    }

    private int catalog(CommandSender sender) {
        SupportedLocale locale = svc.messages().localeOf(sender);
        svc.tasks().run(sender, () -> svc.core().jobs().jobs(), jobs -> {
            svc.messages().send(sender, "jobs.catalog.header");
            for (JobService.Job job : jobs) {
                svc.messages().send(sender, "jobs.catalog.entry", "id", job.id().toLowerCase(java.util.Locale.ROOT),
                        "job", svc.messages().render(locale, "job.name." + job.id()),
                        "skill", job.skill() == null ? svc.messages().render(locale, "skill.none")
                                : svc.messages().render(locale, "skill." + job.skill().name()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int apply(CommandSourceStack source, long positionId, String message) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().jobs().apply(uuid, positionId, message), app -> {
            svc.messages().send(player, "jobs.applied", "id", app.id(), "title", app.positionTitle(), "company", app.companyName());
            notifyManagers(app);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Tells online owners/managers of the company that a new application arrived. */
    private void notifyManagers(JobApplication app) {
        svc.tasks().run(org.bukkit.Bukkit.getConsoleSender(), () -> svc.core().companies().employees(app.companyId()), staff -> {
            for (Employee e : staff) {
                if (!e.role().canManageStaff()) {
                    continue;
                }
                Player online = org.bukkit.Bukkit.getPlayer(e.playerUuid());
                if (online != null) {
                    svc.messages().send(online, "company.application.new_notice", "player", app.applicantName(),
                            "title", app.positionTitle(), "id", app.id());
                }
            }
        });
    }

    private int myApplications(CommandSourceStack source) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().jobs().applicationsOf(uuid), apps -> {
            if (apps.isEmpty()) {
                svc.messages().send(player, "jobs.applications.empty");
                return;
            }
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "jobs.applications.header");
            for (JobApplication a : apps) {
                svc.messages().send(player, "jobs.applications.entry", "id", a.id(), "title", a.positionTitle(),
                        "company", a.companyName(), "status", svc.messages().render(locale, "jobs.application_status." + a.status().name()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int withdraw(CommandSourceStack source, long applicationId) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> {
            svc.core().jobs().withdraw(uuid, applicationId);
            return applicationId;
        }, id -> svc.messages().send(player, "jobs.withdrawn", "id", id));
        return Command.SINGLE_SUCCESS;
    }

    /** Toggles duty. Without a company: the only employment, or turns duty off if already on duty. */
    private int duty(CommandSourceStack source, String ref) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        if (ref == null && work.dutyOf(uuid).isPresent()) {
            work.stopDuty(uuid).ifPresent(e -> svc.messages().send(player, "jobs.duty.off", "company", e.companyName()));
            return Command.SINGLE_SUCCESS;
        }
        svc.tasks().run(player, () -> {
            List<Employee> employments = svc.core().companies().activeEmploymentsOf(uuid).stream()
                    .filter(e -> e.positionId() != null)
                    .toList();
            if (ref != null) {
                Company company = svc.core().companies().requireByRef(ref);
                return employments.stream().filter(e -> e.companyId() == company.id()).findFirst()
                        .orElseThrow(() -> new DomainException("jobs.no_position"));
            }
            if (employments.isEmpty()) {
                throw new DomainException("jobs.no_position");
            }
            if (employments.size() > 1) {
                throw new DomainException("company.specify");
            }
            return employments.getFirst();
        }, employee -> {
            if (!player.isOnline()) {
                return;
            }
            work.startDuty(player, employee);
            SupportedLocale locale = svc.messages().localeOf(player);
            svc.messages().send(player, "jobs.duty.on", "company", employee.companyName(), "title", employee.positionTitle(),
                    "salary", employee.salaryPerHour(),
                    "skill", employee.skill() == null ? svc.messages().render(locale, "skill.none")
                            : svc.messages().render(locale, "skill." + employee.skill().name()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int payslips(CommandSourceStack source) {
        Player player = svc.requirePlayer(source);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        UUID uuid = player.getUniqueId();
        svc.tasks().run(player, () -> svc.core().payroll().recentForPlayer(uuid, 10), entries -> {
            if (entries.isEmpty()) {
                svc.messages().send(player, "jobs.payslips.empty");
                return;
            }
            svc.messages().send(player, "jobs.payslips.header");
            for (PayrollService.PayrollEntry e : entries) {
                svc.messages().send(player, e.paid() ? "jobs.payslips.entry_paid" : "jobs.payslips.entry_unpaid",
                        "date", e.createdAt(), "company", e.companyName(), "minutes", e.workMinutes(), "amount", e.amount());
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
