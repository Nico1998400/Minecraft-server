package se.nordia.swedencore.paper.skills;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.scheduler.Tasks;
import se.nordia.swedencore.paper.text.Messages;
import se.nordia.swedencore.skills.LevelChange;
import se.nordia.swedencore.skills.PlayerSkillBuffer;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillProgress;
import se.nordia.swedencore.skills.SkillService;
import se.nordia.swedencore.skills.XpRateLimiter;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToIntBiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central entry point for awarding skill XP from gameplay.
 *
 * <p>All state is confined to the main thread. XP is applied to an in-memory buffer immediately (so feedback is
 * instant) and flushed to the database periodically, on quit, and on shutdown.
 */
public final class SkillTracker {

    /** Notified on the main thread after XP has been granted (used by employment to detect real work). */
    @FunctionalInterface
    public interface XpListener {
        void onXp(Player player, Skill skill, long granted);
    }

    private static final class State {
        final PlayerSkillBuffer buffer;
        final XpRateLimiter limiter;

        State(PlayerSkillBuffer buffer, XpRateLimiter limiter) {
            this.buffer = buffer;
            this.limiter = limiter;
        }
    }

    private final SkillService service;
    private final ActivityTracker activity;
    private final Messages messages;
    private final Tasks tasks;
    private final Logger logger;
    private final Map<UUID, State> states = new HashMap<>();
    private final List<XpListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ToIntBiFunction<UUID, Skill> bonusPercent = (player, skill) -> 0;

    public SkillTracker(SkillService service, ActivityTracker activity, Messages messages, Tasks tasks, Logger logger) {
        this.service = service;
        this.activity = activity;
        this.messages = messages;
        this.tasks = tasks;
        this.logger = logger;
    }

    public void addListener(XpListener listener) {
        listeners.add(listener);
    }

    /** Extra XP percentage for a player and skill (e.g. employment bonus). */
    public void setBonusProvider(ToIntBiFunction<UUID, Skill> provider) {
        this.bonusPercent = provider;
    }

    public Duration flushInterval() {
        return service.config().flushInterval();
    }

    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        State state = new State(new PlayerSkillBuffer(service.curve()), new XpRateLimiter(service.config()));
        states.put(uuid, state);
        tasks.async(() -> service.load(uuid)).whenComplete((progress, error) -> tasks.sync(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Failed to load skills for " + uuid, Tasks.unwrap(error));
                return;
            }
            if (states.get(uuid) == state) {
                state.buffer.load(progress);
            }
        }));
    }

    public void onQuit(UUID uuid) {
        State state = states.remove(uuid);
        if (state != null) {
            flush(uuid, state, false);
        }
    }

    /**
     * Awards XP for gameplay. Applies game mode, AFK, diminishing returns and bonus rules.
     *
     * @param requireActive whether the player must not be AFK right now
     * @return XP actually granted
     */
    public long award(Player player, Skill skill, long baseXp, boolean requireActive) {
        if (baseXp <= 0) {
            return 0;
        }
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return 0;
        }
        UUID uuid = player.getUniqueId();
        if (requireActive && !activity.isActive(uuid)) {
            return 0;
        }
        State state = states.get(uuid);
        if (state == null) {
            return 0;
        }
        long xp = baseXp;
        int bonus = bonusPercent.applyAsInt(uuid, skill);
        if (bonus > 0) {
            xp = xp + (xp * bonus + 50) / 100;
        }
        xp = state.limiter.apply(skill, xp, System.currentTimeMillis());
        if (xp <= 0) {
            return 0;
        }
        LevelChange change = state.buffer.add(skill, xp);
        if (change.xpApplied() <= 0) {
            return 0;
        }
        feedback(player, change);
        for (XpListener listener : listeners) {
            listener.onXp(player, skill, change.xpApplied());
        }
        return change.xpApplied();
    }

    /**
     * Grants XP directly without gameplay rules (contracts, employment rewards). Works for offline players.
     * Safe to call from any thread; the database is updated immediately.
     */
    public void grantReward(UUID uuid, Skill skill, long xp) {
        tasks.async("skill reward " + uuid, () -> service.addXp(uuid, skill, xp));
    }

    private void feedback(Player player, LevelChange change) {
        SupportedLocale locale = messages.localeOf(player);
        Component skillName = messages.render(locale, "skill." + change.skill().name());
        long[] progress = service.curve().progressWithinLevel(change.totalXp());
        long percent = progress[1] == 0 ? 100 : (progress[0] * 100) / progress[1];
        player.sendActionBar(messages.render(locale, "skills.xp_gain",
                "xp", change.xpApplied(), "skill", skillName, "level", change.newLevel(), "progress", percent));
        if (change.leveledUp()) {
            player.showTitle(Title.title(
                    messages.render(locale, "skills.level_up_title", "skill", skillName),
                    messages.render(locale, "skills.level_up_subtitle", "level", change.newLevel()),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
            messages.send(player, "skills.level_up", "skill", skillName, "level", change.newLevel());
        }
    }

    /** Current progress for an online player, including unflushed XP. */
    public Optional<SkillProgress> progress(UUID uuid, Skill skill) {
        State state = states.get(uuid);
        if (state == null || !state.buffer.loaded()) {
            return Optional.empty();
        }
        return Optional.of(state.buffer.progress(skill));
    }

    public int level(UUID uuid, Skill skill) {
        State state = states.get(uuid);
        return state == null ? 1 : state.buffer.level(skill);
    }

    /** Periodic flush of all online players (async writes). */
    public void flushAll() {
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            flush(entry.getKey(), entry.getValue(), true);
        }
    }

    private void flush(UUID uuid, State state, boolean restoreOnFailure) {
        Map<Skill, Long> drained = state.buffer.drainForFlush();
        if (drained.isEmpty()) {
            return;
        }
        tasks.async(() -> service.addXp(uuid, drained)).whenComplete((changes, error) -> {
            if (error == null) {
                return;
            }
            logger.log(Level.SEVERE, "Failed to save skill XP for " + uuid + ": " + drained, Tasks.unwrap(error));
            if (restoreOnFailure) {
                tasks.sync(() -> {
                    if (states.get(uuid) == state) {
                        state.buffer.restore(drained);
                    }
                });
            }
        });
    }

    /** Blocking flush used during plugin shutdown (the worker pool may already be stopping). */
    public void flushAllBlocking() {
        for (Map.Entry<UUID, State> entry : states.entrySet()) {
            Map<Skill, Long> drained = entry.getValue().buffer.drainForFlush();
            if (drained.isEmpty()) {
                continue;
            }
            try {
                service.addXp(entry.getKey(), drained);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Failed to save skill XP on shutdown for " + entry.getKey() + ": " + drained, e);
            }
        }
    }
}
