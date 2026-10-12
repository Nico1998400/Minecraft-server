package se.nordia.plugins.guide;

import se.nordia.plugins.economy.AccountOwner;
import se.nordia.plugins.economy.EconomyService;
import se.nordia.plugins.localization.SupportedLocale;
import se.nordia.plugins.player.NordiaPlayer;
import se.nordia.plugins.player.PlayerService;
import se.nordia.plugins.skills.SkillProgress;
import se.nordia.plugins.skills.SkillService;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Collects read-only facts about a player for the guide's prompt. Blocking (database): run off the main thread. */
public final class GuideContextBuilder {

    private final EconomyService economy;
    private final SkillService skills;
    private final PlayerService players;

    public GuideContextBuilder(EconomyService economy, SkillService skills, PlayerService players) {
        this.economy = economy;
        this.skills = skills;
        this.players = players;
    }

    public String build(UUID player, SupportedLocale locale) {
        StringBuilder out = new StringBuilder();
        Optional<NordiaPlayer> profile = players.find(player);
        profile.ifPresent(p -> out.append("- Name: ").append(p.name()).append('\n')
                .append("- Reputation: ").append(p.reputation()).append('\n'));
        out.append("- Balance: ").append(economy.balance(AccountOwner.player(player)).format(locale.javaLocale())).append('\n');
        String trained = skills.load(player).values().stream()
                .filter(s -> s.xp() > 0)
                .sorted(Comparator.comparingInt(SkillProgress::level).reversed())
                .map(s -> s.skill().name().toLowerCase(Locale.ROOT) + " level " + s.level())
                .collect(Collectors.joining(", "));
        out.append("- Skills: ").append(trained.isEmpty() ? "none trained yet (a brand-new player)" : trained);
        return out.toString();
    }
}
