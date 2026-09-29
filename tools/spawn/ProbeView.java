import java.nio.file.Path;

public final class ProbeView {
    public static void main(String[] args) {
        WorldReader w = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        int[][] pts = {
                {-1052, -1078}, {-1139, -1151}, {-1110, -1033},
                {-1000, -950}, {-900, -950}, {-850, -1078}, {-900, -1078},
                {-1000, -1200}, {-1100, -900}, {-950, -1000},
                {-800, -1100}, {-900, -1200}, {-1200, -900},
        };
        for (int[] p : pts) {
            int x = p[0], z = p[1];
            int s = w.surface(x, z);
            int f = w.floor(x, z);
            String b = s == WorldReader.MISSING ? "MISSING" : w.block(x, s, z);
            System.out.printf("%d,%d surface=%s floor=%s top=%s%n", x, z, s == WorldReader.MISSING ? "MISS" : String.valueOf(s), f == WorldReader.MISSING ? "MISS" : String.valueOf(f), b);
        }
    }
}
