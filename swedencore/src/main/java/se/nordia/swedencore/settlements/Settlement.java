package se.nordia.swedencore.settlements;

import java.time.Instant;
import java.util.UUID;

/** A player-founded settlement. Its protected area is a square of the tier's radius around the centre. */
public record Settlement(long id, String name, String world, int centerX, int centerZ, Tier tier, UUID leader,
                         String leaderName, Instant foundedAt, int radius) {

    public enum Tier {
        OUTPOST, SETTLEMENT, VILLAGE, TOWN, CITY;

        public Tier next() {
            return this == CITY ? null : values()[ordinal() + 1];
        }
    }

    public enum Role {
        LEADER, OFFICER, RESIDENT
    }

    public boolean contains(String otherWorld, int x, int z) {
        return world.equals(otherWorld) && Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }
}
