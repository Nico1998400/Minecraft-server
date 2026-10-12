package se.nordia.plugins.essentials;

import java.util.Objects;

/** A saved position in a world, as plain values (the Paper layer turns it into a Bukkit location). */
public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

    public Spot {
        Objects.requireNonNull(world);
        if (world.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid spot");
        }
    }
}
