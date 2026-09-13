package se.nordia.swedencore.cities;

import se.nordia.swedencore.properties.Region;

import java.time.Instant;

/** A city: a named square area around a centre with its own treasury. */
public record City(long id, String name, String world, int centerX, int centerZ, int radius, Instant foundedAt) {

    public boolean contains(String otherWorld, int x, int z) {
        return world.equals(otherWorld) && Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
    }

    /** The city's area as a full-height region (for overlap checks). */
    public Region area() {
        return new Region(world, centerX - radius, Integer.MIN_VALUE / 2, centerZ - radius, centerX + radius, Integer.MAX_VALUE / 2, centerZ + radius);
    }
}
