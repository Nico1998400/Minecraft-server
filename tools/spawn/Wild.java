import java.util.ArrayDeque;

/**
 * The terrain-detail pass for the land around the town: everything the town does not stand on is reshaped with
 * geological forms (a ravined granite cliff under the plateau, tors on the plateau, wooded ridges and coastal cliffs in
 * the west, rolling farmland in the east, a rocky headland with sea stacks) and then eroded. The town itself is
 * locked; the two meet over a feathered band so no seam shows.
 */
final class Wild {

    static final int SX = Canvas.SX, SZ = Canvas.SZ;
    /** 0 = locked town ground, 1 = fully wild. */
    static final double[][] WILD = new double[SX][SZ];

    enum Area { PLATEAU, WEST, EAST, HEADLAND, SEA }

    static Area area(int u, int v) {
        if (v < Terrain.CV - 4 && Math.hypot(u - 100, v + 106) > 30) return Area.SEA;
        if (Math.hypot(u - 100, v + 106) <= 30) return Area.HEADLAND;
        if (Terrain.mountainSector(u, v) || v > 60) return Area.PLATEAU;
        return u < 0 ? Area.WEST : Area.EAST;
    }

    static Geology.Rock rock(int u, int v) {
        return switch (area(u, v)) {
            case PLATEAU -> Geology.Rock.GRANITE;
            case WEST, HEADLAND, SEA -> Geology.Rock.COAST;
            case EAST -> Geology.Rock.HILL;
        };
    }

    static boolean locked(int u, int v) {
        if (Terrain.inBasin(u, v) || Terrain.arm(u, v) || Terrain.isStreet(u, v) || Terrain.corridorAt(u, v) != null) return true;
        if (Terrain.w0(u, v) > 0.97 && v >= Terrain.CV - 10 && Terrain.dq(u, v) < Terrain.PLATEAU_DQ - 10 && Terrain.taper(u, v) > 0.1) return true;
        if (Math.abs(u) <= 26 && v >= 60 && v <= 114) return true;               // king's hall and its podium
        if (Math.hypot(u - 38, v - 84) < 22) return true;                         // world tree and the spring
        if (Math.abs(u) <= 3 && v >= 100) return true;                            // path behind the hall
        return false;
    }

    /** Distance from locked ground, feathered into a 0..1 wildness. */
    static void mask() {
        int[][] dist = new int[SX][SZ];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++) {
                boolean l = locked(i + Canvas.MINX, j + Canvas.MINZ);
                dist[i][j] = l ? 0 : Integer.MAX_VALUE;
                if (l) q.add(new int[] {i, j});
            }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (B.Dir d : B.Dir.values()) {
                int ni = c[0] + d.dx, nj = c[1] + d.dz;
                if (ni < 0 || nj < 0 || ni >= SX || nj >= SZ || dist[ni][nj] <= dist[c[0]][c[1]] + 1) continue;
                dist[ni][nj] = dist[c[0]][c[1]] + 1;
                q.add(new int[] {ni, nj});
            }
        }
        for (int i = 0; i < SX; i++) for (int j = 0; j < SZ; j++) WILD[i][j] = Terrain.smooth((dist[i][j] - 1) / 9.0);
    }

    static double wild(int u, int v) {
        return Canvas.inXZ(u, v) ? WILD[u - Canvas.MINX][v - Canvas.MINZ] : 0;
    }

    // ------------------------------------------------------------------ primary forms

    /** Where the plateau cliff stands (in distance from the basin), with ravines cutting back and buttresses pushing out. */
    static double cliffLine(int u, int v) {
        double a = Math.atan2(u - Terrain.CU, v - Terrain.CV);
        double ravines = Math.pow(Geology.ridged(a * 60, 3, 9, 3, 501), 2.2) * 11;   // narrow cuts into the plateau
        double buttress = (Geology.fbm(a * 40, 7, 14, 3, 502) - 0.5) * 7;
        return Terrain.PLATEAU_DQ - 9 + ravines - buttress;
    }

    /** Cliff profile: a steep face with two ledges, 0 at the foot, 1 at the rim. */
    static double cliffProfile(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        double s = Terrain.smooth(t);
        double ledges = 0.08 * Math.sin(s * Math.PI * 3);
        return Math.max(0, Math.min(1, s + ledges));
    }

    static double plateauTop(int u, int v) {
        double roll = 3.2 * (Geology.fbm(u, v, 34, 3, 511) - 0.5) * 2;
        double tors = 0;
        int[][] torAt = {{-52, 86, 9}, {-38, 104, 6}, {64, 96, 8}, {70, 76, 5}, {-66, 70, 5}};
        for (int[] t : torAt) {
            double d = Math.hypot(u - t[0], v - t[1]);
            double shape = Math.max(0, 1 - d / (t[2] + 5 * Geology.noise(u, v, 5, 512)));
            tors = Math.max(tors, shape * shape * (t[2] + 3));
        }
        return Terrain.PLATEAU + roll + tors;
    }

    static double westHills(int u, int v, double base) {
        double ridge = Geology.ridged(u * 0.9 + 13, v, 46, 4, 521);
        double valley = Geology.fbm(u, v, 30, 3, 522);
        return base + 10 * ridge * Terrain.smooth((-u - 55) / 34.0) + 4 * (valley - 0.5);
    }

    static double eastFields(int u, int v, double base) {
        double roll = 5 * (Geology.fbm(u, v, 40, 3, 531) - 0.4) + 2 * (Geology.fbm(u, v, 13, 2, 532) - 0.5);
        return Math.max(2, Math.min(base, 16) * 0.6 + roll + 4);
    }

    /**
     * Reshapes the design heights of all wild ground. Called with the town design already in place; wild cells are
     * blended towards the geological form by their wildness.
     */
    static void shape(double[][] design) {
        mask();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                double k = WILD[i][j];
                if (k <= 0) continue;
                double town = design[i][j];
                double form = town;
                int d = Terrain.dq(u, v);
                switch (area(u, v)) {
                    case PLATEAU -> {
                        double line = cliffLine(u, v);
                        double below = Math.min(town, 22 + 5 * Geology.fbm(u, v, 20, 3, 541));
                        double foot = below + 3 * Geology.ridged(u, v, 18, 3, 542);
                        double t = (d - line) / (5 + 3 * Geology.noise(u, v, 11, 543));
                        form = foot + (plateauTop(u, v) - foot) * cliffProfile(t);
                        if (!Terrain.mountainSector(u, v)) {
                            // outside the mountain sector the plateau drops away into the flanks as hills
                            double side = Terrain.smooth((Terrain.angle(u, v) - Math.toRadians(50)) / Math.toRadians(16));
                            double hills = u < 0 ? westHills(u, v, Math.min(town, 24)) : eastFields(u, v, Math.min(town, 24));
                            form = form * (1 - side) + hills * side;
                        }
                    }
                    case WEST -> {
                        double hills = westHills(u, v, Math.max(town, 2));
                        // coastal cliffs: the land stays high right up to a ragged coastline, then drops into the bay
                        double coast = Terrain.CV - 3 + 5 * (Geology.fbm(u, v, 16, 3, 551) - 0.5) * 2;
                        if (v < coast) form = -7 - 4 * Geology.noise(u, v, 9, 552);
                        else form = Math.max(hills, 7 + 6 * Geology.ridged(u, v, 22, 3, 553));
                    }
                    case EAST -> {
                        form = eastFields(u, v, town);
                        if (town < 1) form = town; // keep the eastern beaches
                    }
                    case HEADLAND -> {
                        double r = Math.hypot(u - 100, v + 106) + 4 * (Geology.fbm(u, v, 8, 3, 561) - 0.5);
                        double top = 11 + 4 * Geology.ridged(u, v, 14, 3, 562);
                        form = r < 17 ? top : r < 20 ? top - (r - 17) * 5 : -9 - Geology.noise(u, v, 7, 563) * 3;
                    }
                    case SEA -> form = town;
                }
                design[i][j] = town + (form - town) * k;
            }
        seaStacks(design);
    }

    /** Rock pillars standing off the western cliffs and the headland. */
    static void seaStacks(double[][] design) {
        int[][] stacks = {{-78, -46, 3, 9}, {-95, -40, 2, 6}, {-66, -52, 2, 5}, {86, -122, 3, 10}, {118, -100, 2, 7}, {-110, -34, 3, 11}};
        for (int[] s : stacks)
            for (int du = -5; du <= 5; du++)
                for (int dv = -5; dv <= 5; dv++) {
                    int u = s[0] + du, v = s[1] + dv;
                    if (!Canvas.inXZ(u, v)) continue;
                    double d = Math.hypot(du, dv) + Geology.noise(u, v, 2, 571) * 1.5;
                    if (d > s[2] + 1.5) continue;
                    double hgt = d <= s[2] ? s[3] - d * 0.8 : -1;
                    int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                    design[i][j] = Math.max(design[i][j], hgt);
                    WILD[i][j] = 1;
                }
    }

    // ------------------------------------------------------------------ erosion

    static void erode(int[][] H) {
        double[][] h = new double[SX][SZ];
        double[][] s = new double[SX][SZ];
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++) {
                h[i][j] = H[i][j];
                double edge = Terrain.WEIGHT[i][j];
                s[i][j] = H[i][j] > Canvas.SEA + 1 ? WILD[i][j] * Terrain.smooth((edge - 0.3) / 0.5) : 0;
            }
        Geology.hydraulic(h, s, 70_000, 77);
        Geology.thermal(h, s, 12, 1.6);
        Geology.strata(h, s, 4, 0.05, 0.02, 2.2, 0.5);
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++) if (s[i][j] > 0) H[i][j] = (int) Math.round(h[i][j]);
    }

    // ------------------------------------------------------------------ paint and detail

    static boolean isWild(int u, int v) {
        return wild(u, v) > 0.35 && !Terrain.WALL[u - Canvas.MINX][v - Canvas.MINZ];
    }

    static void paint(int u, int v) {
        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
        int top = Terrain.H[i][j];
        boolean nearWater = Terrain.nearWater(u, v, 2);
        Geology.paintColumn(u, v, top, Geology.Relief.of(Terrain.H, i, j), rock(u, v), 200, nearWater);
    }

    static void detail() {
        boolean[][] w = new boolean[SX][SZ];
        for (int i = 0; i < SX; i++) for (int j = 0; j < SZ; j++) w[i][j] = WILD[i][j] > 0.6 && !Terrain.RIVER[i][j];
        Geology.cliffDetail(Terrain.H, w);
        // caves in the plateau cliff and the western coast
        int[][] caves = {{-34, 50, 0, 1}, {58, 44, 0, 1}, {-86, -30, 0, -1}, {-20, 58, 0, 1}};
        for (int[] c : caves) {
            int u = c[0], v = c[1];
            // walk towards the cliff until the ground rises sharply, then dig in
            for (int k = 0; k < 20; k++) {
                if (Terrain.h(u, v + c[3]) - Terrain.h(u, v) >= 6) break;
                v += c[3];
            }
            if (Terrain.h(u, v + c[3]) - Terrain.h(u, v) < 6 || !isWild(u, v + c[3])) continue;
            Geology.cave(u, Terrain.h(u, v) + 3, v + c[3], c[2], c[3], 11, 2.6, 2.4);
        }
    }
}
