package se.nordia.swedencore.jobs;

import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.skills.Skill;

/**
 * @param skill  skill of the job (null for jobs without a skill); the level requirement applies to this skill
 * @param filled active employees currently in this position
 */
public record JobPosition(
        long id,
        long companyId,
        String companyName,
        int companyReputation,
        String jobId,
        Skill skill,
        String title,
        int requiredLevel,
        Money salaryPerHour,
        int openings,
        int filled,
        boolean open
) {
    public boolean hasVacancy() {
        return open && filled < openings;
    }
}
