package se.nordia.swedencore.properties;

import java.util.Objects;

/** An axis-aligned block cuboid in a world, bounds inclusive. */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Region {
        Objects.requireNonNull(world);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid region bounds");
        }
    }

    /** Builds a region from two arbitrary corners. */
    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(String otherWorld, int x, int y, int z) {
        return world.equals(otherWorld) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean intersects(Region other) {
        return world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }
}
