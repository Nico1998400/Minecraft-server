package se.nordia.swedencore.companies;

import java.time.Instant;
import java.util.UUID;

public record Company(long id, String name, UUID ownerUuid, Status status, int reputation, Instant foundedAt) {

    public enum Status {
        ACTIVE, DISSOLVED, BANKRUPT
    }

    public boolean active() {
        return status == Status.ACTIVE;
    }
}
