import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Landscape: an irregular harbour basin with a narrow northern mouth between two harbour arms; the town climbs a
 * natural slope around it (streets follow the contour lines); a rocky cliff carries the plateau of the king's hall; a
 * river springs on the plateau, falls down the cliff and cascades through the town into the harbour. The box edge
 * blends into the natural terrain read from the world.
 */
final class Terrain {

    static final int CU = 0, CV = -32;
    static final int QUAY_V = -3;          // straight quay in front of the market (the arrival ship moors here)
    static final int QUAY_HALF = 24;
    static final int R_ARM = 49;
    static final double MOUTH = Math.toRadians(13);
    static final double SLOPE = 0.34;      // town slope: blocks up per block away from the quay
    static final int PROMENADE = 7;
    static final int PLATEAU = 40, PLATEAU_DQ = 68;
    static final int[] STREET_LEVELS = {3, 9, 15, 21};
    static final int BLEND = 26;

    static WorldReader nat;
    static int worldX, worldZ;

    static final int[][] H = new int[Canvas.SX][Canvas.SZ];
    static final int[][] DQ = new int[Canvas.SX][Canvas.SZ];      // distance to the basin (town cells)
    static final int[][] LEVEL = new int[Canvas.SX][Canvas.SZ];   // street level index + 1 if the cell is a street
    static final boolean[][] WALL = new boolean[Canvas.SX][Canvas.SZ];
    static final boolean[][] ROCK = new boolean[Canvas.SX][Canvas.SZ];
    static final boolean[][] RIVER = new boolean[Canvas.SX][Canvas.SZ];
    static final boolean[][] BASIN = new boolean[Canvas.SX][Canvas.SZ];
    static final double[][] WEIGHT = new double[Canvas.SX][Canvas.SZ];

    record Corridor(int u0, int u1, int vFrom, int vTo, int[] heights) {}

    static final List<Corridor> CORRIDORS = new ArrayList<>();

    // ------------------------------------------------------------------ noise

    static double hash(int x, int z, int seed) {
        long h = x * 374761393L + z * 668265263L + seed * 144665L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xffffff) / (double) 0xffffff;
    }

    static double noise(double x, double z, double scale, int seed) {
        x /= scale;
        z /= scale;
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0, seed), b = hash(x0 + 1, z0, seed), c = hash(x0, z0 + 1, seed), d = hash(x0 + 1, z0 + 1, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    static double fbm(double x, double z, double scale, int seed) {
        return 0.55 * noise(x, z, scale, seed) + 0.3 * noise(x, z, scale / 2.3, seed + 7) + 0.15 * noise(x, z, scale / 5.1, seed + 13);
    }

    static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------ geometry

    static double radius(int u, int v) {
        return Math.hypot(u - CU, v - CV);
    }

    /** Angle from due south, 0..PI. */
    static double angle(int u, int v) {
        return Math.abs(Math.atan2(u - CU, v - CV));
    }

    static double basinRadius(int u, int v) {
        double a = Math.atan2(u - CU, v - CV);
        return 30 + 2.5 * Math.sin(3 * a + 0.4) + 3 * (noise(a * 9, 0, 1, 41) - 0.5);
    }

    static boolean basinShape(int u, int v) {
        double r = radius(u, v);
        if (v > CV && Math.abs(u) <= QUAY_HALF) return v < QUAY_V && r < basinRadius(u, v) + 8;
        return r < basinRadius(u, v);
    }

    static boolean arm(int u, int v) {
        if (v >= CV || basinShape(u, v)) return false;
        double r = radius(u, v);
        double outer = R_ARM + 3 * (noise(u, v, 11, 43) - 0.5);
        return r < outer && Math.PI - angle(u, v) > MOUTH;
    }

    static boolean inBasin(int u, int v) {
        return Canvas.inXZ(u, v) && BASIN[u - Canvas.MINX][v - Canvas.MINZ];
    }

    static int dq(int u, int v) {
        return Canvas.inXZ(u, v) ? DQ[u - Canvas.MINX][v - Canvas.MINZ] : 999;
    }

    /** 0..1: how much of the full town slope applies (tapers to the shore on the flanks). */
    static double taper(int u, int v) {
        return smooth((v - CV + 3) / 46.0);
    }

    static boolean mountainSector(int u, int v) {
        return angle(u, v) < Math.toRadians(52);
    }

    /** The natural-looking slope before streets are cut into it. */
    static double slopeHeight(int u, int v) {
        int d = dq(u, v);
        if (d < PROMENADE) return 0;
        double s = d - PROMENADE;
        double a = Math.atan2(u - CU, v - CV);
        double ridges = 2.2 * Math.sin(a * 4 + 1.1) * smooth(s / 22.0);
        double bumps = 2.4 * (fbm(u, v, 18, 11) - 0.5) * smooth(s / 12.0);
        double base = s * SLOPE + ridges + bumps;
        double town = base * taper(u, v);
        if (mountainSector(u, v)) {
            double rise = smooth((d - (PLATEAU_DQ - 12)) / 12.0);
            double sector = smooth((Math.toRadians(52) - angle(u, v)) / Math.toRadians(10));
            double plateau = PLATEAU + 1.2 * (fbm(u, v, 9, 5) - 0.5) * 2;
            town = town + (plateau - town) * rise * sector;
        } else if (d > PLATEAU_DQ - 8) {
            town = Math.min(town, 24 + 6 * fbm(u, v, 20, 17));
        }
        return town;
    }

    static double designHeight(int u, int v) {
        if (basinShape(u, v)) {
            double r = radius(u, v);
            return -10 - 2 * (1 - r / 34);
        }
        if (v < CV) {
            if (arm(u, v)) {
                double outer = R_ARM + 3 * (noise(u, v, 11, 43) - 0.5);
                double edge = outer - radius(u, v);
                return edge < 3 ? 1 - (3 - edge) * 2.4 : 1;
            }
            if (radius(u, v) < R_ARM + 3) return -9 - 2 * fbm(u, v, 12, 4); // the harbour mouth channel
            double shore = Math.max(0, Math.min(radius(u, v) - R_ARM, (CV - v) + 2.0));
            return Math.max(-13 - 3 * fbm(u, v, 30, 3), -1 - shore * 0.7);
        }
        double h = slopeHeight(u, v);
        if (h < 1 && taper(u, v) < 0.6) {
            // flank shore: beaches down into the bay
            double sea = (CV - v + 8) * 0.9;
            return Math.max(-13, Math.min(h, 1 - sea));
        }
        return h;
    }

    static double naturalHeight(int u, int v) {
        if (nat == null) return 4;
        int x = worldX + u, z = worldZ + v;
        if (x >= -648 && x <= -521 && z >= 236 && z <= 363) return -13; // the first spawn draft: becomes bay again
        int s = nat.surface(x, z);
        if (s == WorldReader.MISSING) return -12;
        String top = nat.block(x, s, z);
        if (top != null && (top.equals("water") || top.contains("kelp") || top.contains("seagrass"))) return nat.floor(x, z) - 64;
        int g = nat.ground(x, z);
        return (g == WorldReader.MISSING ? s : g) - 64;
    }

    // ------------------------------------------------------------------ generate

    static void generate() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) BASIN[u - Canvas.MINX][v - Canvas.MINZ] = basinShape(u, v);
        basinDistance();
        double[][] design = new double[Canvas.SX][Canvas.SZ];
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) design[u - Canvas.MINX][v - Canvas.MINZ] = designHeight(u, v);
        cutStreets(design);
        corridors(design);
        Wild.shape(design);
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int edge = Math.min(Math.min(u - Canvas.MINX, Canvas.MAXX - u), Math.min(v - Canvas.MINZ, Canvas.MAXZ - v));
                double w = smooth(edge / (double) BLEND);
                double h = w * design[u - Canvas.MINX][v - Canvas.MINZ] + (1 - w) * naturalHeight(u, v);
                H[u - Canvas.MINX][v - Canvas.MINZ] = (int) Math.round(h);
                WEIGHT[u - Canvas.MINX][v - Canvas.MINZ] = w;
            }
        for (Corridor c : CORRIDORS)
            for (int u = c.u0(); u <= c.u1(); u++)
                for (int v = c.vFrom(); v <= c.vTo(); v++) H[u - Canvas.MINX][v - Canvas.MINZ] = c.heights()[v - c.vFrom()];
        Wild.erode(H);
        rivers();
        classify();
        paint();
        Wild.detail();
        River.fill();
    }

    /** The river from the spring under the world tree down through the town, and a brook from the eastern fields. */
    static void rivers() {
        List<double[]> main = List.of(new double[] {42, 92}, new double[] {44, 84}, new double[] {43, 76}, new double[] {40, 68},
                new double[] {38, 60}, new double[] {34, 51}, new double[] {29, 41}, new double[] {27, 31}, new double[] {23, 21},
                new double[] {20, 11}, new double[] {16, 1}, new double[] {12, -9});
        River.carve(main, 3, 6, 3, Canvas.SEA);
        int spring = River.levelAt(42, 92);
        if (spring != River.NONE) River.pond(42, 92, 5, spring, 3);
        int join = River.levelAt(34, 51);
        if (join == River.NONE) join = River.levelAt(35, 51);
        List<double[]> brook = List.of(new double[] {76, 62}, new double[] {66, 58}, new double[] {56, 57}, new double[] {46, 53},
                new double[] {36, 51});
        River.carve(brook, 2, 3, 11, join);
    }

    static void basinDistance() {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                boolean b = BASIN[u - Canvas.MINX][v - Canvas.MINZ];
                DQ[u - Canvas.MINX][v - Canvas.MINZ] = b ? 0 : Integer.MAX_VALUE;
                if (b) q.add(new int[] {u, v});
            }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        // chamfer distance: 10 straight, 14 diagonal, stored /10
        int[][] dist = new int[Canvas.SX][Canvas.SZ];
        for (int[] row : dist) java.util.Arrays.fill(row, Integer.MAX_VALUE);
        for (int[] c : q) dist[c[0] - Canvas.MINX][c[1] - Canvas.MINZ] = 0;
        ArrayDeque<int[]> work = new ArrayDeque<>(q);
        while (!work.isEmpty()) {
            int[] c = work.poll();
            int d0 = dist[c[0] - Canvas.MINX][c[1] - Canvas.MINZ];
            for (int[] d : dirs) {
                int nu = c[0] + d[0], nv = c[1] + d[1];
                if (!Canvas.inXZ(nu, nv)) continue;
                int nd = d0 + (d[0] != 0 && d[1] != 0 ? 14 : 10);
                if (nd < dist[nu - Canvas.MINX][nv - Canvas.MINZ]) {
                    dist[nu - Canvas.MINX][nv - Canvas.MINZ] = nd;
                    work.add(new int[] {nu, nv});
                }
            }
        }
        for (int u = 0; u < Canvas.SX; u++) for (int v = 0; v < Canvas.SZ; v++) DQ[u][v] = dist[u][v] / 10;
    }

    /** Streets follow contour lines: a band around each street level is flattened to that level. */
    /**
     * Streets follow contour lines at fixed width: the contour of each street level is traced, then widened to five
     * blocks and flattened to that level (small cuts and fills along the way).
     */
    static void cutStreets(double[][] design) {
        double[][] s = new double[Canvas.SX][];
        for (int i = 0; i < Canvas.SX; i++) s[i] = design[i].clone();
        boolean[][] claimed = new boolean[Canvas.SX][Canvas.SZ];
        for (int i = 0; i < STREET_LEVELS.length; i++) {
            int level = STREET_LEVELS[i];
            for (int u = Canvas.MINX + 1; u < Canvas.MAXX; u++)
                for (int v = Canvas.MINZ + 1; v < Canvas.MAXZ; v++) {
                    if (v < CV + 2 || taper(u, v) < 0.35 || inBasin(u, v) || w0(u, v) < 0.9) continue;
                    double here = s[u - Canvas.MINX][v - Canvas.MINZ];
                    if (here < level) continue;
                    boolean contour = false;
                    for (B.Dir d : B.Dir.values()) if (s[u + d.dx - Canvas.MINX][v + d.dz - Canvas.MINZ] < level) contour = true;
                    if (!contour) continue;
                    for (int du = -2; du <= 2; du++)
                        for (int dv = -2; dv <= 2; dv++) {
                            int nu = u + du, nv = v + dv;
                            if (!Canvas.inXZ(nu, nv) || du * du + dv * dv > 5 || inBasin(nu, nv)) continue;
                            if (claimed[nu - Canvas.MINX][nv - Canvas.MINZ]) continue;
                            claimed[nu - Canvas.MINX][nv - Canvas.MINZ] = true;
                            design[nu - Canvas.MINX][nv - Canvas.MINZ] = level;
                            LEVEL[nu - Canvas.MINX][nv - Canvas.MINZ] = i + 1;
                        }
                }
        }
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++)
                if (!inBasin(u, v) && dq(u, v) < PROMENADE && v >= CV - 8 && !arm(u, v)) LEVEL[u - Canvas.MINX][v - Canvas.MINZ] = 9;
    }

    /** Blend weight from the box edge (available before the heights are final). */
    static double w0(int u, int v) {
        int edge = Math.min(Math.min(u - Canvas.MINX, Canvas.MAXX - u), Math.min(v - Canvas.MINZ, Canvas.MAXZ - v));
        return smooth(edge / (double) BLEND);
    }

    /** Stairways straight up the slope: never descending, one block per step. */
    static void corridors(double[][] design) {
        addCorridor(design, -5, 5, QUAY_V + PROMENADE - 1, 101);
        addCorridor(design, -46, -42, CV + 16, 34);
        addCorridor(design, 42, 46, CV + 16, 34);
    }

    static void addCorridor(double[][] design, int u0, int u1, int vFrom, int vTo) {
        int n = vTo - vFrom + 1;
        int[] hs = new int[n];
        int cu = (u0 + u1) / 2;
        int[] rising = new int[n];
        for (int i = 0; i < n; i++) {
            int want = (int) Math.round(design[cu - Canvas.MINX][vFrom + i - Canvas.MINZ]);
            rising[i] = i == 0 ? want : Math.max(rising[i - 1], want);
        }
        // never more than one block per step: start climbing early where the slope turns into a cliff
        hs[n - 1] = rising[n - 1];
        for (int i = n - 2; i >= 0; i--) hs[i] = Math.max(rising[i], hs[i + 1] - 1);
        CORRIDORS.add(new Corridor(u0, u1, vFrom, vTo, hs));
        for (int u = u0; u <= u1; u++)
            for (int v = vFrom; v <= vTo; v++) design[u - Canvas.MINX][v - Canvas.MINZ] = hs[v - vFrom];
    }

    static Corridor corridorAt(int u, int v) {
        for (Corridor c : CORRIDORS) if (u >= c.u0() && u <= c.u1() && v >= c.vFrom() && v <= c.vTo()) return c;
        return null;
    }

    static double distToSegment(double px, double pz, int[] a, int[] b) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double t = Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / (dx * dx + dz * dz)));
        return Math.hypot(px - (a[0] + t * dx), pz - (a[1] + t * dz));
    }

    static int h(int u, int v) {
        if (!Canvas.inXZ(u, v)) return -12;
        return H[u - Canvas.MINX][v - Canvas.MINZ];
    }

    static double w(int u, int v) {
        return Canvas.inXZ(u, v) ? WEIGHT[u - Canvas.MINX][v - Canvas.MINZ] : 0;
    }

    static boolean isStreet(int u, int v) {
        return Canvas.inXZ(u, v) && LEVEL[u - Canvas.MINX][v - Canvas.MINZ] > 0;
    }

    static boolean town(int u, int v) {
        return w(u, v) > 0.97 && v >= CV - 10 && dq(u, v) < PLATEAU_DQ - 8 && taper(u, v) > 0.05 || arm(u, v);
    }

    // ------------------------------------------------------------------ materials

    static void classify() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int here = h(u, v);
                int drop = 0;
                for (B.Dir d : B.Dir.values()) drop = Math.max(drop, here - h(u + d.dx, v + d.dz));
                boolean quayEdge = !inBasin(u, v) && here >= Canvas.SEA && (town(u, v) || arm(u, v)) && nearBasin(u, v);
                if (quayEdge || (drop >= 2 && (town(u, v) || isStreet(u, v)) && corridorAt(u, v) == null)) WALL[u - Canvas.MINX][v - Canvas.MINZ] = true;
                else if (drop >= 3 || (!town(u, v) && here > 8 && fbm(u, v, 6, 21) > 0.66)) ROCK[u - Canvas.MINX][v - Canvas.MINZ] = true;
            }
    }

    static boolean nearBasin(int u, int v) {
        for (B.Dir d : B.Dir.values()) if (inBasin(u + d.dx, v + d.dz)) return true;
        return false;
    }

    static String masonry() {
        double r = Canvas.TERRAIN_RNG.nextDouble();
        if (r < 0.40) return "stone_bricks";
        if (r < 0.56) return "mossy_stone_bricks";
        if (r < 0.66) return "cracked_stone_bricks";
        if (r < 0.76) return "cobblestone";
        if (r < 0.84) return "andesite";
        if (r < 0.92) return "tuff_bricks";
        return "mossy_cobblestone";
    }

    static String rock(int y) {
        double r = Canvas.TERRAIN_RNG.nextDouble();
        if (r < 0.38) return "stone";
        if (r < 0.58) return "andesite";
        if (r < 0.70) return "tuff";
        if (r < 0.77) return y > 20 ? "calcite" : "cobblestone";
        if (r < 0.86) return "mossy_cobblestone";
        if (r < 0.93) return "gravel";
        return "diorite";
    }

    static void paint() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int top = h(u, v);
                int iu = u - Canvas.MINX, iv = v - Canvas.MINZ;
                boolean water = top < Canvas.SEA;
                if (Wild.isWild(u, v) && !RIVER[iu][iv]) {
                    Wild.paint(u, v);
                    continue;
                }
                Canvas.fill(u, Canvas.MINY, v, u, top - 4, v, "stone");
                if (WALL[iu][iv]) {
                    int low = top;
                    for (B.Dir d : B.Dir.values()) low = Math.min(low, h(u + d.dx, v + d.dz));
                    for (int y = Math.min(low - 2, top - 3); y <= top; y++) Canvas.set(u, y, v, masonry());
                } else if (ROCK[iu][iv]) {
                    int low = top;
                    for (B.Dir d : B.Dir.values()) low = Math.min(low, h(u + d.dx, v + d.dz));
                    for (int y = Math.min(low - 1, top - 3); y <= top; y++) Canvas.set(u, y, v, rock(y));
                    if (top > Canvas.SEA && Canvas.TERRAIN_RNG.nextDouble() < 0.3) Canvas.set(u, top, v, Canvas.TERRAIN_RNG.nextDouble() < 0.5 ? "mossy_cobblestone" : "moss_block");
                } else if (water) {
                    Canvas.fill(u, top - 3, v, u, top - 1, v, "stone");
                    double r = Canvas.TERRAIN_RNG.nextDouble();
                    Canvas.set(u, top, v, r < 0.6 ? "sand" : r < 0.85 ? "gravel" : "clay");
                } else if (top <= Canvas.SEA + 1 && nearWater(u, v, 2)) {
                    Canvas.fill(u, top - 3, v, u, top, v, "sand");
                    if (Canvas.TERRAIN_RNG.nextDouble() < 0.2) Canvas.set(u, top, v, "gravel");
                } else {
                    Canvas.fill(u, top - 3, v, u, top - 1, v, "dirt");
                    double r = fbm(u, v, 7, 31);
                    Canvas.set(u, top, v, r > 0.68 ? "coarse_dirt" : r < 0.24 ? "podzol" : "grass_block");
                }
                if (water) {
                    Canvas.fill(u, top + 1, v, u, Canvas.SEA, v, "water");
                    if (top < Canvas.SEA - 2 && Canvas.TERRAIN_RNG.nextDouble() < 0.07) Canvas.set(u, top + 1, v, "seagrass");
                }
                Canvas.setGround(u, v, water ? Canvas.SEA : top);
            }
    }

    static boolean nearWater(int u, int v, int r) {
        for (int du = -r; du <= r; du++)
            for (int dv = -r; dv <= r; dv++) if (h(u + du, v + dv) < Canvas.SEA) return true;
        return false;
    }

}
