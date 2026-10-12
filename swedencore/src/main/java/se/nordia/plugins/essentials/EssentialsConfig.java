package se.nordia.plugins.essentials;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Settings from essentials.yml. Each kind of teleport has its own warm-up (seconds the player must stand still) and
 * cooldown (seconds before the next one). Values are clamped to sane ranges so a typo cannot break the server.
 * The texts (motd, rules, info) are MiniMessage lines written by the server owner.
 */
public record EssentialsConfig(
        Map<Kind, Rule> rules,
        int maxHomes,
        int tpaTimeoutSeconds,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        boolean logActions,
        int tprMinRadius,
        int tprMaxRadius,
        int nearRadius,
        List<String> motd,
        List<String> rulesText,
        List<String> info
) {
    public enum Kind {
        SPAWN, HOME, WARP, TPA, BACK, TPR
    }

    public record Rule(int warmupSeconds, int cooldownSeconds) {
        public Rule {
            warmupSeconds = Math.clamp(warmupSeconds, 0, 60);
            cooldownSeconds = Math.clamp(cooldownSeconds, 0, 86_400);
        }
    }

    public EssentialsConfig {
        Map<Kind, Rule> copy = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            copy.put(kind, rules.getOrDefault(kind, new Rule(3, 0)));
        }
        rules = Map.copyOf(copy);
        maxHomes = Math.clamp(maxHomes, 1, 100);
        tpaTimeoutSeconds = Math.clamp(tpaTimeoutSeconds, 5, 600);
        tprMinRadius = Math.clamp(tprMinRadius, 0, 100_000);
        tprMaxRadius = Math.clamp(tprMaxRadius, Math.max(tprMinRadius, 16), 100_000);
        nearRadius = Math.clamp(nearRadius, 8, 2_000);
        motd = List.copyOf(motd);
        rulesText = List.copyOf(rulesText);
        info = List.copyOf(info);
    }

    public Rule rule(Kind kind) {
        return rules.get(kind);
    }

    public static EssentialsConfig defaults() {
        Map<Kind, Rule> rules = new EnumMap<>(Kind.class);
        rules.put(Kind.SPAWN, new Rule(3, 0));
        rules.put(Kind.HOME, new Rule(3, 10));
        rules.put(Kind.WARP, new Rule(3, 0));
        rules.put(Kind.TPA, new Rule(3, 30));
        rules.put(Kind.BACK, new Rule(3, 10));
        rules.put(Kind.TPR, new Rule(5, 300));
        return new EssentialsConfig(rules, 3, 60, true, true, true, 250, 2_000, 200,
                List.of(), List.of(), List.of());
    }
}
