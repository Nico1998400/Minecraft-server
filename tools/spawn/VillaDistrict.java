import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * SydHamn villa district: a lake shore with waterfront plots, a gentle slope rising to a hill with view plots, a park
 * in the middle, named streets with pavements, and a limited number of individually designed villas on private lots.
 * Every lot is valued by its location (water, view, park, corner, size, street, privacy) and written to a registry for
 * the property system.
 */
final class VillaDistrict {

    static final Random R = new Random(2046);
    static final int SX = Canvas.SX, SZ = Canvas.SZ;
    static final int[][] H = Terrain.H;
    static final int[][] ROAD = new int[SX][SZ];      // 0 none, 1 carriageway, 2 pavement
    static final int[][] ROAD_Y = new int[SX][SZ];
    static final boolean[][] PARK = new boolean[SX][SZ];

    record Street(String name, List<double[]> pts, boolean main) {}

    static final List<Street> STREETS = new ArrayList<>();

    static {
        STREETS.add(new Street("Villavägen", List.of(new double[] {112, 6}, new double[] {72, 2}, new double[] {30, 10},
                new double[] {-10, 4}, new double[] {-50, 12}, new double[] {-90, 6}, new double[] {-112, 10}), true));
        STREETS.add(new Street("Strandvägen", List.of(new double[] {112, -36}, new double[] {86, -41}, new double[] {48, -35},
                new double[] {10, -40}, new double[] {-30, -35}, new double[] {-72, -41}, new double[] {-112, -38}), true));
        STREETS.add(new Street("Kyrkbacken", List.of(new double[] {30, 10}, new double[] {28, -12}, new double[] {26, -37}), false));
        STREETS.add(new Street("Sjögränd", List.of(new double[] {-70, 8}, new double[] {-74, -16}, new double[] {-72, -40}), false));
        STREETS.add(new Street("Backvägen", List.of(new double[] {-10, 4}, new double[] {-6, 28}, new double[] {8, 50},
                new double[] {30, 64}, new double[] {50, 70}), false));
        STREETS.add(new Street("Björkvägen", List.of(new double[] {72, 2}, new double[] {76, 28}, new double[] {72, 48}), false));
        STREETS.add(new Street("Parkgränd", List.of(new double[] {-50, 12}, new double[] {-56, 38}, new double[] {-42, 60}), false));
    }

    static final double[][] TURNING = {{50, 70, 7}, {72, 48, 6}, {-42, 60, 6}};

    // ------------------------------------------------------------------ terrain

    static double shore(double u) {
        return -60 + 9 * Math.sin(u / 37.0 + 0.6) + 5 * (Geology.fbm(u, 0, 23, 3, 901) - 0.5) * 2;
    }

    static double design(int u, int v) {
        double s = shore(u);
        if (v < s) return Math.max(-11, -2 - (s - v) * 0.55 - 2 * Geology.noise(u, v, 11, 902));
        double t = v - s;
        double base = t * 0.13 + 2.5 * (Geology.fbm(u, v, 40, 3, 903) - 0.5) * 2;
        double hill = 13 * Math.exp(-Math.pow(Math.hypot(u - 44, v - 70) / 34.0, 2));
        double ridge = 7 * Terrain.smooth((v - 40) / 50.0);
        double bank = Terrain.smooth(t / 4.0);
        return Math.max(0.6, (base + hill + ridge + 5) * bank + 0.6 * (1 - bank));
    }

    static void terrain() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int edge = Math.min(Math.min(u - Canvas.MINX, Canvas.MAXX - u), Math.min(v - Canvas.MINZ, Canvas.MAXZ - v));
                double w = Terrain.smooth(edge / 24.0);
                H[i][j] = (int) Math.round(w * design(u, v) + (1 - w) * Terrain.naturalHeight(u, v));
                Terrain.WEIGHT[i][j] = w;
            }
    }

    static boolean lake(int u, int v) {
        return Canvas.inXZ(u, v) && H[u - Canvas.MINX][v - Canvas.MINZ] < Canvas.SEA;
    }

    // ------------------------------------------------------------------ streets

    /**
     * Streets follow the land with an even grade (at most one block in four), stored in half blocks so slopes become
     * smooth ramps of slabs. The ground on either side is eased into embankments.
     */
    static void streets() {
        double[][] best = new double[SX][SZ];
        for (double[] row : best) java.util.Arrays.fill(row, 99);
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            int n = pts.size();
            double[] y = new double[n];
            for (int k = 0; k < n; k++) {
                int u = (int) Math.round(pts.get(k)[0]), v = (int) Math.round(pts.get(k)[1]);
                int existing = roadY(u, v);
                y[k] = existing != Integer.MIN_VALUE ? existing / 2.0 : Math.max(1, Terrain.h(u, v));
            }
            for (int pass = 0; pass < 10; pass++)
                for (int k = 1; k < n - 1; k++) y[k] = (y[k - 1] + 2 * y[k] + y[k + 1]) / 4;
            // grade limit: samples are half a block apart, so 1 in 4 is 0.125 per sample
            for (int k = 1; k < n; k++) y[k] = Math.max(y[k - 1] - 0.125, Math.min(y[k - 1] + 0.125, y[k]));
            for (int k = n - 2; k >= 0; k--) y[k] = Math.max(y[k + 1] - 0.125, Math.min(y[k + 1] + 0.125, y[k]));
            for (int k = 0; k < n; k++) {
                double[] p = pts.get(k);
                int half = (int) Math.round(y[k] * 2);
                for (int du = -5; du <= 5; du++)
                    for (int dv = -5; dv <= 5; dv++) {
                        int u = (int) Math.round(p[0]) + du, v = (int) Math.round(p[1]) + dv;
                        if (!Canvas.inXZ(u, v)) continue;
                        double d = Math.hypot(u - p[0], v - p[1]);
                        if (d > 4.6) continue;
                        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                        int kind = d <= 2.6 ? 1 : 2;
                        if (ROAD[i][j] == 1 && kind == 2) continue;
                        if (ROAD[i][j] == kind && d >= best[i][j]) continue;
                        ROAD[i][j] = kind;
                        ROAD_Y[i][j] = half;
                        best[i][j] = d;
                    }
            }
        }
        for (double[] c : TURNING) {
            int yy = roadY((int) c[0], (int) c[1]);
            if (yy == Integer.MIN_VALUE) yy = Terrain.h((int) c[0], (int) c[1]) * 2;
            for (int du = -9; du <= 9; du++)
                for (int dv = -9; dv <= 9; dv++) {
                    int u = (int) c[0] + du, v = (int) c[1] + dv;
                    double d = Math.hypot(du, dv);
                    if (!Canvas.inXZ(u, v) || d > c[2] + 2) continue;
                    int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                    int kind = d <= c[2] ? 1 : 2;
                    if (ROAD[i][j] == 1 && kind == 2) continue;
                    ROAD[i][j] = kind;
                    ROAD_Y[i][j] = yy;
                }
        }
        // embankments: ease the terrain towards the road level within a few blocks
        int[][] dist = new int[SX][SZ];
        for (int[] row : dist) java.util.Arrays.fill(row, 99);
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        int[][] near = new int[SX][SZ];
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++)
                if (ROAD[i][j] > 0) {
                    dist[i][j] = 0;
                    near[i][j] = Math.floorDiv(ROAD_Y[i][j] + (ROAD[i][j] == 2 ? 1 : 0), 2);
                    q.add(new int[] {i, j});
                    H[i][j] = Math.floorDiv(ROAD_Y[i][j], 2);
                }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (B.Dir d : B.Dir.values()) {
                int ni = c[0] + d.dx, nj = c[1] + d.dz;
                if (ni < 0 || nj < 0 || ni >= SX || nj >= SZ || dist[ni][nj] <= dist[c[0]][c[1]] + 1) continue;
                dist[ni][nj] = dist[c[0]][c[1]] + 1;
                near[ni][nj] = near[c[0]][c[1]];
                if (dist[ni][nj] < 8) q.add(new int[] {ni, nj});
            }
        }
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++)
                if (ROAD[i][j] == 0 && dist[i][j] < 8 && H[i][j] >= Canvas.SEA) {
                    double k = Terrain.smooth(1 - dist[i][j] / 8.0);
                    H[i][j] = (int) Math.round(H[i][j] + (near[i][j] - H[i][j]) * k);
                }
    }

    /** Road surface in half blocks at (u, v), or MIN_VALUE off the road. */
    static int roadY(int u, int v) {
        if (!Canvas.inXZ(u, v)) return Integer.MIN_VALUE;
        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
        return ROAD[i][j] > 0 ? ROAD_Y[i][j] : Integer.MIN_VALUE;
    }

    static boolean road(int u, int v) {
        return Canvas.inXZ(u, v) && ROAD[u - Canvas.MINX][v - Canvas.MINZ] > 0;
    }

    // ------------------------------------------------------------------ paint

    static void paint() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int top = H[i][j];
                Canvas.fill(u, Canvas.MINY, v, u, top - 4, v, "stone");
                if (top < Canvas.SEA) {
                    Canvas.fill(u, top - 3, v, u, top - 1, v, "stone");
                    Canvas.set(u, top, v, Geology.noise(u, v, 5, 911) < 0.6 ? "sand" : "gravel");
                    Canvas.fill(u, top + 1, v, u, Canvas.SEA, v, "water");
                    if (top < Canvas.SEA - 2 && R.nextDouble() < 0.05) Canvas.set(u, top + 1, v, "seagrass");
                    Canvas.setGround(u, v, Canvas.SEA);
                    continue;
                }
                Canvas.fill(u, top - 3, v, u, top - 1, v, "dirt");
                Canvas.setGround(u, v, top);
                if (ROAD[i][j] > 0) {
                    boolean carriage = ROAD[i][j] == 1;
                    int s = ROAD_Y[i][j] + (carriage ? 0 : 1);
                    int y = Math.floorDiv(s, 2);
                    Canvas.fill(u, top - 3, v, u, y, v, "stone");
                    Canvas.set(u, y, v, carriage ? (R.nextDouble() < 0.93 ? "polished_deepslate" : "cobbled_deepslate") : "smooth_stone");
                    Canvas.fill(u, y + 1, v, u, y + 5, v, "air");
                    if ((s & 1) == 1) Canvas.set(u, y + 1, v, carriage ? "polished_deepslate_slab[type=bottom]" : "smooth_stone_slab[type=bottom]");
                    H[i][j] = y;
                    Canvas.setGround(u, v, y);
                    Canvas.use(u, v, u, v);
                    Canvas.STREET[i][j] = true;
                } else if (top <= Canvas.SEA + 1 && Terrain.nearWater(u, v, 2)) {
                    Canvas.set(u, top, v, Geology.noise(u, v, 4, 912) < 0.5 ? "sand" : "gravel");
                } else {
                    double n = Geology.noise(u, v, 8, 913);
                    Canvas.set(u, top, v, n > 0.8 ? "podzol" : n < 0.1 ? "coarse_dirt" : "grass_block");
                }
            }
    }

    // ------------------------------------------------------------------ lots

    record Lot(int id, String street, int number, B.Frame frame, int w, int d, String tier, double score, int price,
               Villa.Family family, Villa.Info info, boolean water, boolean view, boolean corner, boolean park) {}

    static final List<Lot> LOTS = new ArrayList<>();

    static boolean free(int u, int v) {
        if (!Canvas.inXZ(u, v) || Terrain.WEIGHT[u - Canvas.MINX][v - Canvas.MINZ] < 0.9) return false;
        return !road(u, v) && !lake(u, v) && !PARK[u - Canvas.MINX][v - Canvas.MINZ] && !Canvas.used(u, v);
    }

    /** Walks along both sides of every street and carves out lots with their front on the pavement. */
    static void lots() {
        int id = 1;
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            int number = 1;
            for (int side : new int[] {-1, 1}) {
                int k = 6;
                while (k < pts.size() - 6) {
                    double[] a = pts.get(k - 1), b = pts.get(k + 1), p = pts.get(k);
                    double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                    double nx = -tz / len * side, nz = tx / len * side;
                    B.Dir primary = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? B.Dir.WEST : B.Dir.EAST) : (nz > 0 ? B.Dir.NORTH : B.Dir.SOUTH);
                    B.Dir secondary = Math.abs(nx) > Math.abs(nz) ? (nz > 0 ? B.Dir.NORTH : B.Dir.SOUTH) : (nx > 0 ? B.Dir.WEST : B.Dir.EAST);
                    int[][] sizes = {{30, 34}, {26, 30}, {22, 28}, {20, 24}, {17, 22}, {15, 20}};
                    boolean placed = false;
                    search:
                    for (int[] sz : sizes) for (int off : new int[] {6, 7, 5, 8}) for (B.Dir front : new B.Dir[] {primary, secondary}) {
                        int fu = (int) Math.round(p[0] + nx * off), fv = (int) Math.round(p[1] + nz * off);
                        int w = sz[0] - R.nextInt(3), d = sz[1] - R.nextInt(3);
                        B.Frame zero = B.Frame.facing(0, 0, 0, front);
                        int ou = fu - zero.wx(w / 2, d - 1), ov = fv - zero.wz(w / 2, d - 1);
                        B.Frame probe = B.Frame.facing(ou, 0, ov, front);
                        if (!lotFree(probe, w, d)) continue;
                        Lot lot = value(id++, s.name(), number, probe, w, d);
                        number += 2;
                        claim(probe, w, d);
                        LOTS.add(lot);
                        k += (int) (w * 2.2) + 4;
                        placed = true;
                        break search;
                    }
                    if (!placed) k += 3;
                }
                number = 2;
            }
        }
        System.out.println("villa lots: " + LOTS.size() + " rejected out/edge/road/lake/park/used/slope/frontage " + java.util.Arrays.toString(WHY));
    }

    static final int[] WHY = new int[8];

    static boolean lotFree(B.Frame f, int w, int d) {
        int min = 999, max = -999;
        for (int x = -1; x <= w; x++)
            for (int z = -1; z < d; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                if (z >= d - 5 && road(u, v)) continue; // the street edge may clip the front of the lot
                if (!free(u, v)) {
                    WHY[!Canvas.inXZ(u, v) ? 0 : Terrain.WEIGHT[u - Canvas.MINX][v - Canvas.MINZ] < 0.9 ? 1 : road(u, v) ? 2 : lake(u, v) ? 3 : PARK[u - Canvas.MINX][v - Canvas.MINZ] ? 4 : 5]++;
                    return false;
                }
                int g = Terrain.h(u, v);
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
        if (max - min > 9) {
            WHY[6]++;
            return false;
        }
        int pave = 0;
        for (int x = 0; x < w; x++) if (road(f.wx(x, d), f.wz(x, d)) || road(f.wx(x, d + 1), f.wz(x, d + 1))) pave++;
        if (pave < w / 2) WHY[7]++;
        return pave >= w / 2;
    }

    static void claim(B.Frame f, int w, int d) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (!road(f.wx(x, z), f.wz(x, z))) Canvas.use(f.wx(x, z), f.wz(x, z), f.wx(x, z), f.wz(x, z));
    }

    /** Location decides value as much as size: water, view, park, corner, street and privacy. */
    static Lot value(int id, String street, int number, B.Frame f, int w, int d) {
        int cu = f.wx(w / 2, d / 2), cv = f.wz(w / 2, d / 2);
        double waterDist = 99, parkDist = 99;
        int corner = 0, height = Terrain.h(cu, cv);
        for (int du = -40; du <= 40; du += 2)
            for (int dv = -40; dv <= 40; dv += 2) {
                double dd = Math.hypot(du, dv);
                if (lake(cu + du, cv + dv)) waterDist = Math.min(waterDist, dd);
                if (Canvas.inXZ(cu + du, cv + dv) && PARK[cu + du - Canvas.MINX][cv + dv - Canvas.MINZ]) parkDist = Math.min(parkDist, dd);
            }
        for (int x = -2; x <= w + 1; x += w + 3) for (int z = 0; z < d; z++) if (road(f.wx(x, z), f.wz(x, z))) corner = 1;
        boolean water = waterDist < 24, view = height >= 14, park = parkDist < 26;
        double score = 1.2 + w * d / 260.0 + (water ? 3.2 - waterDist / 12 : 0) + (view ? 1.6 + (height - 14) * 0.12 : 0)
                       + (park ? 1.0 : 0) + corner * 0.8 + (street.equals("Strandvägen") ? 0.8 : 0)
                       + (street.equals("Björkvägen") || street.equals("Backvägen") ? 0.5 : 0) + (cu > 60 ? 0.4 : 0);
        String tier = score >= 7 ? "PREMIUM" : score >= 5 ? "LARGE" : score >= 3.4 ? "MEDIUM" : "SMALL";
        int base = switch (tier) {
            case "PREMIUM" -> 1_800_000;
            case "LARGE" -> 950_000;
            case "MEDIUM" -> 520_000;
            default -> 260_000;
        };
        int price = (int) (Math.round(base * (0.85 + score * 0.05) / 5000.0) * 5000);
        return new Lot(id, street, number, f, w, d, tier, score, price, null, null, water, view, corner == 1, park);
    }

    // ------------------------------------------------------------------ build lots

    static void houses() {
        List<Lot> built = new ArrayList<>();
        Villa.Family last = null;
        java.util.Map<Villa.Family, Integer> count = new java.util.EnumMap<>(Villa.Family.class);
        for (Lot lot : LOTS) {
            List<Villa.Family> options = new ArrayList<>(Villa.familiesFor(lot.tier()));
            if (lot.corner() && !lot.tier().equals("SMALL")) options.add(0, Villa.Family.CORNER);
            if (lot.view() || slope(lot) >= 5) options.add(0, Villa.Family.SPLIT_LEVEL);
            Villa.Family pick = null;
            int best = Integer.MAX_VALUE;
            for (Villa.Family fam : options) {
                int c = count.getOrDefault(fam, 0) * 3 + (fam == last ? 10 : 0) + R.nextInt(3);
                int[] sz = Villa.size(fam, R);
                if (sz[0] + 8 > lot.w() || sz[1] + 10 > lot.d()) c += 20;
                if (c < best) {
                    best = c;
                    pick = fam;
                }
            }
            count.merge(pick, 1, Integer::sum);
            last = pick;
            built.add(Garden.build(lot, pick));
        }
        LOTS.clear();
        LOTS.addAll(built);
    }

    static int slope(Lot lot) {
        int min = 999, max = -999;
        for (int x = 0; x < lot.w(); x += 2)
            for (int z = 0; z < lot.d(); z += 2) {
                int g = Terrain.h(lot.frame().wx(x, z), lot.frame().wz(x, z));
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
        return max - min;
    }

    // ------------------------------------------------------------------ park and street furniture

    static void markPark() {
        for (int u = -64; u <= -16; u++)
            for (int v = -32; v <= -2; v++) {
                double d = Math.hypot((u + 40) / 26.0, (v + 17) / 15.0);
                if (d < 1 && !road(u, v) && !lake(u, v)) PARK[u - Canvas.MINX][v - Canvas.MINZ] = true;
            }
    }

    static void park() {
        int cu = -40, cv = -17;
        int g = Terrain.h(cu, cv);
        // pond with an island and a little bridge
        for (int du = -9; du <= 9; du++)
            for (int dv = -6; dv <= 6; dv++) {
                double d = Math.hypot(du / 9.0, dv / 6.0) + 0.1 * Geology.noise(du, dv, 3, 921);
                int u = cu - 8 + du, v = cv + dv;
                if (d > 1) continue;
                int gg = Terrain.h(u, v);
                if (d > 0.85) Canvas.set(u, gg, v, "mossy_cobblestone");
                else {
                    Canvas.set(u, gg - 2, v, "gravel");
                    Canvas.set(u, gg - 1, v, "water");
                    Canvas.set(u, gg, v, "water");
                    Canvas.fill(u, gg + 1, v, u, gg + 3, v, "air");
                    if (R.nextDouble() < 0.06) Canvas.set(u, gg + 1, v, "lily_pad");
                }
                Canvas.use(u, v, u, v);
            }
        // paths
        for (int u = -64; u <= -16; u++)
            for (int v = -32; v <= -2; v++) {
                if (!PARK[u - Canvas.MINX][v - Canvas.MINZ]) continue;
                boolean path = Math.abs((v + 17) - (u + 40) * 0.2) < 1.0 || Math.abs(u + 22) < 1.0 || Math.abs(Math.hypot(u + 40, (v + 17) * 1.6) - 20) < 0.9;
                if (path && !Canvas.get(u, Terrain.h(u, v), v).equals("water")) {
                    Canvas.set(u, Terrain.h(u, v), v, Geology.noise(u, v, 2, 922) < 0.7 ? "gravel" : "dirt_path");
                    Canvas.use(u, v, u, v);
                }
            }
        // pavilion
        int pu = -26, pv = -24, pg = Terrain.h(pu, pv);
        for (int du = -3; du <= 3; du++) for (int dv = -3; dv <= 3; dv++) Canvas.set(pu + du, pg, pv + dv, "smooth_stone");
        for (int[] c : new int[][] {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) Canvas.fill(pu + c[0], pg + 1, pv + c[1], pu + c[0], pg + 3, pv + c[1], "pale_oak_fence");
        B.Frame pf = new B.Frame(pu, pg, pv, 0);
        Villa.R = R;
        Villa.hip(pf, -4, -4, 4, 4, 4, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
        pf.set(0, 3, 0, "lantern[hanging=true]");
        Canvas.use(pu - 4, pv - 4, pu + 4, pv + 4);
        // playground: swings and a sandbox
        int su = -54, sv = -10, sg = Terrain.h(su, sv);
        for (int du = 0; du <= 6; du++) for (int dv = 0; dv <= 4; dv++) Canvas.set(su + du, sg, sv + dv, "sand");
        Canvas.fill(su, sg + 1, sv + 2, su, sg + 4, sv + 2, "spruce_fence");
        Canvas.fill(su + 6, sg + 1, sv + 2, su + 6, sg + 4, sv + 2, "spruce_fence");
        Canvas.fill(su, sg + 5, sv + 2, su + 6, sg + 5, sv + 2, "stripped_spruce_log[axis=x]");
        for (int x : new int[] {2, 4}) {
            Canvas.fill(su + x, sg + 3, sv + 2, su + x, sg + 4, sv + 2, "iron_chain[axis=y]");
            Canvas.set(su + x, sg + 2, sv + 2, "spruce_slab[type=bottom]");
        }
        Canvas.use(su, sv, su + 6, sv + 4);
        // benches and lamps along the path ring
        for (int a = 0; a < 360; a += 40) {
            int u = (int) Math.round(-40 + Math.cos(Math.toRadians(a)) * 20), v = (int) Math.round(-17 + Math.sin(Math.toRadians(a)) * 12.5);
            if (!PARK[u - Canvas.MINX][v - Canvas.MINZ]) continue;
            int gg = Terrain.h(u, v);
            Canvas.setIfAir(u, gg + 1, v, a % 80 == 0 ? "spruce_stairs[facing=north,half=bottom]" : "air");
            if (a % 80 == 40) Build.lamp(u, v);
        }
    }

    /** Street lights on alternate sides, avenue trees on the main streets, name signs at the corners. */
    static void furniture() {
        int k = 0;
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            for (int i = 8; i < pts.size() - 4; i += 22) {
                double[] a = pts.get(i - 1), b = pts.get(i + 1), p = pts.get(i);
                double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                int side = (k++ & 1) == 0 ? 1 : -1;
                int u = (int) Math.round(p[0] - tz / len * 3.8 * side), v = (int) Math.round(p[1] + tx / len * 3.8 * side);
                if (!Canvas.inXZ(u, v) || ROAD[u - Canvas.MINX][v - Canvas.MINZ] != 2) continue;
                int g = Terrain.h(u, v) + 1;
                Canvas.set(u, g, v, "stone_brick_wall");
                Canvas.fill(u, g + 1, v, u, g + 4, v, "iron_bars");
                B.Dir arm = Math.abs(tz) > Math.abs(tx) ? (side * -tz > 0 ? B.Dir.EAST : B.Dir.WEST) : (side * tx > 0 ? B.Dir.NORTH : B.Dir.SOUTH);
                arm = arm.opposite();
                Canvas.set(u + arm.dx, g + 5, v + arm.dz, "iron_bars");
                Canvas.set(u, g + 5, v, "iron_bars");
                Canvas.set(u + arm.dx, g + 4, v + arm.dz, "lantern[hanging=true]");
            }
            // avenue trees on the main streets, in the pavement verge
            if (s.main())
                for (int i = 18; i < pts.size() - 4; i += 22) {
                    double[] a = pts.get(i - 1), b = pts.get(i + 1), p = pts.get(i);
                    double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                    for (int side : new int[] {-1, 1}) {
                        int u = (int) Math.round(p[0] - tz / len * 6.2 * side), v = (int) Math.round(p[1] + tx / len * 6.2 * side);
                        if (!Canvas.inXZ(u, v) || Canvas.used(u, v) || road(u, v) || lake(u, v)) continue;
                        int g = Terrain.h(u, v);
                        Canvas.set(u, g, v, "grass_block");
                        if (R.nextBoolean()) Nature.birch(u, v, g, 7 + R.nextInt(3));
                        else Nature.oak(u, v, g, 6 + R.nextInt(2));
                        Canvas.use(u, v, u, v);
                    }
                }
            double[] p0 = s.pts().get(0), p1 = s.pts().get(1);
            int su = (int) Math.round(p0[0] + (p1[0] - p0[0]) * 0.12), sv = (int) Math.round(p0[1] + (p1[1] - p0[1]) * 0.12);
            Town.label(su + 0.5, Terrain.h(su, sv) + 4.5, sv + 0.5, s.name(), "", "#FFFFFF", 0.9f);
        }
    }

    // ------------------------------------------------------------------ registry

    static void registry(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"district\": \"SydHamn\",\n  \"origin\": [" + Box.worldX + ", 64, " + Box.worldZ + "],\n  \"lots\": [\n");
        for (int n = 0; n < LOTS.size(); n++) {
            Lot l = LOTS.get(n);
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] c : new int[][] {{0, 0}, {l.w() - 1, 0}, {0, l.d() - 1}, {l.w() - 1, l.d() - 1}}) {
                int u = l.frame().wx(c[0], c[1]), v = l.frame().wz(c[0], c[1]);
                x0 = Math.min(x0, u);
                x1 = Math.max(x1, u);
                z0 = Math.min(z0, v);
                z1 = Math.max(z1, v);
            }
            sb.append(String.format(Locale.ROOT,
                    "    {\"id\": %d, \"address\": \"%s %d\", \"tier\": \"%s\", \"style\": \"%s\", \"floors\": %d, \"bedrooms\": %d, "
                    + "\"lotArea\": %d, \"from\": [%d, %d], \"to\": [%d, %d], \"waterfront\": %b, \"view\": %b, \"corner\": %b, "
                    + "\"nearPark\": %b, \"locationScore\": %.2f, \"suggestedPriceSek\": %d}%s%n",
                    l.id(), l.street(), l.number(), l.tier(), l.info().style, l.info().floors, l.info().bedrooms, l.w() * l.d(),
                    Box.worldX + x0, Box.worldZ + z0, Box.worldX + x1, Box.worldZ + z1, l.water(), l.view(), l.corner(), l.park(),
                    l.score(), l.price(), n + 1 < LOTS.size() ? "," : ""));
        }
        sb.append("  ]\n}\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        System.out.println("registry: " + LOTS.size() + " lots -> " + file);
    }

    // ------------------------------------------------------------------ generate

    static void generate() {
        terrain();
        streets();
        markPark();
        paint();
        lots();
        houses();
        park();
        furniture();
        Forest.grow((u, v) -> PARK[u - Canvas.MINX][v - Canvas.MINZ] ? 0.02 : Terrain.WEIGHT[u - Canvas.MINX][v - Canvas.MINZ] < 0.9 ? 0.05 : 0.0012,
                200, (u, v) -> road(u, v) || Canvas.used(u, v));
    }
}
