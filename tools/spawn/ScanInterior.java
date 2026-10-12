import java.nio.file.Path;

/** Finds roofed, walkable floor near the spawn at a given feet level, as a coarse map (each character = 3x3 blocks). */
public final class ScanInterior {
    static boolean free(String b) {
        return b == null || b.contains("air") || b.contains("carpet") || b.contains("torch") || b.contains("lantern")
                || b.contains("banner") || b.contains("sign") || b.contains("button") || b.contains("pressure") || b.contains("rail")
                || b.contains("candle") || b.contains("flower") || b.contains("grass") || b.contains("fern") || b.contains("vine");
    }

    public static void main(String[] args) {
        WorldReader w = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        int cx = Integer.parseInt(args[0]);
        int cz = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int r = Integer.parseInt(args[3]);
        System.out.println("feet level y=" + y + ", centre " + cx + "," + cz + ", legend: R = roofed floor you can stand on, . = open sky floor, # = blocked, _ = no floor");
        for (int z = cz - r; z <= cz + r; z += 3) {
            StringBuilder sb = new StringBuilder();
            for (int x = cx - r; x <= cx + r; x += 3) {
                int roofed = 0;
                int open = 0;
                int blocked = 0;
                for (int dz = 0; dz < 3; dz++) {
                    for (int dx = 0; dx < 3; dx++) {
                        int bx = x + dx;
                        int bz = z + dz;
                        String floor = w.block(bx, y - 1, bz);
                        if (!free(w.block(bx, y, bz)) || !free(w.block(bx, y + 1, bz))) {
                            blocked++;
                        } else if (free(floor)) {
                            continue;
                        } else {
                            boolean roof = false;
                            for (int h = 2; h <= 14 && !roof; h++) {
                                roof = !free(w.block(bx, y + h, bz));
                            }
                            if (roof) {
                                roofed++;
                            } else {
                                open++;
                            }
                        }
                    }
                }
                sb.append(roofed >= 5 ? 'R' : open >= 5 ? '.' : blocked >= 5 ? '#' : '_');
            }
            System.out.println(String.format("z=%d ", z) + sb);
        }
    }
}
