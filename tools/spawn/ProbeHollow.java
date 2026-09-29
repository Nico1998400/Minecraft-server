import java.nio.file.Path;

/** Counts columns in the transplanted hub that still have an air pocket under the ground, and why. */
public final class ProbeHollow {
    public static void main(String[] args) {
        WorldReader w = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        int ox = 1567, oz = 930, hollowCols = 0, cols = 0;
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        StringBuilder ex = new StringBuilder();
        for (int u = -200; u <= 200; u += 6)
            for (int v = -195; v <= 195; v += 6) {
                int x = ox + u, z = oz + v;
                int g = w.ground(x, z);
                if (g == WorldReader.MISSING || g < 80) continue;
                cols++;
                int air = 0;
                for (int y = 60; y < g - 20; y++) {
                    String b = w.block(x, y, z);
                    if ("air".equals(b) || "cave_air".equals(b)) air++;
                }
                if (air < 6) continue;
                hollowCols++;
                int d = Transplant.deepestBuilt(w, x, z);
                String cause = d < 9000 ? w.block(x, d + 64, z) : "none";
                why.merge(cause, 1, Integer::sum);
                if (ex.length() < 1500) ex.append(String.format("  %d,%d g=%d air=%d deepestBuilt=%d (%s)%n", x, z, g, air, d + 64, cause));
            }
        System.out.println("columns with ground>=80: " + cols + ", still hollow: " + hollowCols);
        System.out.println("deepest built block in hollow columns: " + why);
        System.out.print(ex);
    }
}
