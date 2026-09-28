import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Övre staden": a new quarter next to the old spawn castle, built as an overlay on the existing world. Streets follow
 * the natural ground, lots are laid along them, and every house is one of the medieval types that match the castle.
 * Existing builds are detected and never touched.
 */
final class OldTown {

    static final Random R = new Random(1318);
    static final int SX = Canvas.SX, SZ = Canvas.SZ;
    static final boolean[][] BUILT = new boolean[SX][SZ];
    static final boolean[][] ROAD = new boolean[SX][SZ];
    static final int[][] ROAD_Y = new int[SX][SZ];

    record Street(String name, List<double[]> pts) {}

    static final List<Street> STREETS = new ArrayList<>();

    static void street(String name, double... xz) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) pts.add(new double[] {xz[i] - Box.worldX, xz[i + 1] - Box.worldZ});
        STREETS.add(new Street(name, pts));
    }

    /** World position of the castle (lots near it are worth more). */
    static int CASTLE_X = 1660, CASTLE_Z = 40;
    static String DISTRICT = "Övre staden";

    static void layout() {
        if (Box.name.equals("districts")) {
            districts();
            return;
        }
        street("Borggatan", 1690, -34, 1712, -52, 1740, -66, 1772, -76, 1806, -78, 1826, -86);
        street("Tornvägen", 1744, -68, 1748, -92, 1742, -112, 1728, -132);
        street("Kvarngränd", 1790, -77, 1798, -56, 1796, -34, 1804, -24);
        street("Smedjegränd", 1700, -46, 1690, -70, 1686, -96, 1700, -118);
    }

    /** Streets around the transplanted hub, given relative to the hub origin (the hub's own layout). */
    static void districts() {
        DISTRICT = "Nordia stad";
        CASTLE_X = Box.worldX + 65;
        CASTLE_Z = Box.worldZ - 30;
        rel("Torggatan", 67, 110, 64, 132, 58, 152, 40, 178, 12, 196, -22, 194, -48, 184);
        rel("Kvarnvägen", -70, 26, -78, 52, -86, 82, -96, 110, -112, 134, -132, 146);
        rel("Östergatan", 116, 70, 138, 78, 162, 76, 186, 60, 202, 30);
        rel("Skogsgränd", 12, 196, 2, 172, -16, 148, -40, 126, -62, 100, -84, 84);
        rel("Sjövägen", 162, 76, 168, 104, 160, 132, 142, 160, 114, 180, 84, 188, 58, 186);
        rel("Kyrkstigen", -48, 184, -70, 176, -92, 166, -112, 150);
    }

    static void rel(String name, int... uv) {
        double[] xz = new double[uv.length];
        for (int i = 0; i < uv.length; i += 2) {
            xz[i] = Box.worldX + uv[i];
            xz[i + 1] = Box.worldZ + uv[i + 1];
        }
        street(name, xz);
    }

    static final Set<String> NATURAL = Set.of("air", "cave_air", "grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt",
            "stone", "andesite", "diorite", "granite", "gravel", "sand", "clay", "water", "tuff", "calcite", "deepslate", "moss_block",
            "snow", "snow_block", "mossy_cobblestone", "dirt_path", "mud", "farmland", "sweet_berry_bush", "bee_nest", "vine",
            "cobweb", "pumpkin", "melon", "sugar_cane", "copper_ore", "coal_ore", "iron_ore");

    static boolean natural(String b) {
        return NATURAL.contains(b) || B.isPlant(b) || b.endsWith("_leaves") || b.endsWith("_log") || b.endsWith("_wood")
               || b.contains("mushroom") || b.endsWith("_sapling");
    }

    // ------------------------------------------------------------------ setup

    static void survey() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int x = Box.worldX + u, z = Box.worldZ + v;
                int g = Terrain.nat.ground(x, z);
                int top = Terrain.nat.surface(x, z);
                g = g == WorldReader.MISSING ? 0 : g - 64;
                Terrain.H[i][j] = g;
                Terrain.WEIGHT[i][j] = 1;
                Canvas.setGround(u, v, g);
                // a column is taken if anything man-made stands on it
                int ceil = top == WorldReader.MISSING ? g + 12 : Math.max(g + 12, top - 64);
                for (int y = g - 1; y <= ceil && !BUILT[i][j]; y++) if (!natural(Canvas.world(u, y, v))) BUILT[i][j] = true;
            }
        // keep a margin around existing builds
        boolean[][] grown = new boolean[SX][SZ];
        for (int i = 0; i < SX; i++)
            for (int j = 0; j < SZ; j++) {
                if (!BUILT[i][j]) continue;
                for (int a = -4; a <= 4; a++)
                    for (int b = -4; b <= 4; b++) {
                        int ii = i + a, jj = j + b;
                        if (ii >= 0 && jj >= 0 && ii < SX && jj < SZ) grown[ii][jj] = true;
                    }
            }
        int n = 0;
        for (int i = 0; i < SX; i++) for (int j = 0; j < SZ; j++) if (BUILT[i][j] = grown[i][j]) n++;
        System.out.println("oldtown: " + n + " columns next to existing builds are left alone");
    }

    // ------------------------------------------------------------------ streets

    static String paving() {
        double r = R.nextDouble();
        return r < 0.35 ? "cobblestone" : r < 0.6 ? "gravel" : r < 0.75 ? "andesite" : r < 0.88 ? "stone" : r < 0.95 ? "dirt_path" : "mossy_cobblestone";
    }

    static void streets() {
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            int n = pts.size();
            double[] y = new double[n];
            for (int k = 0; k < n; k++) y[k] = Terrain.h((int) Math.round(pts.get(k)[0]), (int) Math.round(pts.get(k)[1]));
            for (int pass = 0; pass < 8; pass++) for (int k = 1; k < n - 1; k++) y[k] = (y[k - 1] + 2 * y[k] + y[k + 1]) / 4;
            for (int k = 1; k < n; k++) y[k] = Math.max(y[k - 1] - 0.3, Math.min(y[k - 1] + 0.3, y[k]));
            for (int k = n - 2; k >= 0; k--) y[k] = Math.max(y[k + 1] - 0.3, Math.min(y[k + 1] + 0.3, y[k]));
            for (int k = 0; k < n; k++) {
                double[] p = pts.get(k);
                int yy = (int) Math.round(y[k]);
                for (int du = -3; du <= 3; du++)
                    for (int dv = -3; dv <= 3; dv++) {
                        int u = (int) Math.round(p[0]) + du, v = (int) Math.round(p[1]) + dv;
                        if (!Canvas.inXZ(u, v) || Math.hypot(u - p[0], v - p[1]) > 2.3) continue;
                        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                        if (BUILT[i][j] && !ROAD[i][j] && Terrain.H[i][j] > yy + 1) continue;
                        if (!ROAD[i][j] || Math.abs(ROAD_Y[i][j] - yy) > 0) {
                            ROAD[i][j] = true;
                            ROAD_Y[i][j] = yy;
                        }
                    }
            }
        }
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!ROAD[i][j]) continue;
                int y = ROAD_Y[i][j], g = Terrain.H[i][j];
                Canvas.fill(u, Math.min(g, y) - 2, v, u, y - 1, v, "cobblestone");
                Canvas.set(u, y, v, paving());
                Canvas.fill(u, y + 1, v, u, y + 6, v, "air");
                Terrain.H[i][j] = y;
                Canvas.setGround(u, v, y);
                Canvas.use(u, v, u, v);
                Canvas.STREET[i][j] = true;
            }
    }

    static boolean road(int u, int v) {
        return Canvas.inXZ(u, v) && ROAD[u - Canvas.MINX][v - Canvas.MINZ];
    }

    // ------------------------------------------------------------------ lots

    record Lot(int id, String street, int number, B.Frame frame, int w, int d, double score, String tier, Medieval.Type type,
               Medieval.Result house) {}

    static final List<Lot> LOTS = new ArrayList<>();

    static boolean free(int u, int v) {
        if (!Canvas.inXZ(u, v) || u < Canvas.MINX + 3 || u > Canvas.MAXX - 3 || v < Canvas.MINZ + 3 || v > Canvas.MAXZ - 3) return false;
        int i = u - Canvas.MINX, j = v - Canvas.MINZ;
        return !BUILT[i][j] && !ROAD[i][j] && !Canvas.used(u, v) && Terrain.H[i][j] > Canvas.SEA + 1;
    }

    static boolean lotFree(B.Frame f, int w, int d) {
        int min = 999, max = -999;
        for (int x = -1; x <= w; x++)
            for (int z = -1; z < d; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                if (z >= d - 3 && road(u, v)) continue;
                if (!free(u, v)) return false;
                int g = Terrain.h(u, v);
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
        if (max - min > 7) return false;
        int front = 0;
        for (int x = 0; x < w; x++) if (road(f.wx(x, d), f.wz(x, d)) || road(f.wx(x, d + 1), f.wz(x, d + 1))) front++;
        return front >= w / 2;
    }

    static void lots() {
        int id = 1;
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            int number = 1;
            for (int side : new int[] {-1, 1}) {
                int k = 4;
                while (k < pts.size() - 4) {
                    double[] a = pts.get(k - 1), b = pts.get(k + 1), p = pts.get(k);
                    double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                    double nx = -tz / len * side, nz = tx / len * side;
                    B.Dir primary = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? B.Dir.WEST : B.Dir.EAST) : (nz > 0 ? B.Dir.NORTH : B.Dir.SOUTH);
                    int[][] sizes = {{28, 30}, {24, 26}, {20, 22}, {17, 20}, {14, 17}};
                    boolean placed = false;
                    search:
                    for (int[] sz : sizes)
                        for (int off : new int[] {3, 4, 5}) {
                            int w = sz[0] - R.nextInt(3), d = sz[1] - R.nextInt(3);
                            int fu = (int) Math.round(p[0] + nx * off), fv = (int) Math.round(p[1] + nz * off);
                            B.Frame zero = B.Frame.facing(0, 0, 0, primary);
                            int ou = fu - zero.wx(w / 2, d - 1), ov = fv - zero.wz(w / 2, d - 1);
                            B.Frame probe = B.Frame.facing(ou, 0, ov, primary);
                            if (!lotFree(probe, w, d)) continue;
                            for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) if (!road(probe.wx(x, z), probe.wz(x, z))) Canvas.use(probe.wx(x, z), probe.wz(x, z), probe.wx(x, z), probe.wz(x, z));
                            LOTS.add(new Lot(id++, s.name(), number, probe, w, d, 0, null, null, null));
                            number += 2;
                            k += (int) (w * 2.1) + 3;
                            placed = true;
                            break search;
                        }
                    if (!placed) k += 3;
                }
                number = 2;
            }
        }
        System.out.println("oldtown lots: " + LOTS.size());
    }

    /** Value by location: close to the castle, lot size, a view over the valley, corner. */
    static Lot valued(Lot l) {
        B.Frame f = l.frame();
        int cu = f.wx(l.w() / 2, l.d() / 2), cv = f.wz(l.w() / 2, l.d() / 2);
        double castle = Math.hypot(Box.worldX + cu - CASTLE_X, Box.worldZ + cv - CASTLE_Z);
        int edge = 99;
        for (int du = -30; du <= 30; du += 3)
            for (int dv = -30; dv <= 30; dv += 3)
                if (Canvas.inXZ(cu + du, cv + dv) && Terrain.h(cu + du, cv + dv) < Terrain.h(cu, cv) - 12) edge = Math.min(edge, (int) Math.hypot(du, dv));
        boolean corner = false;
        for (int z = 0; z < l.d(); z++) if (road(f.wx(-2, z), f.wz(-2, z)) || road(f.wx(l.w() + 1, z), f.wz(l.w() + 1, z))) corner = true;
        double score = 1.2 + l.w() * l.d() / 220.0 + Math.max(0, (180 - castle) / 60.0) + (edge < 30 ? 1.6 - edge / 25.0 : 0) + (corner ? 0.6 : 0);
        String tier = score >= 5.6 ? "PREMIUM" : score >= 4.4 ? "LARGE" : score >= 3.2 ? "MEDIUM" : "SMALL";
        return new Lot(l.id(), l.street(), l.number(), f, l.w(), l.d(), score, tier, null, null);
    }

    // ------------------------------------------------------------------ build

    static void houses() {
        List<Lot> valued = new ArrayList<>();
        for (Lot l : LOTS) valued.add(valued(l));
        // the single best lot gets the manor
        Lot best = valued.stream().max((a, b) -> Double.compare(a.score(), b.score())).orElse(null);
        Map<Medieval.Type, Integer> count = new EnumMap<>(Medieval.Type.class);
        Medieval.R = R;
        List<Lot> built = new ArrayList<>();
        for (Lot l : valued) {
            Medieval.Type t;
            if (l == best && l.w() >= 22) t = Medieval.Type.MANOR;
            else {
                List<Medieval.Type> opt = switch (l.tier()) {
                    case "PREMIUM", "LARGE" -> List.of(Medieval.Type.MERCHANT, Medieval.Type.TOWERHOUSE, Medieval.Type.TOWNHOUSE);
                    case "MEDIUM" -> List.of(Medieval.Type.TOWNHOUSE, Medieval.Type.TOWERHOUSE, Medieval.Type.COTTAGE);
                    default -> List.of(Medieval.Type.COTTAGE, Medieval.Type.TOWNHOUSE);
                };
                t = opt.get(0);
                int bestC = Integer.MAX_VALUE;
                for (Medieval.Type o : opt) {
                    int c = count.getOrDefault(o, 0) * 3 + R.nextInt(3);
                    if (c < bestC) {
                        bestC = c;
                        t = o;
                    }
                }
            }
            count.merge(t, 1, Integer::sum);
            built.add(buildLot(l, t));
        }
        LOTS.clear();
        LOTS.addAll(built);
    }

    static Lot buildLot(Lot l, Medieval.Type t) {
        B.Frame lf = l.frame();
        int[] sz = Medieval.size(t);
        int hw = Math.min(sz[0], l.w() - 8), hd = Math.min(sz[1], l.d() - 9);
        int setback = Math.max(4, Math.min(l.d() - hd - 4, 4 + R.nextInt(3)));
        int hx = (l.w() - hw) / 2 + R.nextInt(3) - 1, hz = l.d() - setback - hd;
        // clear the lot of trees and tall plants, lay grass
        int floor = Integer.MIN_VALUE;
        for (int x = 0; x < l.w(); x++)
            for (int z = 0; z < l.d(); z++) {
                int u = lf.wx(x, z), v = lf.wz(x, z);
                if (road(u, v)) continue;
                int g = Terrain.h(u, v);
                Canvas.fill(u, g + 1, v, u, g + 26, v, "air");
                if (Canvas.world(u, g, v).equals("grass_block") || Canvas.world(u, g, v).equals("dirt")) Canvas.set(u, g, v, "grass_block");
            }
        for (int x = hx - 1; x <= hx + hw; x++) for (int z = hz - 1; z <= hz + hd; z++) floor = Math.max(floor, Terrain.h(lf.wx(x, z), lf.wz(x, z)));
        floor += 1;
        B.Frame hf = new B.Frame(lf.wx(hx, hz), floor, lf.wz(hx, hz), lf.turns());
        Medieval.Result res = Medieval.build(t, hf, hw, hd);
        garden(lf, l, hx, hz, hw, hd, floor, res);
        return new Lot(l.id(), l.street(), l.number(), lf, l.w(), l.d(), l.score(), l.tier(), t, res);
    }

    static void garden(B.Frame f, Lot l, int hx, int hz, int hw, int hd, int floor, Medieval.Result res) {
        int w = l.w(), d = l.d();
        int doorX = hx + res.doorX();
        boolean hedge = R.nextBoolean();
        // boundary: low stone walls with pillars, or clipped azalea hedges; a gate opening at the path
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || x == w - 1 || z == 0 || z == d - 1;
                if (!edge) continue;
                int u = f.wx(x, z), v = f.wz(x, z);
                if (road(u, v)) continue;
                if (z == d - 1 && Math.abs(x - doorX) <= 1) continue;
                int g = Terrain.h(u, v);
                if (hedge) {
                    Canvas.set(u, g + 1, v, "flowering_azalea_leaves[persistent=true]");
                    if (z != d - 1) Canvas.set(u, g + 2, v, "azalea_leaves[persistent=true]");
                } else {
                    boolean pillar = (x + z) % 5 == 0;
                    Canvas.set(u, g + 1, v, pillar ? "stone_bricks" : "stone_brick_wall");
                    if (pillar) Canvas.set(u, g + 2, v, z == d - 1 ? "lantern" : "stone_brick_slab[type=bottom]");
                }
            }
        // gate posts with lanterns
        for (int x : new int[] {doorX - 2, doorX + 2}) {
            int u = f.wx(x, d - 1), v = f.wz(x, d - 1);
            if (road(u, v)) continue;
            int g = Terrain.h(u, v);
            Canvas.set(u, g + 1, v, "stone_bricks");
            Canvas.set(u, g + 2, v, "stone_brick_wall");
            Canvas.set(u, g + 3, v, "lantern");
        }
        // path of cobble and gravel from the gate to the door, stepping up to the floor
        for (int z = hz + hd; z < d; z++) {
            int u = f.wx(doorX, z), v = f.wz(doorX, z);
            if (road(u, v)) break;
            Canvas.set(u, Terrain.h(u, v), v, (z & 1) == 0 ? "cobblestone" : "gravel");
        }
        // flower beds along the front, fruit trees and a woodpile or well at the back
        for (int x = hx; x < hx + hw; x++) {
            if (Math.abs(x - doorX) <= 1) continue;
            int u = f.wx(x, hz + hd + 1), v = f.wz(x, hz + hd + 1);
            int g = Terrain.h(u, v);
            if (!Canvas.isAir(u, g + 1, v)) continue;
            Canvas.set(u, g, v, "podzol");
            Canvas.set(u, g + 1, v, R.nextInt(3) == 0 ? "flowering_azalea_leaves[persistent=true]" : Canvas.pick("red_tulip", "poppy", "cornflower", "allium", "oxeye_daisy"));
        }
        int backZ = Math.max(2, hz - 5);
        if (hz >= 8 && R.nextBoolean()) {
            int wx = 3 + R.nextInt(Math.max(1, w - 6)), wz = Math.max(3, hz - 4);
            int u = f.wx(wx, wz), v = f.wz(wx, wz);
            Medieval.well(new B.Frame(u, Terrain.h(u, v), v, f.turns()), 0, 0);
        } else if (hz >= 5) {
            for (int x = 2; x < Math.min(w - 2, 8); x++) {
                int u = f.wx(x, 2), v = f.wz(x, 2);
                int g = Terrain.h(u, v);
                Canvas.set(u, g + 1, v, B.rotate("spruce_log[axis=x]", f.turns()));
                if (x % 2 == 0) Canvas.set(u, g + 2, v, B.rotate("birch_log[axis=x]", f.turns()));
            }
        }
        for (int k = 0, tries = 0; k < (l.w() * l.d() > 500 ? 3 : 1) && tries < 30; tries++) {
            int x = 2 + R.nextInt(w - 4), z = 2 + R.nextInt(Math.max(1, backZ));
            if (x > hx - 3 && x < hx + hw + 2) continue;
            int u = f.wx(x, z), v = f.wz(x, z);
            int g = Terrain.h(u, v);
            if (!Canvas.isAir(u, g + 1, v)) continue;
            if (R.nextBoolean() ? Nature.spawnTree(u, v, g, R) : Garden.fruitTree(u, v, g)) k++;
        }
        // a bench by the door and an address label
        int bu = f.wx(doorX + 2, hz + hd), bv = f.wz(doorX + 2, hz + hd);
        Canvas.setIfAir(bu, Terrain.h(bu, bv) + 1, bv, B.rotate(B.stairs("spruce", B.Dir.NORTH), f.turns()));
        int lu = f.wx(doorX + 2, d - 1), lv = f.wz(doorX + 2, d - 1);
        Town.label(lu + 0.5, Terrain.h(lu, lv) + 4.2, lv + 0.5, l.street() + " " + l.number(), "", "#F3E3C3", 0.6f);
    }

    static void furniture() {
        int k = 0;
        for (Street s : STREETS) {
            List<double[]> pts = River.spline(s.pts(), 0, 1);
            for (int i = 6; i < pts.size() - 3; i += 20) {
                double[] a = pts.get(i - 1), b = pts.get(i + 1), p = pts.get(i);
                double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                int side = (k++ & 1) == 0 ? 1 : -1;
                int u = (int) Math.round(p[0] - tz / len * 3 * side), v = (int) Math.round(p[1] + tx / len * 3 * side);
                if (!Canvas.inXZ(u, v) || road(u, v) || Canvas.used(u, v) || BUILT[u - Canvas.MINX][v - Canvas.MINZ]) continue;
                B.Dir arm = Math.abs(tz) > Math.abs(tx) ? (side * -tz > 0 ? B.Dir.WEST : B.Dir.EAST) : (side * tx > 0 ? B.Dir.SOUTH : B.Dir.NORTH);
                Build.hangingLamp(u, v, arm);
            }
            for (int i = 14; i < pts.size() - 3; i += 26) {
                double[] a = pts.get(i - 1), b = pts.get(i + 1), p = pts.get(i);
                double tx = b[0] - a[0], tz = b[1] - a[1], len = Math.hypot(tx, tz);
                int side = ((i / 26) & 1) == 0 ? 1 : -1;
                int u = (int) Math.round(p[0] - tz / len * 4.5 * side), v = (int) Math.round(p[1] + tx / len * 4.5 * side);
                if (!Canvas.inXZ(u, v) || road(u, v) || Canvas.used(u, v) || BUILT[u - Canvas.MINX][v - Canvas.MINZ]) continue;
                Nature.spawnTree(u, v, Terrain.h(u, v), R);
            }
            double[] p0 = s.pts().get(0), p1 = s.pts().get(1);
            int su = (int) Math.round(p0[0] + (p1[0] - p0[0]) * 0.25), sv = (int) Math.round(p0[1] + (p1[1] - p0[1]) * 0.25);
            Town.label(su + 0.5, Terrain.h(su, sv) + 4.5, sv + 0.5, s.name(), "", "#F3C969", 0.9f);
        }
    }

    static void registry(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"district\": \"" + DISTRICT + "\",\n  \"lots\": [\n");
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
            int price = (int) (Math.round(switch (l.tier()) {
                case "PREMIUM" -> 1_900_000;
                case "LARGE" -> 1_000_000;
                case "MEDIUM" -> 560_000;
                default -> 280_000;
            } * (0.85 + l.score() * 0.05) / 5000.0) * 5000);
            sb.append(String.format(Locale.ROOT,
                    "    {\"id\": %d, \"address\": \"%s %d\", \"tier\": \"%s\", \"style\": \"%s\", \"floors\": %d, \"lotArea\": %d, "
                    + "\"from\": [%d, %d], \"to\": [%d, %d], \"locationScore\": %.2f, \"suggestedPriceSek\": %d}%s%n",
                    l.id(), l.street(), l.number(), l.tier(), l.house().style(), l.house().floors(), l.w() * l.d(),
                    Box.worldX + x0, Box.worldZ + z0, Box.worldX + x1, Box.worldZ + z1, l.score(), price, n + 1 < LOTS.size() ? "," : ""));
        }
        sb.append("  ]\n}\n");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        System.out.println("registry: " + LOTS.size() + " lots -> " + file);
    }

    static void generate() {
        Canvas.overlay();
        layout();
        survey();
        streets();
        lots();
        houses();
        furniture();
    }
}
