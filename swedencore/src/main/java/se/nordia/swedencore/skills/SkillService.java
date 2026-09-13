package se.nordia.swedencore.skills;

import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent skill progress. XP only ever increases and is capped at the curve's max. */
public final class SkillService {

    /** Guard against absurd single grants (bugs, exploits). */
    static final long MAX_SINGLE_GRANT = 1_000_000;

    public record LeaderboardEntry(UUID player, String name, long xp, int level) {
    }

    private final Database database;
    private final SkillsConfig config;

    public SkillService(Database database, SkillsConfig config) {
        this.database = database;
        this.config = config;
    }

    public SkillsConfig config() {
        return config;
    }

    public LevelCurve curve() {
        return config.curve();
    }

    public Map<Skill, SkillProgress> load(UUID player) {
        return database.inTransaction(tx -> load(tx, player));
    }

    public Map<Skill, SkillProgress> load(Tx tx, UUID player) throws SQLException {
        Map<Skill, SkillProgress> result = new EnumMap<>(Skill.class);
        for (Skill skill : Skill.values()) {
            result.put(skill, new SkillProgress(skill, 0, 1));
        }
        for (SkillProgress p : tx.queryList("SELECT skill_id, xp FROM skill_progress WHERE player_uuid = ?",
                rs -> {
                    long xp = rs.getLong("xp");
                    Skill skill = Skill.valueOf(rs.getString("skill_id"));
                    return new SkillProgress(skill, xp, curve().levelForXp(xp));
                }, player)) {
            result.put(p.skill(), p);
        }
        return result;
    }

    public int level(Tx tx, UUID player, Skill skill) throws SQLException {
        long xp = tx.queryOne("SELECT xp FROM skill_progress WHERE player_uuid = ? AND skill_id = ?",
                rs -> rs.getLong(1), player, skill).orElse(0L);
        return curve().levelForXp(xp);
    }

    public int level(UUID player, Skill skill) {
        return database.inTransaction(tx -> level(tx, player, skill));
    }

    public LevelChange addXp(UUID player, Skill skill, long amount) {
        return database.inTransaction(tx -> addXp(tx, player, skill, amount));
    }

    public LevelChange addXp(Tx tx, UUID player, Skill skill, long amount) throws SQLException {
        if (amount < 0 || amount > MAX_SINGLE_GRANT) {
            throw new IllegalArgumentException("XP amount out of range: " + amount);
        }
        tx.update("""
                INSERT INTO skill_progress (player_uuid, skill_id) VALUES (?, ?)
                ON CONFLICT (player_uuid, skill_id) DO NOTHING""", player, skill);
        long oldXp = tx.queryLong("SELECT xp FROM skill_progress WHERE player_uuid = ? AND skill_id = ? FOR UPDATE",
                player, skill);
        long newXp = Math.min(curve().maxXp(), Math.addExact(oldXp, amount));
        int oldLevel = curve().levelForXp(oldXp);
        int newLevel = curve().levelForXp(newXp);
        if (newXp != oldXp) {
            tx.update("UPDATE skill_progress SET xp = ?, level = ?, updated_at = now() WHERE player_uuid = ? AND skill_id = ?",
                    newXp, newLevel, player, skill);
        }
        return new LevelChange(skill, oldLevel, newLevel, newXp - oldXp, newXp);
    }

    /** Applies a batch of buffered XP for one player atomically. Skills are processed in enum order (lock order). */
    public List<LevelChange> addXp(UUID player, Map<Skill, Long> deltas) {
        return database.inTransaction(tx -> {
            List<LevelChange> changes = new ArrayList<>();
            for (Skill skill : Skill.values()) {
                Long delta = deltas.get(skill);
                if (delta != null && delta > 0) {
                    changes.add(addXp(tx, player, skill, delta));
                }
            }
            return changes;
        });
    }

    public List<LeaderboardEntry> top(Skill skill, int limit) {
        int safeLimit = Math.clamp(limit, 1, 50);
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT sp.player_uuid, p.name, sp.xp FROM skill_progress sp
                        JOIN players p ON p.uuid = sp.player_uuid
                        WHERE sp.skill_id = ? AND sp.xp > 0
                        ORDER BY sp.xp DESC, p.name
                        LIMIT ?""",
                rs -> {
                    long xp = rs.getLong("xp");
                    return new LeaderboardEntry(Tx.uuid(rs, "player_uuid"), rs.getString("name"), xp, curve().levelForXp(xp));
                }, skill, safeLimit));
    }
}
