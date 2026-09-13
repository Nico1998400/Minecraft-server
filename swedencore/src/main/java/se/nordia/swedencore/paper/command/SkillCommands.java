package se.nordia.swedencore.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.localization.SupportedLocale;
import se.nordia.swedencore.paper.skills.SkillTracker;
import se.nordia.swedencore.player.NordiaPlayer;
import se.nordia.swedencore.skills.LevelCurve;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillProgress;
import se.nordia.swedencore.skills.SkillService;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /skills [player]}, {@code /skills top <skill>}. Skills are public: employers need to see them. */
public final class SkillCommands {

    private static final int BAR_LENGTH = 20;

    private final CommandServices svc;
    private final SkillTracker tracker;

    public SkillCommands(CommandServices svc, SkillTracker tracker) {
        this.svc = svc;
        this.tracker = tracker;
    }

    public void register(Commands commands) {
        commands.register(node(), "Visa färdigheter / Show skills", List.of("fardigheter", "skill"));
    }

    private record PlayerSkills(String name, Map<Skill, SkillProgress> progress) {
    }

    private LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("skills")
                .executes(c -> {
                    Player player = svc.requirePlayer(c.getSource());
                    if (player != null) {
                        UUID uuid = player.getUniqueId();
                        String name = player.getName();
                        svc.tasks().run(player, () -> new PlayerSkills(name, svc.core().skills().load(uuid)),
                                result -> show(player, result, uuid));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("top")
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (Skill s : Skill.values()) {
                                        if (s.key().startsWith(b.getRemainingLowerCase())) {
                                            b.suggest(s.key());
                                        }
                                    }
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    CommandSender sender = c.getSource().getSender();
                                    String input = StringArgumentType.getString(c, "skill");
                                    svc.tasks().run(sender, () -> {
                                        Skill skill = Skill.parse(input).orElseThrow(() -> new DomainException("skills.unknown_skill"));
                                        return Map.entry(skill, svc.core().skills().top(skill, 10));
                                    }, result -> showTop(sender, result.getKey(), result.getValue()));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(CommandServices.onlinePlayerNames())
                        .executes(c -> {
                            CommandSender sender = c.getSource().getSender();
                            String name = StringArgumentType.getString(c, "player");
                            svc.tasks().run(sender, () -> {
                                NordiaPlayer target = svc.core().players().requireByName(name);
                                return Map.entry(target.uuid(), new PlayerSkills(target.name(), svc.core().skills().load(target.uuid())));
                            }, result -> show(sender, result.getValue(), result.getKey()));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private void show(CommandSender viewer, PlayerSkills skills, UUID subject) {
        SupportedLocale locale = svc.messages().localeOf(viewer);
        LevelCurve curve = svc.core().skills().curve();
        svc.messages().send(viewer, "skills.header", "player", skills.name());
        Map<Skill, SkillProgress> merged = new EnumMap<>(skills.progress());
        for (Skill skill : Skill.values()) {
            // Online players have unflushed XP in memory; prefer it when available.
            tracker.progress(subject, skill).ifPresent(p -> merged.put(skill, p));
            SkillProgress p = merged.get(skill);
            Component name = svc.messages().render(locale, "skill." + skill.name());
            if (p.level() >= curve.maxLevel()) {
                svc.messages().send(viewer, "skills.entry_max", "skill", name, "level", p.level(), "xp", p.xp());
                continue;
            }
            long[] within = curve.progressWithinLevel(p.xp());
            svc.messages().send(viewer, "skills.entry", "skill", name, "level", p.level(),
                    "xp", within[0], "next", within[1], "bar", bar(within[0], within[1]));
        }
    }

    private void showTop(CommandSender viewer, Skill skill, List<SkillService.LeaderboardEntry> entries) {
        SupportedLocale locale = svc.messages().localeOf(viewer);
        Component name = svc.messages().render(locale, "skill." + skill.name());
        if (entries.isEmpty()) {
            svc.messages().send(viewer, "skills.top.empty");
            return;
        }
        svc.messages().send(viewer, "skills.top.header", "skill", name);
        int rank = 1;
        for (SkillService.LeaderboardEntry entry : entries) {
            svc.messages().send(viewer, "skills.top.entry", "rank", rank++, "player", entry.name(),
                    "level", entry.level(), "xp", entry.xp());
        }
    }

    static Component bar(long current, long total) {
        int filled = total <= 0 ? BAR_LENGTH : (int) Math.min(BAR_LENGTH, (current * BAR_LENGTH) / total);
        return Component.text("|".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("|".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY));
    }
}
