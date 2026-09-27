import java.util.Random;

/**
 * Tools for landscapes that look shaped by geology rather than by a formula: ridged and warped noise for primary forms,
 * droplet hydraulic erosion (gullies, ravines, alluvial fans), thermal erosion (scree), rock strata with ledges, and
 * material painting by slope, height and rock type. Works on a height grid indexed like {@link Canvas} columns.
 */
final class Geology {

    enum Rock {
        GRANITE("stone", "andesite", "stone", "stone", "granite", "stone", "andesite", "tuff"),
        DARK("deepslate", "cobbled_deepslate", "tuff", "deepslate", "calcite", "deepslate", "smooth_basalt", "tuff"),
        COAST("stone", "andesite", "granite", "stone", "tuff", "andesite", "diorite", "stone"),
        HILL("stone", "andesite", "stone", "tuff", "cobblestone", "andesite", "stone", "diorite");

        final String[] bands;

        Rock(String... bands) {
            this.bands = bands;
        }
    }

    static final Random R = Canvas.TERRAIN_RNG;
    /** Above this height steep ground is bare rock (mountains); 999 = no alpine zone. */
    static int ROCK_LINE = 999;
    /** Multiplies how much material a droplet can carry and remove per step. */
    static double EROSION = 1;

    // ------------------------------------------------------------------ noise

    static double noise(double x, double z, double scale, int seed) {
        return Terrain.noise(x, z, scale, seed);
    }

    static double fbm(double x, double z, double scale, int octaves, int seed) {
        double sum = 0, amp = 1, norm = 0, s = scale;
        for (int o = 0; o < octaves; o++) {
            sum += noise(x, z, s, seed + o * 17) * amp;
            norm += amp;
            amp *= 0.5;
            s /= 2.03;
        }
        return sum / norm;
    }

    /** Ridged multifractal: sharp crests, broad valleys; detail concentrates on the ridges. */
    static double ridged(double x, double z, double scale, int octaves, int seed) {
        double sum = 0, amp = 1, norm = 0, weight = 1, s = scale;
        for (int o = 0; o < octaves; o++) {
            double n = 1 - Math.abs(2 * noise(x, z, s, seed + o * 31) - 1);
            n = n * n * weight;
            weight = Math.min(1, n * 1.8);
            sum += n * amp;
            norm += amp;
            amp *= 0.52;
            s /= 2.07;
        }
        return sum / norm;
    }

    /** 3D value noise for overhangs and cave walls. */
    static double noise3(int x, int y, int z, int seed) {
        double a = noise(x + y * 0.61, z - y * 0.37, 5.0, seed), b = noise(x - y * 0.43, z + y * 0.71, 3.1, seed + 9);
        return 0.6 * a + 0.4 * b;
    }

    // ------------------------------------------------------------------ erosion

    static double at(double[][] h, int i, int j) {
        i = Math.max(0, Math.min(h.length - 1, i));
        j = Math.max(0, Math.min(h[0].length - 1, j));
        return h[i][j];
    }

    /** Bilinear height and gradient at a point. */
    static double[] sample(double[][] h, double x, double z) {
        int i = (int) Math.floor(x), j = (int) Math.floor(z);
        double fx = x - i, fz = z - j;
        double a = at(h, i, j), b = at(h, i + 1, j), c = at(h, i, j + 1), d = at(h, i + 1, j + 1);
        double gx = (b - a) * (1 - fz) + (d - c) * fz, gz = (c - a) * (1 - fx) + (d - b) * fx;
        double height = a * (1 - fx) * (1 - fz) + b * fx * (1 - fz) + c * (1 - fx) * fz + d * fx * fz;
        return new double[] {height, gx, gz};
    }

    /**
     * Droplet hydraulic erosion. Water runs downhill, picks up sediment where it speeds up and drops it where it slows:
     * gullies on slopes, ravines where flow concentrates, fans at the foot. {@code strength} (0..1) masks where it may act.
     */
    static void hydraulic(double[][] h, double[][] strength, int drops, long seed) {
        Random r = new Random(seed);
        int w = h.length, d = h[0].length;
        double inertia = 0.06, capacity = 3.2 * EROSION, minCapacity = 0.02, erodeRate = 0.35, depositRate = 0.28, evaporate = 0.018,
                gravity = 6, maxStep = 0.55 * EROSION;
        int radius = 2;
        for (int n = 0; n < drops; n++) {
            double x = 1 + r.nextDouble() * (w - 3), z = 1 + r.nextDouble() * (d - 3);
            if (strength[(int) x][(int) z] <= 0) continue;
            double dx = 0, dz = 0, speed = 1, water = 1, sediment = 0;
            for (int life = 0; life < 60; life++) {
                int i = (int) x, j = (int) z;
                double[] s = sample(h, x, z);
                dx = dx * inertia - s[1] * (1 - inertia);
                dz = dz * inertia - s[2] * (1 - inertia);
                double len = Math.hypot(dx, dz);
                if (len < 1e-6) break;
                dx /= len;
                dz /= len;
                double px = x, pz = z;
                x += dx;
                z += dz;
                if (x < 1 || z < 1 || x >= w - 2 || z >= d - 2) break;
                double dh = sample(h, x, z)[0] - s[0];
                double cap = Math.max(-dh * speed * water * capacity, minCapacity);
                double st = strength[i][j];
                if (sediment > cap || dh > 0) {
                    double amount = dh > 0 ? Math.min(dh, sediment) : (sediment - cap) * depositRate;
                    sediment -= amount;
                    double fx = Math.max(0, Math.min(1, px - i)), fz = Math.max(0, Math.min(1, pz - j));
                    h[i][j] += amount * (1 - fx) * (1 - fz) * st;
                    h[i + 1][j] += amount * fx * (1 - fz) * st;
                    h[i][j + 1] += amount * (1 - fx) * fz * st;
                    h[i + 1][j + 1] += amount * fx * fz * st;
                } else {
                    double amount = Math.min(Math.min((cap - sediment) * erodeRate, -dh), maxStep);
                    double total = 0;
                    for (int a = -radius; a <= radius; a++)
                        for (int b = -radius; b <= radius; b++) {
                            double wgt = Math.max(0, radius + 0.5 - Math.hypot(a, b));
                            total += wgt;
                        }
                    for (int a = -radius; a <= radius; a++)
                        for (int b = -radius; b <= radius; b++) {
                            int ii = i + a, jj = j + b;
                            if (ii < 0 || jj < 0 || ii >= w || jj >= d) continue;
                            double wgt = Math.max(0, radius + 0.5 - Math.hypot(a, b)) / total;
                            double take = amount * wgt * strength[ii][jj];
                            h[ii][jj] -= take;
                            sediment += take;
                        }
                }
                speed = Math.sqrt(Math.max(0, speed * speed + dh * gravity));
                water *= 1 - evaporate;
            }
        }
    }

    /** Thermal erosion: material slides until slopes are no steeper than {@code talus}; forms scree at cliff feet. */
    static void thermal(double[][] h, double[][] strength, int iterations, double talus) {
        int w = h.length, d = h[0].length;
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int it = 0; it < iterations; it++)
            for (int i = 1; i < w - 1; i++)
                for (int j = 1; j < d - 1; j++) {
                    if (strength[i][j] <= 0) continue;
                    int bi = -1, bj = -1;
                    double maxDiff = 0;
                    for (int[] n : nb) {
                        double diff = h[i][j] - h[i + n[0]][j + n[1]];
                        if (diff > maxDiff) {
                            maxDiff = diff;
                            bi = i + n[0];
                            bj = j + n[1];
                        }
                    }
                    if (maxDiff > talus && strength[bi][bj] > 0) {
                        double move = (maxDiff - talus) * 0.25 * strength[i][j];
                        h[i][j] -= move;
                        h[bi][bj] += move;
                    }
                }
    }

    /**
     * Rock strata: on steep ground heights snap towards layer boundaries, leaving ledges and steps. The layers tilt
     * gently so the bands run across the landscape like real bedding.
     */
    static void strata(double[][] h, double[][] strength, int layer, double tiltU, double tiltV, double minSlope, double amount) {
        int w = h.length, d = h[0].length;
        double[][] out = new double[w][d];
        for (int i = 0; i < w; i++)
            for (int j = 0; j < d; j++) {
                double slope = 0;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) slope = Math.max(slope, Math.abs(h[i][j] - at(h, i + a, j + b)));
                double tilt = i * tiltU + j * tiltV;
                double t = (h[i][j] + tilt) / layer;
                double f = t - Math.floor(t);
                double stepped = (Math.floor(t) + Terrain.smooth((f - 0.25) / 0.5)) * layer - tilt;
                double k = Terrain.smooth((slope - minSlope) / 1.6) * strength[i][j] * amount * (0.4 + noise(i, j, 30, 330));
                out[i][j] = h[i][j] + (stepped - h[i][j]) * k;
            }
        for (int i = 0; i < w; i++) System.arraycopy(out[i], 0, h[i], 0, d);
    }

    // ------------------------------------------------------------------ materials

    /** The bedrock at a voxel: continuous tilted bands chosen per rock type, with a little per-block variation. */
    static String stratum(Rock rock, int u, int y, int v) {
        double wobble = 7 * noise(u, v, 31, 301) + 2 * noise(u, v, 9, 302);
        int band = (int) Math.floor((y + 0.28 * u - 0.19 * v + wobble) / 3.4);
        String s = rock.bands[Math.floorMod(band * 7 + (band >> 2), rock.bands.length)];
        double r = R.nextDouble();
        if (r < 0.05) return "cobblestone";
        if (r < 0.08 && y < 20) return "mossy_cobblestone";
        if (r < 0.10) return "gravel".equals(s) ? "andesite" : s.equals("stone") ? "andesite" : "stone";
        return s;
    }

    /** Slope at a cell of the canvas-sized height array: largest drop to any of the 8 neighbours. */
    static int slope(int[][] h, int i, int j) {
        int m = 0;
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++) {
                int ii = Math.max(0, Math.min(h.length - 1, i + a)), jj = Math.max(0, Math.min(h[0].length - 1, j + b));
                m = Math.max(m, Math.abs(h[i][j] - h[ii][jj]));
            }
        return m;
    }

    /** Highest neighbour within r cells, to find the foot of cliffs (scree). */
    static int above(int[][] h, int i, int j, int r) {
        int m = h[i][j];
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++) {
                int ii = i + a, jj = j + b;
                if (ii < 0 || jj < 0 || ii >= h.length || jj >= h[0].length) continue;
                m = Math.max(m, h[ii][jj]);
            }
        return m;
    }

    /** Terrain shape around a column: how far it drops to its lowest neighbour and how far the ground rises above it. */
    record Relief(int drop, int rise, int farRise) {
        static Relief of(int[][] h, int i, int j) {
            int lo = h[i][j], hi = h[i][j];
            for (int a = -1; a <= 1; a++)
                for (int b = -1; b <= 1; b++) {
                    int ii = Math.max(0, Math.min(h.length - 1, i + a)), jj = Math.max(0, Math.min(h[0].length - 1, j + b));
                    lo = Math.min(lo, h[ii][jj]);
                    hi = Math.max(hi, h[ii][jj]);
                }
            return new Relief(h[i][j] - lo, hi - h[i][j], above(h, i, j, 3) - h[i][j]);
        }
    }

    /**
     * Paints one natural column (u, v) with its top at {@code top}, reading the relief the way a geologist would:
     * cliff edges are bare bedrock, ledges and the foot of cliffs catch soil and scree, steep slopes are turf broken by
     * rock, flat ground is forest soil or meadow; snow up high; sand and gravel along water.
     */
    static void paintColumn(int u, int v, int top, Relief r, Rock rock, int snowLine, boolean nearWater) {
        int deep = Math.max(Canvas.MINY, top - 12);
        Canvas.fill(u, Canvas.MINY, v, u, deep - 1, v, "stone");
        double n = noise(u, v, 9, 311), n2 = noise(u, v, 4, 312), n3 = noise(u, v, 2.5, 313);
        for (int y = deep; y <= top; y++) Canvas.set(u, y, v, stratum(rock, u, y, v));
        if (top < Canvas.SEA) {
            String bed = rock == Rock.COAST && r.drop() >= 2 ? stratum(rock, u, top, v) : n2 < 0.55 ? "sand" : n2 < 0.8 ? "gravel" : "clay";
            Canvas.set(u, top, v, bed);
            Canvas.fill(u, top + 1, v, u, Canvas.SEA, v, "water");
            if (top < Canvas.SEA - 2 && R.nextDouble() < 0.08) Canvas.set(u, top + 1, v, "seagrass");
            Canvas.setGround(u, v, Canvas.SEA);
            return;
        }
        Canvas.setGround(u, v, top);
        boolean high = top > snowLine + (int) (n * 6);
        boolean alpine = top > ROCK_LINE + (int) (n2 * 8);
        if (alpine && (r.drop() >= 2 || ridged(u, v, 11, 3, 314) > 0.62)) {
            if (high && r.drop() <= 3 && n3 > 0.3) Canvas.set(u, top, v, "snow_block");
            return;
        }
        // cliff edge: the face below is bare; the top is rock with the odd tuft
        if (r.drop() >= 4) {
            if (high && n2 > 0.35) Canvas.set(u, top, v, "snow_block");
            else if (r.rise() <= 1 && n3 > 0.72) Canvas.set(u, top, v, "grass_block");
            return;
        }
        if (high) {
            Canvas.set(u, top, v, "snow_block");
            if (r.drop() <= 1) Canvas.setIfAir(u, top + 1, v, "snow[layers=" + (1 + (int) (n2 * 3)) + "]");
            return;
        }
        if (nearWater && top <= Canvas.SEA + 1) {
            Canvas.fill(u, top - 2, v, u, top, v, n2 < 0.6 ? "sand" : "gravel");
            return;
        }
        // foot of a cliff: a scree apron, grassing over further out
        if (r.farRise() >= 6 && r.drop() <= 2) {
            double apron = Math.min(1, (r.farRise() - 5) / 6.0);
            if (n3 < 0.35 + 0.35 * apron) {
                Canvas.set(u, top, v, n2 < 0.45 ? "gravel" : n2 < 0.75 ? "andesite" : n2 < 0.9 ? "cobblestone" : "tuff");
                Canvas.set(u, top - 1, v, "gravel");
                return;
            }
        }
        boolean exposed = r.drop() >= 2;
        if (exposed) {
            // steep slope: thin turf and moss over bedrock, broken by bare rock
            boolean alpineMeadow = top > snowLine - 14 || top > ROCK_LINE - 12;
            String s = n3 < (alpineMeadow ? 0.38 : 0.2) ? stratum(rock, u, top, v)
                    : n3 < 0.62 ? "moss_block" : n3 < 0.9 ? "grass_block" : "coarse_dirt";
            Canvas.set(u, top, v, s);
            return;
        }
        Canvas.fill(u, top - 3, v, u, top - 1, v, "dirt");
        String soil;
        if (top > snowLine - 14) soil = n2 < 0.25 ? "coarse_dirt" : n2 < 0.33 ? "gravel" : "grass_block";
        else if (exposed && n2 < 0.3) soil = "coarse_dirt";
        else if (n > 0.68) soil = "podzol";
        else if (n < 0.16) soil = "coarse_dirt";
        else if (n2 > 0.88) soil = "moss_block";
        else soil = "grass_block";
        Canvas.set(u, top, v, soil);
    }


    // ------------------------------------------------------------------ cliff detail

    /**
     * Works the cliff faces over after painting: notches and overhangs, vines hanging from ledges, tufts of grass and
     * bushes on ledges. {@code isCliff} marks columns that may be carved.
     */
    static void cliffDetail(int[][] h, boolean[][] wild) {
        for (int u = Canvas.MINX + 1; u < Canvas.MAXX; u++)
            for (int v = Canvas.MINZ + 1; v < Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!wild[i][j]) continue;
                int top = h[i][j];
                if (top <= Canvas.SEA) continue;
                for (B.Dir d : B.Dir.values()) {
                    int ni = i + d.dx, nj = j + d.dz;
                    if (ni < 0 || nj < 0 || ni >= h.length || nj >= h[0].length || !wild[ni][nj]) continue;
                    int low = Math.max(h[ni][nj], Canvas.SEA);
                    int drop = top - low;
                    if (drop < 5) continue;
                    // notches and overhangs: carve into the face where 3D noise says so, never the top two blocks
                    for (int y = low + 2; y <= top - 2; y++)
                        if (noise3(u, y, v, 401) > 0.66 && Canvas.isSolid(u, y, v)) {
                            Canvas.set(u, y, v, "air");
                            if (noise3(u - d.dx, y, v - d.dz, 402) > 0.72) Canvas.set(u - d.dx, y, v - d.dz, "air");
                        }
                    // vines hanging down the face from a ledge
                    if (R.nextDouble() < 0.06) {
                        int len = 3 + R.nextInt(Math.max(1, Math.min(9, drop - 2)));
                        int nu = u + d.dx, nv = v + d.dz;
                        String face = d.opposite().n();
                        for (int k = 1; k <= len; k++) {
                            int y = top - k + 1;
                            if (!Canvas.isAir(nu, y, nv) || !Canvas.isSolid(u, y, v)) break;
                            Canvas.set(nu, y, nv, "vine[" + face + "=true]");
                        }
                    }
                }
                // life on ledges
                if (Geology.slope(h, i, j) >= 3 && Canvas.isAir(u, top + 1, v) && R.nextDouble() < 0.16) {
                    double c = R.nextDouble();
                    if (c < 0.45) {
                        Canvas.set(u, top, v, "grass_block");
                        Canvas.set(u, top + 1, v, R.nextDouble() < 0.6 ? "short_grass" : "fern");
                    } else if (c < 0.7) Canvas.set(u, top + 1, v, "azalea_leaves[persistent=true]");
                    else if (c < 0.85) Canvas.set(u, top, v, "moss_block");
                    else Canvas.set(u, top + 1, v, "spruce_leaves[persistent=true]");
                }
            }
    }

    /** Carves a cave mouth into a cliff: an ellipsoid tunnel from (u, y, v) heading in direction (du, dv). */
    static void cave(int u, int y, int v, double du, double dv, int length, double rw, double rh) {
        double len = Math.hypot(du, dv);
        du /= len;
        dv /= len;
        for (int k = 0; k <= length; k++) {
            double cu = u + du * k, cv = v + dv * k, cy = y + Math.sin(k * 0.4) * 0.8;
            double shrink = 1 - 0.4 * k / (double) length;
            for (int a = -4; a <= 4; a++)
                for (int b = -3; b <= 4; b++)
                    for (int c = -4; c <= 4; c++) {
                        double ex = a / (rw * shrink), ey = b / (rh * shrink), ez = c / (rw * shrink);
                        if (ex * ex + ey * ey + ez * ez > 1) continue;
                        int x = (int) Math.round(cu) + a, yy = (int) Math.round(cy) + b, z = (int) Math.round(cv) + c;
                        if (Canvas.isSolid(x, yy, z)) Canvas.set(x, yy, z, "air");
                    }
        }
        // a little floor detail
        for (int k = 2; k < length; k += 3) {
            int x = (int) Math.round(u + du * k), z = (int) Math.round(v + dv * k);
            int fy = y - (int) Math.round(rh) + 1;
            if (Canvas.isAir(x, fy, z) && Canvas.isSolid(x, fy - 1, z)) Canvas.set(x, fy, z, R.nextDouble() < 0.5 ? "moss_carpet" : "brown_mushroom");
        }
    }
}
