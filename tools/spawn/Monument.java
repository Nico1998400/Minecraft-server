import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/** Sculpted landmarks voxelised from simple solids: the harbour guardians, the world tree, runestones. */
final class Monument {

    // ------------------------------------------------------------------ solids

    interface Solid {
        boolean contains(double x, double y, double z);
    }

    record Ellipsoid(double cx, double cy, double cz, double rx, double ry, double rz) implements Solid {
        public boolean contains(double x, double y, double z) {
            double a = (x - cx) / rx, b = (y - cy) / ry, c = (z - cz) / rz;
            return a * a + b * b + c * c <= 1;
        }
    }

    record Capsule(double ax, double ay, double az, double bx, double by, double bz, double ra, double rb) implements Solid {
        public boolean contains(double x, double y, double z) {
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / len2));
            double px = ax + dx * t - x, py = ay + dy * t - y, pz = az + dz * t - z;
            double r = ra + (rb - ra) * t;
            return px * px + py * py + pz * pz <= r * r;
        }
    }

    record Box(double x0, double y0, double z0, double x1, double y1, double z1) implements Solid {
        public boolean contains(double x, double y, double z) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
        }
    }

    record Part(Solid solid, Supplier<String> material) {}

    /** Voxelises parts into a frame; earlier parts win. Tests voxel centres. */
    static void carve(B.Frame f, List<Part> parts, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++)
                    for (Part p : parts)
                        if (p.solid().contains(x + 0.5, y + 0.5, z + 0.5)) {
                            f.set(x, y, z, p.material().get());
                            break;
                        }
    }

    // ------------------------------------------------------------------ guardian

    static String statueStone(double y) {
        double r = Canvas.rnd();
        double moss = Math.max(0, 0.35 - y * 0.025);
        if (r < moss) return Canvas.rnd() < 0.5 ? "mossy_cobblestone" : "mossy_stone_bricks";
        r = Canvas.rnd();
        if (r < 0.55) return "stone";
        if (r < 0.80) return "andesite";
        if (r < 0.92) return "polished_andesite";
        return "tuff";
    }

    /**
     * A 31-block stone warrior with both hands on the pommel of a sword planted in front of him, cloak behind,
     * helmet with a gilded rim. Local front = +z. Stands on a rock islet whose top is local y 0.
     */
    static void guardian(B.Frame f) {
        List<Part> p = new ArrayList<>();
        Supplier<String> gold = () -> "gold_block";
        Supplier<String> dark = () -> Canvas.pick("polished_deepslate", "deepslate_tiles", "polished_deepslate");
        Supplier<String> beard = () -> Canvas.pick("tuff", "tuff", "andesite");
        Supplier<String> mail = () -> Canvas.pick("cobbled_deepslate", "polished_andesite", "andesite");
        Supplier<String> cloak = () -> Canvas.pick("cobbled_deepslate", "deepslate", "deepslate_bricks");
        Supplier<String> skin = () -> Canvas.pick("stone", "stone", "smooth_stone", "andesite");
        // head, helmet and face
        p.add(new Part(new Box(-0.6, 25.6, 2.2, 0.6, 27.8, 3.3), dark));                       // nasal guard
        p.add(new Part(new Box(-1.6, 26.4, 2.0, -0.6, 27.3, 3.2), () -> "deepslate"));          // eye shadow
        p.add(new Part(new Box(0.6, 26.4, 2.0, 1.6, 27.3, 3.2), () -> "deepslate"));
        p.add(new Part(new Ellipsoid(0, 27.6, 0.3, 3.35, 0.6, 3.35), gold));                     // helmet rim
        Ellipsoid dome = new Ellipsoid(0, 28.0, 0.3, 3.0, 3.1, 3.0);
        p.add(new Part((x, y, z) -> y >= 27.8 && dome.contains(x, y, z), dark));
        p.add(new Part(new Box(-0.5, 31.0, -0.2, 0.5, 32.2, 0.8), gold));                        // crest
        p.add(new Part(new Ellipsoid(0, 24.6, 1.9, 2.4, 3.0, 1.5), beard));                      // beard
        p.add(new Part(new Capsule(0, 21.8, 2.8, 0, 19.8, 3.3, 0.9, 0.5), beard));               // braid
        p.add(new Part(new Ellipsoid(0, 27.0, 0.3, 2.6, 3.0, 2.6), skin));                       // head
        p.add(new Part(new Capsule(0, 22.5, 0, 0, 24.5, 0.3, 1.9, 1.9), skin));                  // neck
        // sword: pommel between the hands, blade down to the plinth
        p.add(new Part(new Ellipsoid(0, 15.2, 5.2, 1.1, 1.1, 1.1), gold));
        p.add(new Part(new Box(-0.5, 10.5, 4.7, 0.5, 13.9, 5.7), () -> "dark_oak_planks"));      // grip
        p.add(new Part(new Box(-3.6, 9.6, 4.7, 3.6, 10.5, 5.7), gold));                          // crossguard
        p.add(new Part(new Box(-1.0, 0.0, 4.7, 1.0, 9.6, 5.7), () -> Canvas.pick("iron_block", "smooth_stone", "iron_block")));
        // hands resting on the pommel
        p.add(new Part(new Ellipsoid(-1.4, 14.2, 5.1, 1.5, 1.3, 1.6), skin));
        p.add(new Part(new Ellipsoid(1.4, 14.2, 5.1, 1.5, 1.3, 1.6), skin));
        // arms
        p.add(new Part(new Capsule(-5.3, 20.5, 0.2, -4.6, 15.5, 2.6, 1.85, 1.55), mail));
        p.add(new Part(new Capsule(5.3, 20.5, 0.2, 4.6, 15.5, 2.6, 1.85, 1.55), mail));
        p.add(new Part(new Capsule(-4.6, 15.5, 2.6, -1.8, 14.2, 4.8, 1.45, 1.25), dark));        // bracers
        p.add(new Part(new Capsule(4.6, 15.5, 2.6, 1.8, 14.2, 4.8, 1.45, 1.25), dark));
        p.add(new Part(new Ellipsoid(-5.2, 21.2, 0.0, 2.5, 2.1, 2.5), dark));                    // pauldrons
        p.add(new Part(new Ellipsoid(5.2, 21.2, 0.0, 2.5, 2.1, 2.5), dark));
        // torso, belt and tunic
        p.add(new Part(new Box(-4.6, 12.4, -2.6, 4.6, 13.4, 3.0), dark));                        // belt
        p.add(new Part(new Box(-0.8, 12.2, 2.7, 0.8, 13.6, 3.4), gold));                         // buckle
        p.add(new Part(new Ellipsoid(0, 17.8, 0.1, 5.0, 5.4, 3.2), mail));                       // torso
        p.add(new Part(new Capsule(0, 13, 0, 0, 7.5, 0, 4.4, 5.3), mail));                       // tunic skirt
        // legs and boots
        p.add(new Part(new Capsule(-2.6, 8, 0, -2.9, 3, 0.2, 2.1, 1.8), skin));
        p.add(new Part(new Capsule(2.6, 8, 0, 2.9, 3, 0.2, 2.1, 1.8), skin));
        p.add(new Part(new Box(-4.8, 0, -1.8, -1.2, 3.2, 2.8), dark));
        p.add(new Part(new Box(1.2, 0, -1.8, 4.8, 3.2, 2.8), dark));
        // cloak hanging from the shoulders down the back
        p.add(new Part(new Solid() {
            public boolean contains(double x, double y, double z) {
                if (y < 1 || y > 22.5) return false;
                double half = 5.4 + (22.5 - y) * 0.12;
                double back = -2.4 - (22.5 - y) * 0.09;
                return Math.abs(x) <= half && z <= -1.2 && z >= back - 1.2 + Math.sin(x * 0.9) * 0.4;
            }
        }, cloak));
        carve(f, p, -9, 0, -7, 9, 33, 8);
        // cloak clasps
        f.set(-4, 22, 1, "gold_block");
        f.set(4, 22, 1, "gold_block");
        // weather it: plants and moss at the feet (placed later if resting on something)
        for (int x = -8; x <= 8; x++)
            for (int z = -7; z <= 8; z++)
                if (f.isAir(x, 0, z) && Canvas.rnd() < 0.08) f.set(x, 0, z, Canvas.pick("moss_carpet", "short_grass", "fern"));
    }

    /** Rocky islet with a carved octagonal plinth; top at local y -1 (the statue starts at y 0). */
    static void plinth(B.Frame f, int seabed) {
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++) {
                double d = Math.hypot(x, z) + 1.5 * Terrain.noise(x, z, 3, 71);
                if (d > 10.5) continue;
                int top = d < 7.5 ? -1 : (int) Math.round(-1 - (d - 7.5) * 1.1);
                for (int y = seabed; y <= top; y++) f.set(x, y, z, Terrain.rock(y + 30));
                if (top >= -2 && Canvas.rnd() < 0.3) f.set(x, top, z, Canvas.pick("mossy_cobblestone", "moss_block"));
                double oct = Math.max(Math.abs(x) + Math.abs(z) * 0.414, Math.abs(z) + Math.abs(x) * 0.414);
                if (oct <= 6.8) {
                    f.set(x, -1, z, oct > 5.9 ? "chiseled_stone_bricks" : "stone_bricks");
                    f.set(x, -2, z, "stone_bricks");
                }
            }
        for (int[] c : new int[][] {{-6, 0}, {6, 0}, {0, -6}}) {
            f.set(c[0], 0, c[1], "stone_brick_wall");
            f.set(c[0], 1, c[1], "campfire[lit=true,signal_fire=false]");
        }
    }

    // ------------------------------------------------------------------ world tree

    /** Yggdrasil: a vast ash with buttress roots, a crown of branches and glowing vines. Built at (u, v) on the terrain. */
    static void worldTree(int u, int v) {
        Random r = new Random(9);
        int base = Canvas.ground(u, v);
        B.Frame f = new B.Frame(u, base, v, 0);
        List<Part> wood = new ArrayList<>();
        Supplier<String> bark = () -> Canvas.pick("dark_oak_wood", "dark_oak_wood", "spruce_wood", "dark_oak_wood", "mangrove_wood");
        Supplier<String> mossy = () -> Canvas.pick("dark_oak_wood", "moss_block", "dark_oak_wood", "mangrove_roots");
        // trunk with a slight twist
        double[] top = {1.5, 30, -1};
        wood.add(new Part(new Capsule(0, -3, 0, 0.8, 14, -0.4, 6.2, 4.2), bark));
        wood.add(new Part(new Capsule(0.8, 14, -0.4, top[0], top[1], top[2], 4.2, 2.6), bark));
        // buttress roots spreading over the ground
        int roots = 8;
        for (int i = 0; i < roots; i++) {
            double a = i * 2 * Math.PI / roots + r.nextDouble() * 0.4;
            double len = 11 + r.nextDouble() * 7;
            double ex = Math.cos(a) * len, ez = Math.sin(a) * len;
            int gy = Canvas.ground(u + (int) Math.round(ex), v + (int) Math.round(ez)) - base;
            wood.add(new Part(new Capsule(Math.cos(a) * 3, 3.5, Math.sin(a) * 3, ex * 0.55, 1.2, ez * 0.55, 2.6, 1.8), mossy));
            wood.add(new Part(new Capsule(ex * 0.55, 1.2, ez * 0.55, ex, gy - 0.5, ez, 1.8, 0.8), mossy));
        }
        // branches: main limbs from the upper trunk, each with two or three sub-limbs; leaves at the tips
        List<double[]> tips = new ArrayList<>();
        int limbs = 7;
        for (int i = 0; i < limbs; i++) {
            double a = i * 2 * Math.PI / limbs + r.nextDouble() * 0.5;
            double sy = 17 + i * 1.8;
            double[] s = {Math.cos(a) * 2, sy, Math.sin(a) * 2};
            double len = 13 + r.nextDouble() * 5;
            double[] e = {Math.cos(a) * len, sy + 7 + r.nextDouble() * 6, Math.sin(a) * len};
            wood.add(new Part(new Capsule(s[0], s[1], s[2], e[0], e[1], e[2], 2.2, 1.1), bark));
            int subs = 2 + r.nextInt(2);
            for (int k = 0; k < subs; k++) {
                double b = a + (r.nextDouble() - 0.5) * 1.6;
                double l2 = 7 + r.nextDouble() * 5;
                double[] e2 = {e[0] + Math.cos(b) * l2, e[1] + 2 + r.nextDouble() * 5, e[2] + Math.sin(b) * l2};
                wood.add(new Part(new Capsule(e[0], e[1], e[2], e2[0], e2[1], e2[2], 1.1, 0.6), bark));
                tips.add(e2);
            }
            tips.add(e);
        }
        tips.add(new double[] {top[0], top[1] + 6, top[2]});
        carve(f, wood, -24, -4, -24, 24, 44, 24);
        // crown: flattened leaf clouds, with glowing vines hanging from their undersides
        for (double[] t : tips) {
            double rx = 5.5 + r.nextDouble() * 2.5, ry = 3 + r.nextDouble() * 1.5;
            for (int x = (int) (t[0] - rx - 1); x <= t[0] + rx + 1; x++)
                for (int y = (int) (t[1] - ry - 1); y <= t[1] + ry + 1; y++)
                    for (int z = (int) (t[2] - rx - 1); z <= t[2] + rx + 1; z++) {
                        double dx = (x + 0.5 - t[0]) / rx, dy = (y + 0.5 - t[1]) / ry, dz = (z + 0.5 - t[2]) / rx;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > 1 - 0.25 * Terrain.noise(x * 3 + t[0], z * 3 + y, 1.3, 77)) continue;
                        if (!f.isAir(x, y, z)) continue;
                        double c = r.nextDouble();
                        f.set(x, y, z, c < 0.45 ? "dark_oak_leaves[persistent=true]" : c < 0.75 ? "azalea_leaves[persistent=true]"
                                : c < 0.92 ? "oak_leaves[persistent=true]" : "flowering_azalea_leaves[persistent=true]");
                    }
        }
        for (int x = -26; x <= 26; x++)
            for (int z = -26; z <= 26; z++)
                for (int y = 44; y >= 12; y--) {
                    String s = f.get(x, y, z);
                    if (!s.contains("leaves")) continue;
                    if (f.isAir(x, y - 1, z) && r.nextDouble() < 0.10) {
                        int len = 2 + r.nextInt(6);
                        for (int k = 1; k <= len; k++) {
                            if (!f.isAir(x, y - k, z)) break;
                            boolean last = k == len || !f.isAir(x, y - k - 1, z);
                            f.set(x, y - k, z, (last ? "cave_vines" : "cave_vines_plant") + "[berries=" + (r.nextDouble() < 0.6) + "]");
                            if (last) break;
                        }
                    }
                    break;
                }
        Canvas.use(u - 20, v - 20, u + 20, v + 20);
    }

    // ------------------------------------------------------------------ runestone

    /** A painted runestone, 3 wide and 6 tall, facing local +z. */
    static void runestone(B.Frame f) {
        int[][] snake = {{0, 1}, {1, 1}, {2, 2}, {2, 3}, {2, 4}, {1, 5}, {0, 5}, {0, 4}, {0, 3}, {1, 3}};
        for (int x = 0; x < 3; x++)
            for (int y = -1; y < 6; y++) {
                if (y == 5 && x != 1) continue;
                f.set(x, y, 0, Canvas.pick("stone", "andesite", "stone", "tuff"));
            }
        for (int[] s : snake) f.set(s[0], s[1], 0, "red_terracotta");
        f.set(1, 2, 0, "black_terracotta");
        f.setIfAir(-1, 0, 0, "short_grass");
        f.setIfAir(3, 0, 1, "fern");
    }
}
