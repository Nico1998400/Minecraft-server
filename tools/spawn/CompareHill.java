import java.nio.file.Path;

/** Compare live overworld vs kullar backup at the castle coast. */
public final class CompareHill {
    public static void main(String[] args) {
        WorldReader live = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        WorldReader kullar = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world_kullar_2026-09-29/dimensions/minecraft/overworld/region"));
        int[][] pts = {
                {-1052, -1078}, {-1000, -1078}, {-950, -1078}, {-900, -1078}, {-850, -1078},
                {-1052, -1000}, {-1000, -1000}, {-950, -1000}, {-900, -1000},
                {-1052, -1150}, {-1000, -1150}, {-1100, -1100}, {-980, -930},
                {-1020, -1020}, {-1080, -1040}, {-1040, -1100},
        };
        System.out.println("=== surface live vs kullar ===");
        int diff = 0;
        for (int[] p : pts) {
            String a = col(live, p[0], p[1]);
            String b = col(kullar, p[0], p[1]);
            String mark = a.equals(b) ? "  " : "!!";
            if (!a.equals(b)) diff++;
            System.out.printf("%s %d,%d  LIVE %s  KULLAR %s%n", mark, p[0], p[1], a, b);
        }
        System.out.println("point diffs: " + diff);

        System.out.println("=== grid -1100..-900 x -1150..-950 step 25: mismatch count ===");
        int n = 0, stoneLive = 0, stoneK = 0, airLive = 0, airK = 0;
        for (int x = -1100; x <= -900; x += 25) {
            for (int z = -1150; z <= -950; z += 25) {
                String sl = live.block(x, 70, z);
                String sk = kullar.block(x, 70, z);
                if (sl == null) sl = "MISS";
                if (sk == null) sk = "MISS";
                if (!sl.equals(sk)) n++;
                if ("stone".equals(sl) || "cobblestone".equals(sl)) stoneLive++;
                if ("stone".equals(sk) || "cobblestone".equals(sk)) stoneK++;
                if ("air".equals(sl) || "cave_air".equals(sl)) airLive++;
                if ("air".equals(sk) || "cave_air".equals(sk)) airK++;
            }
        }
        System.out.printf("y=70 mismatches=%d stone/cobble live=%d kullar=%d air live=%d kullar=%d%n", n, stoneLive, stoneK, airLive, airK);

        System.out.println("=== under castle -1052,-1078 column ===");
        dumpCol(live, "LIVE", -1052, -1078);
        dumpCol(kullar, "KULLAR", -1052, -1078);
        System.out.println("=== coast sample -980,-1020 ===");
        dumpCol(live, "LIVE", -980, -1020);
        dumpCol(kullar, "KULLAR", -980, -1020);
        System.out.println("=== water edge -1000,-950 ===");
        dumpCol(live, "LIVE", -1000, -950);
        dumpCol(kullar, "KULLAR", -1000, -950);
    }

    static String col(WorldReader w, int x, int z) {
        int s = w.surface(x, z);
        if (s == WorldReader.MISSING) return "MISSING";
        return s + " " + w.block(x, s, z);
    }

    static void dumpCol(WorldReader w, String name, int x, int z) {
        System.out.print(name + " ");
        for (int y : new int[]{190, 140, 120, 90, 70, 62, 50, 30, 0}) {
            String b = w.block(x, y, z);
            System.out.print("y" + y + "=" + (b == null ? "?" : b) + " ");
        }
        System.out.println();
    }
}
