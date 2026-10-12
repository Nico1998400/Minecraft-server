package se.nordia.plugins.skills;

public record LevelChange(Skill skill, int oldLevel, int newLevel, long xpApplied, long totalXp) {

    public boolean leveledUp() {
        return newLevel > oldLevel;
    }
}
