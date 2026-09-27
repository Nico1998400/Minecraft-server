import java.util.ArrayList;
import java.util.List;

/**
 * Rivers that follow the terrain: a smooth meandering path that narrows and widens, water held in flat reaches that
 * drop by falls of at least two blocks (plunge pools below), banks raised wherever the water could otherwise escape.
 * Works on {@link Terrain#H} and writes {@link #LEVEL}; water blocks are placed by {@link #fill()}.
 */
final class River {

    static final int NONE = Integer.MIN_VALUE;
    /** Water surface (top water block y) of every channel cell, NONE elsewhere. */
    static final int[][] LEVEL = new int[Canvas.SX][Canvas.SZ];
    /** Height of the ground before the river was cut (bridges keep the street level). */
    static final int[][] BEFORE = new int[Canvas.SX][Canvas.SZ];

    static {
        for (int[] row : LEVEL) java.util.Arrays.fill(row, NONE);
    }

    record Sample(double u, double v, double width, int level, boolean pool) {}

    /** Catmull-Rom through the control points, sampled every half block, plus a gentle meander. */
    static List<double[]> spline(List<double[]> pts, double meander, int seed) {
        List<double[]> out = new ArrayList<>();
        double travelled = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] p0 = pts.get(Math.max(0, i - 1)), p1 = pts.get(i), p2 = pts.get(i + 1), p3 = pts.get(Math.min(pts.size() - 1, i + 2));
            double seg = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            int n = Math.max(2, (int) Math.ceil(seg * 2));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n, t2 = t * t, t3 = t2 * t;
                double x = 0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
                double z = 0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
                out.add(new double[] {x, z, travelled});
                travelled += seg / n;
            }
        }
        double[] last = pts.get(pts.size() - 1);
        out.add(new double[] {last[0], last[1], travelled});
        // meander: offset sideways along the normal
        List<double[]> m = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            double[] a = out.get(Math.max(0, i - 1)), b = out.get(Math.min(out.size() - 1, i + 1)), c = out.get(i);
            double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(tx, tz));
            double nx = -tz / len, nz = tx / len;
            double off = meander * Math.sin(c[2] / 13.0 + seed) * Math.min(1, Math.min(c[2], travelled - c[2]) / 10.0);
            m.add(new double[] {c[0] + nx * off, c[1] + nz * off, c[2]});
        }
        return m;
    }

    /**
     * Traces a stream from a source down the terrain: steepest descent with some momentum (so it swings through bends
     * instead of zig-zagging) and a gentle pull towards the lake it drains into. Returns control points for carve().
     */
    static List<double[]> descend(double su, double sv, double tu, double tv, double stopRadius, int maxSteps) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[] {su, sv});
        int u = (int) Math.round(su), v = (int) Math.round(sv);
        double pdu = 0, pdv = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int step = 0; step < maxSteps; step++) {
            if (Math.hypot(u - tu, v - tv) < stopRadius) break;
            seen.add(((long) u << 32) ^ (v & 0xffffffffL));
            double best = Double.MAX_VALUE;
            int bu = u, bv = v;
            for (int a = -1; a <= 1; a++)
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    int nu = u + a, nv = v + b;
                    if (!Canvas.inXZ(nu, nv) || seen.contains(((long) nu << 32) ^ (nv & 0xffffffffL))) continue;
                    double len = Math.hypot(a, b);
                    double dh = (Terrain.h(nu, nv) - Terrain.h(u, v)) / len;
                    double turn = 1 - (a * pdu + b * pdv) / len;
                    double pull = (Math.hypot(nu - tu, nv - tv) - Math.hypot(u - tu, v - tv)) / len;
                    double score = dh + 0.35 * turn + 0.18 * pull + 0.1 * Terrain.noise(nu, nv, 3, 611);
                    if (score < best) {
                        best = score;
                        bu = nu;
                        bv = nv;
                    }
                }
            if (bu == u && bv == v) break;
            double len = Math.hypot(bu - u, bv - v);
            pdu = 0.6 * pdu + 0.4 * (bu - u) / len;
            pdv = 0.6 * pdv + 0.4 * (bv - v) / len;
            u = bu;
            v = bv;
            if (step % 5 == 4) pts.add(new double[] {u, v});
        }
        pts.add(new double[] {tu, tv});
        return pts;
    }

    static int h(double u, double v) {
        return Terrain.h((int) Math.round(u), (int) Math.round(v));
    }

    /**
     * Cuts a river along the given control points. {@code endLevel} forces the last reach (sea level for the mouth, the
     * main river level for a tributary); NONE lets it follow the ground.
     */
    static void carve(List<double[]> ctrl, double minWidth, double maxWidth, int seed, int endLevel) {
        List<double[]> path = spline(ctrl, 2.2, seed);
        int n = path.size();
        int[] want = new int[n];
        for (int s = 0; s < n; s++) {
            double[] p = path.get(s);
            int g = Integer.MAX_VALUE;
            for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) g = Math.min(g, h(p[0] + du, p[1] + dv));
            want[s] = g - 1;
        }
        // flat reaches, dropping only by falls of two blocks or more
        int[] level = new int[n];
        boolean[] pool = new boolean[n];
        int cur = want[0];
        int since = 99;
        for (int s = 0; s < n; s++) {
            int target = Math.min(cur, want[s]);
            if (endLevel != NONE && n - s < 18) target = Math.min(target, endLevel);
            if (target <= cur - 2 || (endLevel != NONE && n - s < 18 && target < cur)) {
                cur = target;
                since = 0;
            }
            level[s] = cur;
            pool[s] = since < 8;
            since++;
        }
        if (endLevel != NONE) for (int s = n - 12; s < n; s++) if (s >= 0) level[s] = endLevel;
        List<Sample> samples = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            double[] p = path.get(s);
            double wdt = minWidth + (maxWidth - minWidth) * Terrain.noise(p[2], seed, 17, 601);
            if (pool[s]) wdt += 2;
            samples.add(new Sample(p[0], p[1], wdt, level[s], pool[s]));
        }
        apply(samples);
    }

    static void apply(List<Sample> samples) {
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        for (Sample s : samples) {
            minU = Math.min(minU, (int) s.u() - 6);
            maxU = Math.max(maxU, (int) s.u() + 6);
            minV = Math.min(minV, (int) s.v() - 6);
            maxV = Math.max(maxV, (int) s.v() + 6);
        }
        // nearest sample for every cell near the path
        for (int u = minU; u <= maxU; u++)
            for (int v = minV; v <= maxV; v++) {
                if (!Canvas.inXZ(u, v)) continue;
                Sample best = null;
                double bd = Double.MAX_VALUE;
                for (Sample s : samples) {
                    double d = Math.hypot(u - s.u(), v - s.v());
                    if (d < bd) {
                        bd = d;
                        best = s;
                    }
                }
                if (best == null || bd > best.width() / 2 + 0.3) continue;
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (Terrain.inBasin(u, v)) continue;
                int lvl = best.level();
                if (LEVEL[i][j] != NONE) lvl = Math.min(lvl, LEVEL[i][j]);
                if (BEFORE[i][j] == 0 && LEVEL[i][j] == NONE) BEFORE[i][j] = Terrain.H[i][j];
                LEVEL[i][j] = lvl;
                int depth = best.pool() ? 3 : 2;
                Terrain.H[i][j] = Math.min(Terrain.H[i][j], lvl - depth);
                Terrain.RIVER[i][j] = true;
            }
        // banks: every dry neighbour must stand at least one block above the water beside it
        for (int u = minU; u <= maxU; u++)
            for (int v = minV; v <= maxV; v++) {
                if (!Canvas.inXZ(u, v)) continue;
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (LEVEL[i][j] != NONE || Terrain.inBasin(u, v)) continue;
                int need = NONE;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) {
                        int nu = u + a, nv = v + b;
                        if (!Canvas.inXZ(nu, nv)) continue;
                        int l = LEVEL[nu - Canvas.MINX][nv - Canvas.MINZ];
                        if (l != NONE) need = Math.max(need, l + 1);
                    }
                if (need != NONE && Terrain.H[i][j] < need) Terrain.H[i][j] = need;
            }
    }

    /** A round pool (a spring or a tarn) at the given water level. */
    static void pond(double cu, double cv, double r, int level, int depth) {
        List<Sample> s = new ArrayList<>();
        for (double a = 0; a < 360; a += 12) s.add(new Sample(cu + Math.cos(Math.toRadians(a)) * r * 0.45, cv + Math.sin(Math.toRadians(a)) * r * 0.45, r * 1.1, level, depth > 2));
        s.add(new Sample(cu, cv, r * 2, level, depth > 2));
        apply(s);
    }

    static int levelAt(int u, int v) {
        return channel(u, v) ? LEVEL[u - Canvas.MINX][v - Canvas.MINZ] : NONE;
    }

    static boolean channel(int u, int v) {
        return Canvas.inXZ(u, v) && LEVEL[u - Canvas.MINX][v - Canvas.MINZ] != NONE;
    }

    /** Water, beds, banks with reeds and stones, lily pads on the quiet reaches. */
    static void fill() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int l = LEVEL[i][j];
                if (l == NONE) continue;
                int bed = Terrain.H[i][j];
                Canvas.set(u, bed, v, Canvas.TERRAIN_RNG.nextDouble() < 0.5 ? "gravel" : Canvas.TERRAIN_RNG.nextDouble() < 0.5 ? "mossy_cobblestone" : "sand");
                Canvas.fill(u, bed + 1, v, u, l, v, "water");
                Canvas.fill(u, l + 1, v, u, Math.min(Canvas.MAXY, l + 30), v, "air");
                if (l - bed >= 2 && Canvas.TERRAIN_RNG.nextDouble() < 0.07) Canvas.set(u, bed + 1, v, "seagrass");
                Canvas.setGround(u, v, bed);
                boolean still = true;
                for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) if (channel(u + a, v + b) && LEVEL[i + a][j + b] != l) still = false;
                if (still && Canvas.TERRAIN_RNG.nextDouble() < 0.05) Canvas.set(u, l + 1, v, "lily_pad");
            }
        // banks
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                if (channel(u, v) || Terrain.inBasin(u, v)) continue;
                boolean bank = false;
                for (B.Dir d : B.Dir.values()) if (channel(u + d.dx, v + d.dz)) bank = true;
                if (!bank) continue;
                int g = Terrain.h(u, v);
                if (Canvas.street(u, v)) continue;
                for (int y = g - 3; y < g; y++) if (!Canvas.isSolid(u, y, v)) Canvas.set(u, y, v, "mossy_cobblestone");
                double r = Canvas.TERRAIN_RNG.nextDouble();
                if (r < 0.35) Canvas.set(u, g, v, "mossy_cobblestone");
                else if (r < 0.5) Canvas.set(u, g, v, "gravel");
                else if (r < 0.7) Canvas.set(u, g, v, "grass_block");
                if (Canvas.get(u, g, v).equals("grass_block") && Canvas.isAir(u, g + 1, v)) {
                    double p = Canvas.TERRAIN_RNG.nextDouble();
                    if (p < 0.25 && Canvas.isAir(u, g + 2, v)) {
                        Canvas.set(u, g + 1, v, "tall_grass[half=lower]");
                        Canvas.set(u, g + 2, v, "tall_grass[half=upper]");
                    } else if (p < 0.5) Canvas.set(u, g + 1, v, "fern");
                }
            }
    }
}
