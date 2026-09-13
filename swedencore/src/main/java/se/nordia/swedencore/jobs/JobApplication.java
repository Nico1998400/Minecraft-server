package se.nordia.swedencore.jobs;

import java.time.Instant;
import java.util.UUID;

public record JobApplication(
        long id,
        long positionId,
        String positionTitle,
        long companyId,
        String companyName,
        UUID applicantUuid,
        String applicantName,
        String message,
        Status status,
        Instant createdAt
) {
    public enum Status {
        PENDING, ACCEPTED, REJECTED, WITHDRAWN, CLOSED
    }
}
