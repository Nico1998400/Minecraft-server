package se.nordia.swedencore.jobs;

import se.nordia.swedencore.companies.CompanyRole;
import se.nordia.swedencore.companies.CompanyService;
import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;
import se.nordia.swedencore.economy.Money;
import se.nordia.swedencore.skills.Skill;
import se.nordia.swedencore.skills.SkillService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Job board and hiring: positions, applications, acceptance.
 *
 * <p>Flow: company posts a position → players browse and apply (skill requirement checked) → owner/manager accepts or
 * rejects → accepted applicant becomes an employee with the position's salary.
 */
public final class JobService {

    private static final Pattern TITLE = Pattern.compile("[\\p{IsLatin}\\p{N} &.,'()/+\\-]{3,40}");

    public record Job(String id, Skill skill) {
    }

    private final Database database;
    private final CompanyService companies;
    private final SkillService skills;
    private volatile List<Job> jobs;

    public JobService(Database database, CompanyService companies, SkillService skills) {
        this.database = database;
        this.companies = companies;
        this.skills = skills;
    }

    public List<Job> jobs() {
        List<Job> cached = jobs;
        if (cached == null) {
            cached = database.inTransaction(tx -> tx.queryList("SELECT id, skill_id FROM jobs ORDER BY sort_order",
                    rs -> new Job(rs.getString("id"), rs.getString("skill_id") == null ? null : Skill.valueOf(rs.getString("skill_id")))));
            jobs = cached;
        }
        return cached;
    }

    public Job requireJob(String input) {
        String id = input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
        return jobs().stream().filter(j -> j.id().equals(id)).findFirst()
                .orElseThrow(() -> new DomainException("job.unknown_job"));
    }

    // ------------------------------------------------------------------ positions

    public JobPosition createPosition(UUID actor, long companyId, String jobId, String rawTitle, int requiredLevel,
                                      Money salaryPerHour, int openings) {
        Job job = requireJob(jobId);
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (!TITLE.matcher(title).matches()) {
            throw new DomainException("job.invalid_title");
        }
        int maxLevel = skills.curve().maxLevel();
        if (requiredLevel < 1 || requiredLevel > maxLevel || (job.skill() == null && requiredLevel != 1)) {
            throw DomainException.of("job.invalid_level", "max", maxLevel);
        }
        if (openings < 1 || openings > 100) {
            throw new DomainException("job.invalid_openings");
        }
        companies.validateSalary(salaryPerHour);
        return database.inTransaction(tx -> {
            companies.lockActive(tx, companyId);
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            long open = tx.queryLong("SELECT count(*) FROM job_positions WHERE company_id = ? AND status = 'OPEN'", companyId);
            if (open >= companies.config().maxOpenPositions()) {
                throw DomainException.of("job.too_many_positions", "max", companies.config().maxOpenPositions());
            }
            long id = tx.queryLong("""
                            INSERT INTO job_positions (company_id, job_id, title, required_level, salary_per_hour, openings)
                            VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    companyId, job.id(), title, requiredLevel, salaryPerHour.ore(), openings);
            return position(tx, id).orElseThrow();
        });
    }

    public void closePosition(UUID actor, long positionId) {
        database.inTransactionVoid(tx -> {
            long companyId = companyOfPosition(tx, positionId);
            companies.lockActive(tx, companyId);
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            tx.update("UPDATE job_positions SET status = 'CLOSED', closed_at = now() WHERE id = ? AND status = 'OPEN'", positionId);
            tx.update("UPDATE job_applications SET status = 'CLOSED', decided_at = now(), decided_by = ? WHERE position_id = ? AND status = 'PENDING'",
                    actor, positionId);
        });
    }

    public Optional<JobPosition> position(long id) {
        return database.inTransaction(tx -> position(tx, id));
    }

    Optional<JobPosition> position(Tx tx, long id) throws SQLException {
        return tx.queryOne(POSITION_SELECT + " WHERE p.id = ?", JobService::mapPosition, id);
    }

    /** The job board: open positions of active companies with vacancies, best paid first. */
    public List<JobPosition> openPositions(int limit, int offset) {
        return database.inTransaction(tx -> tx.queryList(POSITION_SELECT + """
                         WHERE p.status = 'OPEN' AND c.status = 'ACTIVE'
                           AND (SELECT count(*) FROM company_employees e WHERE e.position_id = p.id AND e.ended_at IS NULL) < p.openings
                         ORDER BY p.salary_per_hour DESC, p.id
                         LIMIT ? OFFSET ?""",
                JobService::mapPosition, Math.clamp(limit, 1, 50), Math.max(0, offset)));
    }

    public List<JobPosition> companyPositions(long companyId) {
        return database.inTransaction(tx -> tx.queryList(POSITION_SELECT + " WHERE p.company_id = ? AND p.status = 'OPEN' ORDER BY p.id",
                JobService::mapPosition, companyId));
    }

    // ------------------------------------------------------------------ applications

    public JobApplication apply(UUID applicant, long positionId, String rawMessage) {
        String message = sanitizeMessage(rawMessage);
        return database.inTransaction(tx -> {
            long companyId = companyOfPosition(tx, positionId);
            companies.lockActive(tx, companyId);
            JobPosition position = lockPosition(tx, positionId);
            if (!position.open()) {
                throw new DomainException("job.position_closed");
            }
            if (!position.hasVacancy()) {
                throw new DomainException("job.position_full");
            }
            if (companies.roleOf(tx, companyId, applicant).isPresent()) {
                throw new DomainException("job.already_member");
            }
            requireSkill(tx, applicant, position);
            long pending = tx.queryLong("SELECT count(*) FROM job_applications WHERE applicant_uuid = ? AND status = 'PENDING'", applicant);
            if (pending >= companies.config().maxPendingApplications()) {
                throw DomainException.of("job.too_many_applications", "max", companies.config().maxPendingApplications());
            }
            if (tx.queryOne("SELECT 1 FROM job_applications WHERE position_id = ? AND applicant_uuid = ? AND status = 'PENDING'",
                    rs -> true, positionId, applicant).isPresent()) {
                throw new DomainException("job.already_applied");
            }
            long id = tx.queryLong("INSERT INTO job_applications (position_id, applicant_uuid, message) VALUES (?, ?, ?) RETURNING id",
                    positionId, applicant, message);
            return application(tx, id).orElseThrow();
        });
    }

    public List<JobApplication> pendingApplications(UUID actor, long companyId) {
        return database.inTransaction(tx -> {
            companies.requireRole(tx, companyId, actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            return tx.queryList(APPLICATION_SELECT + " WHERE p.company_id = ? AND a.status = 'PENDING' ORDER BY a.id",
                    JobService::mapApplication, companyId);
        });
    }

    public List<JobApplication> applicationsOf(UUID applicant) {
        return database.inTransaction(tx -> tx.queryList(APPLICATION_SELECT + " WHERE a.applicant_uuid = ? ORDER BY a.id DESC LIMIT 20",
                JobService::mapApplication, applicant));
    }

    public Optional<JobApplication> application(long id) {
        return database.inTransaction(tx -> application(tx, id));
    }

    Optional<JobApplication> application(Tx tx, long id) throws SQLException {
        return tx.queryOne(APPLICATION_SELECT + " WHERE a.id = ?", JobService::mapApplication, id);
    }

    /** Hires the applicant. Re-validates vacancy, membership and skill at decision time. */
    public JobApplication accept(UUID actor, long applicationId) {
        JobApplication accepted = database.inTransaction(tx -> {
            JobApplication app = application(tx, applicationId).orElseThrow(() -> new DomainException("job.application_not_found"));
            companies.lockActive(tx, app.companyId());
            companies.requireRole(tx, app.companyId(), actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            JobPosition position = lockPosition(tx, app.positionId());
            JobApplication.Status status = lockApplicationStatus(tx, applicationId);
            if (status != JobApplication.Status.PENDING) {
                throw new DomainException("job.application_not_pending");
            }
            if (!position.hasVacancy()) {
                throw new DomainException("job.position_full");
            }
            if (companies.roleOf(tx, app.companyId(), app.applicantUuid()).isPresent()) {
                throw new DomainException("job.already_member");
            }
            requireSkill(tx, app.applicantUuid(), position);
            tx.update("""
                            INSERT INTO company_employees (company_id, player_uuid, role, position_id, salary_per_hour)
                            VALUES (?, ?, 'EMPLOYEE', ?, ?)""",
                    app.companyId(), app.applicantUuid(), position.id(), position.salaryPerHour().ore());
            tx.update("UPDATE job_applications SET status = 'ACCEPTED', decided_at = now(), decided_by = ? WHERE id = ?", actor, applicationId);
            // The applicant is now employed here; their other pending applications to this company are moot.
            tx.update("""
                    UPDATE job_applications SET status = 'CLOSED', decided_at = now()
                    WHERE applicant_uuid = ? AND status = 'PENDING'
                      AND position_id IN (SELECT id FROM job_positions WHERE company_id = ?)""", app.applicantUuid(), app.companyId());
            return application(tx, applicationId).orElseThrow();
        });
        companies.announceMembershipChange(accepted.companyId());
        return accepted;
    }

    public JobApplication reject(UUID actor, long applicationId) {
        return database.inTransaction(tx -> {
            JobApplication app = application(tx, applicationId).orElseThrow(() -> new DomainException("job.application_not_found"));
            companies.lockActive(tx, app.companyId());
            companies.requireRole(tx, app.companyId(), actor, CompanyRole.OWNER, CompanyRole.MANAGER);
            lockPosition(tx, app.positionId());
            if (lockApplicationStatus(tx, applicationId) != JobApplication.Status.PENDING) {
                throw new DomainException("job.application_not_pending");
            }
            tx.update("UPDATE job_applications SET status = 'REJECTED', decided_at = now(), decided_by = ? WHERE id = ?", actor, applicationId);
            return application(tx, applicationId).orElseThrow();
        });
    }

    public void withdraw(UUID applicant, long applicationId) {
        database.inTransactionVoid(tx -> {
            JobApplication app = application(tx, applicationId).orElseThrow(() -> new DomainException("job.application_not_found"));
            if (!app.applicantUuid().equals(applicant)) {
                throw new DomainException("job.application_not_found");
            }
            companies.lockActive(tx, app.companyId());
            lockPosition(tx, app.positionId());
            if (lockApplicationStatus(tx, applicationId) != JobApplication.Status.PENDING) {
                throw new DomainException("job.application_not_pending");
            }
            tx.update("UPDATE job_applications SET status = 'WITHDRAWN', decided_at = now() WHERE id = ?", applicationId);
        });
    }

    // ------------------------------------------------------------------ helpers

    private void requireSkill(Tx tx, UUID player, JobPosition position) throws SQLException {
        if (position.skill() == null || position.requiredLevel() <= 1) {
            return;
        }
        int level = skills.level(tx, player, position.skill());
        if (level < position.requiredLevel()) {
            throw DomainException.of("job.skill_too_low", "level", position.requiredLevel(), "current", level,
                    "skill", position.skill());
        }
    }

    private static String sanitizeMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private static long companyOfPosition(Tx tx, long positionId) throws SQLException {
        return tx.queryOne("SELECT company_id FROM job_positions WHERE id = ?", rs -> rs.getLong(1), positionId)
                .orElseThrow(() -> new DomainException("job.position_not_found"));
    }

    private JobPosition lockPosition(Tx tx, long positionId) throws SQLException {
        tx.queryOne("SELECT id FROM job_positions WHERE id = ? FOR UPDATE", rs -> true, positionId)
                .orElseThrow(() -> new DomainException("job.position_not_found"));
        return position(tx, positionId).orElseThrow();
    }

    private static JobApplication.Status lockApplicationStatus(Tx tx, long applicationId) throws SQLException {
        return tx.queryOne("SELECT status FROM job_applications WHERE id = ? FOR UPDATE",
                rs -> JobApplication.Status.valueOf(rs.getString(1)), applicationId).orElseThrow();
    }

    private static final String POSITION_SELECT = """
            SELECT p.*, c.name AS company_name, c.reputation AS company_reputation, j.skill_id,
                   (SELECT count(*) FROM company_employees e WHERE e.position_id = p.id AND e.ended_at IS NULL) AS filled
            FROM job_positions p
            JOIN companies c ON c.id = p.company_id
            JOIN jobs j ON j.id = p.job_id
            """;

    private static JobPosition mapPosition(ResultSet rs) throws SQLException {
        String skill = rs.getString("skill_id");
        return new JobPosition(rs.getLong("id"), rs.getLong("company_id"), rs.getString("company_name"),
                rs.getInt("company_reputation"), rs.getString("job_id"), skill == null ? null : Skill.valueOf(skill),
                rs.getString("title"), rs.getInt("required_level"), Money.ofOre(rs.getLong("salary_per_hour")),
                rs.getInt("openings"), rs.getInt("filled"), "OPEN".equals(rs.getString("status")));
    }

    private static final String APPLICATION_SELECT = """
            SELECT a.*, p.title, p.company_id, c.name AS company_name, pl.name AS applicant_name
            FROM job_applications a
            JOIN job_positions p ON p.id = a.position_id
            JOIN companies c ON c.id = p.company_id
            JOIN players pl ON pl.uuid = a.applicant_uuid
            """;

    private static JobApplication mapApplication(ResultSet rs) throws SQLException {
        return new JobApplication(rs.getLong("id"), rs.getLong("position_id"), rs.getString("title"),
                rs.getLong("company_id"), rs.getString("company_name"), Tx.uuid(rs, "applicant_uuid"),
                rs.getString("applicant_name"), rs.getString("message"),
                JobApplication.Status.valueOf(rs.getString("status")), Tx.instant(rs, "created_at"));
    }
}
