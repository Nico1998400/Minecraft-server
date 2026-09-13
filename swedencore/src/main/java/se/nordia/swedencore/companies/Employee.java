package se.nordia.swedencore.companies;

import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.skills.Skill;

import java.time.Instant;
import java.util.UUID;

/**
 * An active membership of a player in a company.
 *
 * @param positionId    null for the owner or members without a position
 * @param jobId         job of the position, null if none
 * @param skill         skill of the job, null if the job has no skill
 */
public record Employee(
        long id,
        long companyId,
        String companyName,
        UUID playerUuid,
        String playerName,
        CompanyRole role,
        Long positionId,
        String positionTitle,
        String jobId,
        Skill skill,
        Money salaryPerHour,
        Instant hiredAt
) {
}
