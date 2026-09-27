import java.util.ArrayList;
import java.util.List;

/**
 * "Nordfjället": the mountain range south of Nordhamn, built as its own region. A main ridge with a snow-capped peak,
 * secondary peaks and a pass; spurs reaching north with forested valleys between; a cirque with a tarn under the main
 * peak; a dark cliff wall with a hanging valley and waterfall; a valley lake; a mountain path with a bridge over a
 * gorge, a viewpoint with a beacon and a summer farm on the high meadow.
 */
final class Massif {

    record Ridge(double[] a, double[] b, double ha, double hb) {}

    static final List<Ridge> RIDGES = new ArrayList<>();

    static void ridge(double u0, double v0, double h0, double u1, double v1, double h1) {
        RIDGES.add(new Ridge(new double[] {u0, v0}, new double[] {u1, v1}, h0, h1));
    }

    static {
        // main ridge, west to east: P3 (-130) – main peak P1 (-28) – pass – P2 (72) – P4 (170)
        ridge(-200, 232, 60, -130, 214, 112);
        ridge(-130, 214, 112, -78, 226, 88);
        ridge(-78, 226, 88, -28, 240, 152);
        ridge(-28, 240, 152, 22, 230, 84);
        ridge(22, 230, 84, 72, 214, 128);
        ridge(72, 214, 128, 122, 230, 90);
        ridge(122, 230, 90, 170, 244, 104);
        ridge(170, 244, 104, 205, 238, 60);
        // spurs reaching north towards the town
        ridge(-28, 240, 146, -42, 196, 88);
        ridge(-42, 196, 88, -58, 164, 36);
        ridge(72, 214, 122, 62, 186, 70);
        ridge(62, 186, 70, 48, 160, 30);
        ridge(-130, 214, 108, -150, 180, 56);
        ridge(-150, 180, 56, -160, 150, 26);
        ridge(170, 244, 100, 152, 200, 52);
        ridge(152, 200, 52, 140, 164, 24);
        // southern spurs (seen as layered silhouettes from the peaks)
        ridge(-28, 240, 140, -10, 320, 70);
        ridge(72, 214, 120, 96, 300, 66);
        ridge(-130, 214, 104, -120, 310, 56);
        // a knoll that the path crosses to reach the gorge bridge
        ridge(-6, 150, 26, 14, 176, 34);
    }

    static final double[] TARN = {-8, 204};
    /** Cirques: centre u, v, radius, floor height. */
    static final double[][] CIRQUES = {{-56, 212, 27, 94}, {-24, 276, 26, 98}, {6, 256, 20, 96}, {102, 196, 22, 80},
            {80, 244, 24, 80}, {-120, 184, 24, 70}, {-142, 244, 22, 72}, {158, 212, 22, 64}, {196, 268, 20, 60}};
    static final double[] LAKE = {12, 150};
    static final double[] CLIFF = {70, 196};
    static final int SNOW = 124;

    static double distSeg(double px, double pz, double[] a, double[] b, double[] tOut) {
        double dx = b[0] - a[0], dz = b[1] - a[1];
        double t = Math.max(0, Math.min(1, ((px - a[0]) * dx + (pz - a[1]) * dz) / (dx * dx + dz * dz)));
        tOut[0] = t;
        return Math.hypot(px - (a[0] + t * dx), pz - (a[1] + t * dz));
    }

    /** The primary form: the highest ridge profile over the point (after a domain warp for organic lines). */
    static double primary(int u, int v) {
        double wu = u + 30 * (Geology.fbm(u, v, 80, 3, 701) - 0.5) + 8 * (Geology.noise(u, v, 19, 702) - 0.5);
        double wv = v + 30 * (Geology.fbm(u, v, 80, 3, 703) - 0.5) + 8 * (Geology.noise(u, v, 19, 704) - 0.5);
        double best = 0;
        double[] t = new double[1];
        for (Ridge r : RIDGES) {
            double d = distSeg(wu, wv, r.a(), r.b(), t);
            double crest = r.ha() + (r.hb() - r.ha()) * t[0];
            double half = crest * 1.05 + 10;
            if (d >= half) continue;
            double x = d / half;
            double profile = Math.pow(1 - x, 2.1);
            best = Math.max(best, crest * profile);
        }
        // secondary relief: sharper on high ground
        double hi = Terrain.smooth(best / 70.0);
        double detail = 30 * hi * hi * (Geology.ridged(u, v, 30, 5, 705) - 0.3) + 5 * (Geology.fbm(u, v, 24, 3, 706) - 0.5);
        double foothills = 7 * Geology.fbm(u, v, 30, 3, 707) + 3 * Geology.ridged(u, v, 19, 3, 708);
        return Math.max(best + detail, foothills + 6);
    }

    static double[][] design() {
        double[][] d = new double[Canvas.SX][Canvas.SZ];
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                double h = primary(u, v);
                // the cirque: a bowl scooped out under the main peak's north face
                double dc = Math.hypot((u - TARN[0]) / 1.2, v - TARN[1]);
                if (dc < 26) h = Math.min(h, 86 + Math.pow(dc / 26, 2.2) * 50);
                // glacial cirques around the peaks: concave bowls that leave sharp aretes between them
                for (double[] c : CIRQUES) {
                    double dd = Math.max(0, Math.hypot((u - c[0]) / 1.15, v - c[1]) + 5 * (Geology.noise(u, v, 9, 709) - 0.5));
                    if (dd < c[2]) h = Math.min(h, c[3] + Math.pow(dd / c[2], 2.3) * (h - c[3] + 40));
                }
                // the valley lake basin
                double dl = Math.hypot((u - LAKE[0]) / 1.8, v - LAKE[1]);
                if (dl < 16) h = Math.min(h, 4 + Math.pow(dl / 16, 1.6) * 14);
                // the dark wall: steepen the north face of P2 into a near-vertical cliff with a hanging valley above
                double dw = Math.hypot(u - CLIFF[0], (v - CLIFF[1]) * 1.4);
                if (dw < 34 && h > 26) {
                    double k = Terrain.smooth((34 - dw) / 12);
                    double stepped = h > 62 ? Math.max(h, 74) : Math.min(h, 32);
                    h = h + (stepped - h) * k;
                }
                d[u - Canvas.MINX][v - Canvas.MINZ] = h;
            }
        return d;
    }

    static Geology.Rock rock(int u, int v) {
        return Math.hypot(u - CLIFF[0], (v - CLIFF[1]) * 1.4) < 40 ? Geology.Rock.DARK : Geology.Rock.GRANITE;
    }

    // ------------------------------------------------------------------ generate

    static final List<double[]> PATH = List.of(new double[] {0, 128}, new double[] {3, 138}, new double[] {-6, 148},
            new double[] {-12, 160}, new double[] {-8, 172}, new double[] {-2, 184}, new double[] {-16, 190},
            new double[] {-26, 196}, new double[] {-30, 206}, new double[] {-40, 212});
    static final boolean[][] ON_PATH = new boolean[Canvas.SX][Canvas.SZ];
    static final int[][] PATH_Y = new int[Canvas.SX][Canvas.SZ];

    static void generate() {
        double[][] design = design();
        int[][] H = Terrain.H;
        double[][] strength = new double[Canvas.SX][Canvas.SZ];
        double[][] h = new double[Canvas.SX][Canvas.SZ];
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int edge = Math.min(Math.min(u - Canvas.MINX, Canvas.MAXX - u), Math.min(v - Canvas.MINZ, Canvas.MAXZ - v));
                double w = Terrain.smooth(edge / 34.0);
                h[i][j] = w * design[i][j] + (1 - w) * Terrain.naturalHeight(u, v);
                Terrain.WEIGHT[i][j] = w;
                strength[i][j] = Terrain.smooth((w - 0.2) / 0.5);
            }
        Geology.ROCK_LINE = 84;
        Geology.EROSION = 1.25;
        double before = max(h);
        Geology.hydraulic(h, strength, 240_000, 911);
        System.out.printf("massif: highest %.1f before erosion, %.1f after%n", before, max(h));
        Geology.thermal(h, strength, 16, 1.7);
        Geology.strata(h, strength, 5, 0.04, -0.03, 2.4, 0.45);
        for (int i = 0; i < Canvas.SX; i++) for (int j = 0; j < Canvas.SZ; j++) H[i][j] = (int) Math.round(h[i][j]);
        path();
        waters();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (Terrain.RIVER[i][j]) continue;
                Geology.paintColumn(u, v, H[i][j], Geology.Relief.of(H, i, j), rock(u, v), SNOW, Terrain.nearWater(u, v, 2));
                if (ON_PATH[i][j]) pathSurface(u, v);
            }
        boolean[][] wild = new boolean[Canvas.SX][Canvas.SZ];
        for (int i = 0; i < Canvas.SX; i++) for (int j = 0; j < Canvas.SZ; j++) wild[i][j] = !Terrain.RIVER[i][j] && !ON_PATH[i][j];
        Geology.cliffDetail(H, wild);
        caves();
        River.fill();
        features();
    }

    static double max(double[][] h) {
        double m = -999;
        for (double[] row : h) for (double x : row) m = Math.max(m, x);
        return m;
    }

    // ------------------------------------------------------------------ water

    static void waters() {
        int lake = Terrain.h((int) LAKE[0], (int) LAKE[1]) + 3;
        River.pond(LAKE[0], LAKE[1], 13, lake, 4);
        River.pond(LAKE[0] + 10, LAKE[1] + 3, 9, lake, 3);
        int tarn = Terrain.h((int) TARN[0], (int) TARN[1]) + 2;
        River.pond(TARN[0], TARN[1], 8, tarn, 3);
        // streams find their own way down: the tarn outflow, the hanging valley over the dark wall, two western brooks
        River.carve(River.descend(TARN[0], TARN[1] - 7, LAKE[0] - 4, LAKE[1] + 4, 9, 400), 3, 5, 21, lake);
        int hang = Terrain.h(74, 214) + 1;
        River.pond(74, 214, 6, hang, 2);
        River.carve(River.descend(74, 207, LAKE[0] + 16, LAKE[1] + 4, 9, 400), 3, 4, 33, lake);
        River.carve(River.descend(-96, 204, LAKE[0] - 14, LAKE[1], 9, 400), 2, 4, 41, lake);
        River.carve(River.descend(-56, 205, LAKE[0] - 14, LAKE[1] - 2, 9, 400), 2, 3, 43, lake);
    }

    // ------------------------------------------------------------------ the mountain path

    /** A path that climbs no more than one block per step, cut into the slope where needed. */
    static void path() {
        List<double[]> pts = River.spline(PATH, 0.6, 5);
        int last = Integer.MIN_VALUE;
        for (double[] p : pts) {
            int u = (int) Math.round(p[0]), v = (int) Math.round(p[1]);
            if (!Canvas.inXZ(u, v)) continue;
            int g = Terrain.h(u, v);
            int y = last == Integer.MIN_VALUE ? g : Math.max(last - 1, Math.min(last + 1, g));
            last = y;
            for (int du = -1; du <= 1; du++)
                for (int dv = -1; dv <= 1; dv++) {
                    int uu = u + du, vv = v + dv;
                    if (!Canvas.inXZ(uu, vv)) continue;
                    int i = uu - Canvas.MINX, j = vv - Canvas.MINZ;
                    if (Math.abs(du) + Math.abs(dv) > 1 && ON_PATH[i][j]) continue;
                    Terrain.H[i][j] = y;
                    PATH_Y[i][j] = y;
                    ON_PATH[i][j] = true;
                }
        }
    }

    static void pathSurface(int u, int v) {
        int g = Terrain.h(u, v);
        double r = Geology.noise(u, v, 3, 721);
        Canvas.set(u, g, v, r < 0.45 ? "dirt_path" : r < 0.7 ? "coarse_dirt" : r < 0.85 ? "gravel" : "cobblestone");
        Canvas.fill(u, g + 1, v, u, g + 4, v, "air");
    }

    // ------------------------------------------------------------------ features

    static void caves() {
        int[][] at = {{64, 200, 0, 1}, {-60, 190, 1, 0}, {-120, 200, 0, 1}};
        for (int[] c : at) {
            int u = c[0], v = c[1];
            for (int k = 0; k < 25 && Canvas.inXZ(u, v); k++) {
                if (Terrain.h(u + c[2], v + c[3]) - Terrain.h(u, v) >= 7) break;
                u += c[2];
                v += c[3];
            }
            if (Terrain.h(u + c[2], v + c[3]) - Terrain.h(u, v) < 7) continue;
            Geology.cave(u, Terrain.h(u, v) + 3, v + c[3], c[2], c[3], 13, 3, 2.6);
        }
    }

    /** Trees per cell: thick in the valleys, thinning on steep ground and towards the treeline. */
    static double density(int u, int v) {
        int g = Canvas.ground(u, v);
        if (g > 62) return 0.004;
        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
        if (i < 1 || j < 1 || i >= Canvas.SX - 1 || j >= Canvas.SZ - 1) return 0;
        Geology.Relief r = Geology.Relief.of(Terrain.H, i, j);
        double steep = r.drop() >= 3 ? 0.2 : r.drop() == 2 ? 0.6 : 1;
        double valley = 1 - Terrain.smooth((g - 20) / 40.0) * 0.6;
        return 0.055 * steep * valley;
    }

    static boolean blocked(int u, int v) {
        if (!Canvas.inXZ(u, v)) return true;
        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
        if (ON_PATH[i][j] || Terrain.RIVER[i][j]) return true;
        for (B.Dir d : B.Dir.values()) {
            int ni = i + d.dx, nj = j + d.dz;
            if (ni >= 0 && nj >= 0 && ni < Canvas.SX && nj < Canvas.SZ && (ON_PATH[ni][nj] || Terrain.RIVER[ni][nj])) return true;
        }
        return false;
    }

    static void features() {
        bridge();
        viewpoint(-40, 212);
        shieling(22, 226);
        beacon(-28, 240);
    }

    /** Timber bridges wherever the path crosses a stream: deck at path level, posts to the bed, railings, lanterns. */
    static void bridge() {
        int n = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!ON_PATH[i][j] || !Terrain.RIVER[i][j]) continue;
                int deck = Math.max(PATH_Y[i][j], River.LEVEL[i][j] + 2);
                Canvas.set(u, deck, v, ((u + v) & 1) == 0 ? "spruce_planks" : "stripped_spruce_wood");
                Canvas.fill(u, deck + 1, v, u, deck + 4, v, "air");
                Canvas.setGround(u, v, deck);
                if (Math.floorMod(u * 3 + v, 4) == 0) Canvas.fill(u, Terrain.H[i][j] + 1, v, u, deck - 1, v, "spruce_log[axis=y]");
                for (B.Dir d : B.Dir.values()) {
                    int nu = u + d.dx, nv = v + d.dz;
                    if (!Canvas.inXZ(nu, nv) || ON_PATH[nu - Canvas.MINX][nv - Canvas.MINZ]) continue;
                    if (Canvas.isSolid(nu, deck, nv)) continue;
                    Canvas.set(nu, deck, nv, "spruce_planks");
                    Canvas.set(nu, deck + 1, nv, "spruce_fence");
                    if (Math.floorMod(nu + nv, 4) == 0) {
                        Canvas.set(nu, deck + 2, nv, "spruce_fence");
                        Canvas.set(nu, deck + 3, nv, "lantern");
                        Canvas.fill(nu, Canvas.ground(nu, nv) + 1, nv, nu, deck - 1, nv, "spruce_log[axis=y]");
                    }
                }
                n++;
            }
        System.out.println("bridge cells: " + n);
    }

    /** A stone cairn with a beacon basket and a bench, where the path ends on the spur. */
    static void viewpoint(int u, int v) {
        int g = Canvas.ground(u, v);
        for (int du = -2; du <= 2; du++) for (int dv = -2; dv <= 2; dv++) if (Math.abs(du) + Math.abs(dv) <= 3) Canvas.set(u + du, g, v + dv, "cobblestone");
        for (int k = 0; k < 4; k++)
            for (int du = -1 + k / 3; du <= 1 - k / 3; du++)
                for (int dv = -1 + k / 3; dv <= 1 - k / 3; dv++) Canvas.set(u + du + 3, g + 1 + k, v + dv, Canvas.pick("mossy_cobblestone", "cobblestone", "andesite"));
        Canvas.set(u + 3, g + 5, v, "campfire[lit=true,signal_fire=false]");
        Canvas.set(u - 2, g + 1, v + 1, B.stairs("spruce", B.Dir.SOUTH));
        Canvas.set(u - 1, g + 1, v + 1, B.stairs("spruce", B.Dir.SOUTH));
        Canvas.set(u, g + 1, v - 3, "lantern");
    }

    /** Two turf-roofed cabins of a summer farm on the high meadow. */
    static void shieling(int u, int v) {
        for (int[] c : new int[][] {{0, 0}, {10, 4}}) {
            int x = u + c[0], z = v + c[1];
            int y = Canvas.ground(x + 3, z + 2) + 1;
            Build.longhouse(new B.Frame(x, y, z, 0), 9, 6, true);
        }
        for (int k = 0; k < 14; k++) {
            int x = u - 4 + k, z = v + 11;
            Canvas.set(x, Canvas.ground(x, z) + 1, z, "spruce_fence");
        }
    }

    /** A beacon (vårdkase) on the main summit: a tall pyramid of logs ready to be lit. */
    static void beacon(int u, int v) {
        int g = Canvas.ground(u, v);
        for (int k = 0; k < 8; k++) {
            int r = 3 - k * 3 / 8;
            for (int du = -r; du <= r; du++)
                for (int dv = -r; dv <= r; dv++) {
                    if (Math.abs(du) != r && Math.abs(dv) != r && r > 0) continue;
                    Canvas.set(u + du, g + 1 + k, v + dv, "spruce_log[axis=" + (k % 2 == 0 ? "x" : "z") + "]");
                }
        }
        Canvas.set(u, g + 9, v, "campfire[lit=true,signal_fire=true]");
    }
}
