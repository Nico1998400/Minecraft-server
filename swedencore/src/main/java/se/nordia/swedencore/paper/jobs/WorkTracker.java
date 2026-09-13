package se.nordia.swedencore.paper.jobs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import se.nordia.swedencore.companies.Employee;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.jobs.PayrollService;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.skills.ActivityTracker;
import se.nordia.swedencore.paper.skills.SkillTracker;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.skills.Skill;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks on-duty employees and turns verified work into payroll batches.
 *
 * <p>A minute counts as worked when the on-duty employee gained XP in the job's skill during that minute (XP itself
 * already excludes AFK, creative mode and placed blocks). Jobs without a skill count minutes in which the player was
 * not AFK. Every payroll interval the worked minutes are sent to {@link PayrollService#recordWork} as one idempotent
 * batch. Main-thread confined.
 */
public final class WorkTracker implements SkillTracker.XpListener {

    private static final class Duty {
        final Employee employee;
        final TreeSet<Long> minutes = new TreeSet<>();

        Duty(Employee employee) {
            this.employee = employee;
        }
    }

    private final PayrollService payroll;
    private final ActivityTracker activity;
    private final Messages messages;
    private final Tasks tasks;
    private final Logger logger;
    private final int bonusPercent;
    private final int maxBatchMinutes;
    private final Map<UUID, Duty> duties = new HashMap<>();

    public WorkTracker(PayrollService payroll, ActivityTracker activity, Messages messages, Tasks tasks, Logger logger,
                       int bonusPercent, int payrollIntervalMinutes) {
        this.payroll = payroll;
        this.activity = activity;
        this.messages = messages;
        this.tasks = tasks;
        this.logger = logger;
        this.bonusPercent = bonusPercent;
        this.maxBatchMinutes = payrollIntervalMinutes * 2;
    }

    public Optional<Employee> dutyOf(UUID player) {
        Duty duty = duties.get(player);
        return duty == null ? Optional.empty() : Optional.of(duty.employee);
    }

    public void startDuty(Player player, Employee employee) {
        stopDuty(player.getUniqueId());
        duties.put(player.getUniqueId(), new Duty(employee));
    }

    /** Ends duty and pays out worked minutes. Returns the employment that ended, if any. */
    public Optional<Employee> stopDuty(UUID player) {
        Duty duty = duties.remove(player);
        if (duty == null) {
            return Optional.empty();
        }
        flush(player, duty);
        return Optional.of(duty.employee);
    }

    /** Employment XP bonus: work in the skill of your on-duty job trains you faster. */
    public int bonusPercent(UUID player, Skill skill) {
        Duty duty = duties.get(player);
        return duty != null && duty.employee.skill() == skill ? bonusPercent : 0;
    }

    @Override
    public void onXp(Player player, Skill skill, long granted) {
        Duty duty = duties.get(player.getUniqueId());
        if (duty != null && duty.employee.skill() == skill) {
            duty.minutes.add(currentMinute());
        }
    }

    /** Called every minute: counts non-AFK minutes for jobs that have no skill. */
    public void tickMinute() {
        long minute = currentMinute();
        for (Map.Entry<UUID, Duty> entry : duties.entrySet()) {
            if (entry.getValue().employee.skill() == null && activity.isActive(entry.getKey())) {
                entry.getValue().minutes.add(minute);
            }
        }
    }

    /** Called every payroll interval. */
    public void flushAll() {
        for (Map.Entry<UUID, Duty> entry : duties.entrySet()) {
            flush(entry.getKey(), entry.getValue());
        }
    }

    private void flush(UUID player, Duty duty) {
        if (duty.minutes.isEmpty()) {
            return;
        }
        while (duty.minutes.size() > maxBatchMinutes) {
            duty.minutes.pollFirst();
        }
        long first = duty.minutes.first();
        int count = duty.minutes.size();
        duty.minutes.clear();
        Employee employee = duty.employee;
        Instant periodStart = Instant.ofEpochSecond(first * 60);
        tasks.async(() -> payroll.recordWork(employee.id(), periodStart, count)).whenComplete((result, error) -> tasks.sync(() -> {
            Player online = Bukkit.getPlayer(player);
            if (error != null) {
                Throwable cause = Tasks.unwrap(error);
                if (cause instanceof DomainException domain && domain.code().equals("payroll.not_employed")) {
                    if (duties.get(player) == duty) {
                        duties.remove(player);
                    }
                    if (online != null) {
                        messages.send(online, "jobs.duty.ended", "company", employee.companyName());
                    }
                    return;
                }
                logger.log(Level.SEVERE, "Payroll failed for employee " + employee.id() + " (" + count + " min)", cause);
                return;
            }
            if (online == null || result.isEmpty() || result.get().duplicate()) {
                return;
            }
            PayrollService.PayrollResult r = result.get();
            if (r.paid()) {
                messages.send(online, "jobs.paid", "amount", r.amount(), "minutes", count, "company", employee.companyName());
            } else {
                messages.send(online, "jobs.unpaid", "amount", r.amount(), "company", employee.companyName());
            }
        }));
    }

    private static long currentMinute() {
        return System.currentTimeMillis() / 60_000L;
    }
}
