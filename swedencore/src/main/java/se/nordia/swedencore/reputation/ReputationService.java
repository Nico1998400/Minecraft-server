package se.nordia.swedencore.reputation;

import se.nordia.swedencore.core.DomainException;
import se.nordia.swedencore.database.Database;
import se.nordia.swedencore.database.Tx;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reputation is trust — separate from skill (capability) and wealth (success).
 *
 * <p>Scores are clamped to [-100, 100]. Every change is an auditable event; changes with an idempotency key are
 * applied at most once (e.g. one penalty per unpaid payroll entry).
 */
public final class ReputationService {

    public static final int MIN = -100;
    public static final int MAX = 100;

    public enum SubjectType {
        PLAYER, COMPANY
    }

    public record Subject(SubjectType type, String id) {
        public Subject {
            Objects.requireNonNull(type);
            Objects.requireNonNull(id);
        }

        public static Subject player(UUID uuid) {
            return new Subject(SubjectType.PLAYER, uuid.toString());
        }

        public static Subject company(long companyId) {
            return new Subject(SubjectType.COMPANY, Long.toString(companyId));
        }
    }

    public record Event(long id, int delta, int scoreAfter, String reason, Instant createdAt) {
    }

    private final Database database;

    public ReputationService(Database database) {
        this.database = database;
    }

    /**
     * Adjusts a subject's reputation inside the caller's transaction.
     *
     * @return the new score (unchanged if the idempotency key was already used)
     */
    public int adjust(Tx tx, Subject subject, int delta, String reason, String referenceType, String referenceId,
                      String idempotencyKey) throws SQLException {
        if (delta == 0) {
            return score(tx, subject);
        }
        if (Math.abs(delta) > 200) {
            throw new IllegalArgumentException("Reputation delta out of range: " + delta);
        }
        int current = lockScore(tx, subject);
        int next = Math.clamp((long) current + delta, MIN, MAX);
        boolean inserted = tx.queryOne("""
                        INSERT INTO reputation_events (subject_type, subject_id, delta, score_after, reason,
                                                       reference_type, reference_id, idempotency_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id""",
                rs -> true, subject.type(), subject.id(), delta, next, reason, referenceType, referenceId, idempotencyKey)
                .isPresent();
        if (!inserted) {
            return current;
        }
        if (subject.type() == SubjectType.PLAYER) {
            tx.update("UPDATE players SET reputation = ? WHERE uuid = ?", next, UUID.fromString(subject.id()));
        } else {
            tx.update("UPDATE companies SET reputation = ? WHERE id = ?", next, Long.parseLong(subject.id()));
        }
        return next;
    }

    public int adjust(Subject subject, int delta, String reason, String idempotencyKey) {
        return database.inTransaction(tx -> adjust(tx, subject, delta, reason, null, null, idempotencyKey));
    }

    public int score(Subject subject) {
        return database.inTransaction(tx -> score(tx, subject));
    }

    public int score(Tx tx, Subject subject) throws SQLException {
        return switch (subject.type()) {
            case PLAYER -> tx.queryOne("SELECT reputation FROM players WHERE uuid = ?", rs -> rs.getInt(1),
                    UUID.fromString(subject.id())).orElseThrow(() -> new DomainException("player.unknown"));
            case COMPANY -> tx.queryOne("SELECT reputation FROM companies WHERE id = ?", rs -> rs.getInt(1),
                    Long.parseLong(subject.id())).orElseThrow(() -> new DomainException("company.not_found"));
        };
    }

    private int lockScore(Tx tx, Subject subject) throws SQLException {
        return switch (subject.type()) {
            case PLAYER -> tx.queryOne("SELECT reputation FROM players WHERE uuid = ? FOR UPDATE", rs -> rs.getInt(1),
                    UUID.fromString(subject.id())).orElseThrow(() -> new DomainException("player.unknown"));
            case COMPANY -> tx.queryOne("SELECT reputation FROM companies WHERE id = ? FOR UPDATE", rs -> rs.getInt(1),
                    Long.parseLong(subject.id())).orElseThrow(() -> new DomainException("company.not_found"));
        };
    }

    public List<Event> history(Subject subject, int limit) {
        return database.inTransaction(tx -> tx.queryList("""
                        SELECT id, delta, score_after, reason, created_at FROM reputation_events
                        WHERE subject_type = ? AND subject_id = ? ORDER BY id DESC LIMIT ?""",
                rs -> new Event(rs.getLong("id"), rs.getInt("delta"), rs.getInt("score_after"), rs.getString("reason"),
                        Tx.instant(rs, "created_at")),
                subject.type(), subject.id(), Math.clamp(limit, 1, 50)));
    }
}
