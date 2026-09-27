import java.util.List;

/** The waterfront: longships, piers, the stilt quarter, boathouses, a crane and the beacon lighthouse. */
final class Harbour {

    static String log(String m, char axis) {
        return m + "[axis=" + axis + "]";
    }

    // ------------------------------------------------------------------ longship

    /**
     * A Viking longship along local z (bow at +z), keel centred on x = 0, deck at local y 0 (water surface at y -2).
     * Returns nothing; clutter and the sail are optional so moored and sailing ships differ.
     */
    static void longship(B.Frame f, int len, int beam, String[] sail, boolean sailing) {
        int mid = len / 2;
        double half = beam / 2.0;
        String[] shields = {"yellow_wool", "red_wool", "blue_wool", "white_wool", "black_wool", "red_wool"};
        for (int z = 0; z < len; z++) {
            double t = (z - mid) / (len / 2.0);
            int hw = Math.max(1, (int) Math.round(half * Math.sqrt(Math.max(0, 1 - Math.pow(Math.abs(t), 2.4)))));
            int gy = 1 + (int) Math.round(3 * Math.pow(Math.abs(t), 3.5));
            int ky = -4 + (int) Math.round(2 * Math.pow(Math.abs(t), 3));
            for (int y = ky; y <= gy; y++) {
                double k = y >= 0 ? 1 : Math.sqrt((y - ky + 0.7) / (0.0 - ky + 0.7));
                int hwy = Math.max(0, (int) Math.round(hw * k));
                for (int x = -hwy; x <= hwy; x++) {
                    boolean shell = Math.abs(x) == hwy || y == ky;
                    String s;
                    if (shell) s = (y & 1) == 0 ? "dark_oak_planks" : "spruce_planks";
                    else if (y < 0) s = "spruce_planks";
                    else if (y == 0) s = (x & 1) == 0 ? "spruce_planks" : "stripped_spruce_wood";
                    else s = "air";
                    f.set(x, y, z, s);
                }
            }
            for (int x = -hw + 1; x <= hw - 1; x++) for (int y = 1; y <= gy; y++) f.set(x, y, z, "air");
            if (Math.abs(t) < 0.72 && (z & 1) == 0) { // shields hung on the outside of the top strake
                f.set(-hw, 0, z, shields[(z / 2) % shields.length]);
                f.set(hw, 0, z, shields[(z / 2 + 3) % shields.length]);
            }
            if (Math.abs(t) < 0.65 && z % 3 == 1 && sailing) {
                f.fill(-hw - 3, 0, z, -hw - 1, 0, z, log("stripped_spruce_log", 'x'));
                f.fill(hw + 1, 0, z, hw + 3, 0, z, log("stripped_spruce_log", 'x'));
            }
        }
        // prow with a dragon head, curled stern
        int[][] stem = {{0, 1}, {0, 2}, {1, 2}, {1, 3}, {1, 4}, {2, 4}, {2, 5}, {2, 6}, {3, 6}, {3, 7}};
        for (int[] p : stem) f.set(0, p[1] + 1, len - 1 + p[0], "dark_oak_planks");
        Landmark.dragon(f, 0, 7, len + 2, 1, true);
        int[][] tail = {{0, 1}, {0, 2}, {-1, 2}, {-1, 3}, {-1, 4}, {-2, 4}, {-2, 5}, {-2, 6}, {-1, 7}, {0, 7}, {0, 6}};
        for (int[] p : tail) f.set(0, p[1] + 1, p[0], "dark_oak_planks");
        // steering oar on the starboard quarter
        f.fill((int) half, -3, 3, (int) half, 2, 3, log("stripped_spruce_log", 'y'));
        // mast, yard and a striped square sail that bellies forward
        int mz = mid;
        f.fill(0, 1, mz, 0, 22, mz, log("spruce_log", 'y'));
        f.set(0, 23, mz, "gold_block");
        int sw = beam + 5, top = 20;
        f.fill(-sw / 2 - 1, top, mz, sw / 2 + 1, top, mz, log("stripped_spruce_log", 'x'));
        if (sail != null)
            for (int x = -sw / 2; x <= sw / 2; x++)
                for (int y = top - 10; y < top; y++) {
                    double bx = 1 - Math.pow(x / (sw / 2.0 + 1), 2), by = 1 - Math.pow((y - (top - 5.5)) / 6.0, 2);
                    int belly = sailing ? (int) Math.round(2.2 * bx * by) : (int) Math.round(0.8 * bx * by);
                    String c = sail[Math.floorMod((x + sw / 2) / 2, sail.length)];
                    f.set(x, y, mz + 1 + belly, c);
                }
        else f.fill(-sw / 2, top - 1, mz, sw / 2, top - 1, mz, "white_wool");
        // shrouds from the masthead to the rails
        for (int k = 1; k <= 3; k++) {
            f.set(-k, 22 - k * 5, mz - k, "iron_chain[axis=y]");
            f.set(k, 22 - k * 5, mz - k, "iron_chain[axis=y]");
        }
        // cargo, a tent aft, lanterns
        int[][] cargo = {{-1, mid - 5}, {1, mid - 5}, {0, mid - 6}, {-1, mid + 5}, {1, mid + 6}, {-2, mid + 3}};
        for (int[] c : cargo) f.set(c[0], 1, c[1], Canvas.pick("barrel[facing=up]", "chest[facing=east]", "hay_block", "barrel[facing=up]"));
        for (int z = 3; z <= 6; z++) {
            f.set(-2, 3, z, "red_wool");
            f.set(-1, 4, z, "white_wool");
            f.set(0, 5, z, "red_wool");
            f.set(1, 4, z, "white_wool");
            f.set(2, 3, z, "red_wool");
        }
        f.set(-2, 1, 3, "spruce_fence");
        f.set(-2, 2, 3, "spruce_fence");
        f.set(2, 1, 3, "spruce_fence");
        f.set(2, 2, 3, "spruce_fence");
        f.set(0, 1, len - 4, "lantern");
        f.set(0, 1, 8, "lantern");
    }

    // ------------------------------------------------------------------ piers

    /** A wooden pier from the quay out over the water along local +z, 3 wide. */
    static void pier(B.Frame f, int len) {
        for (int z = 0; z < len; z++) {
            for (int x = -1; x <= 1; x++) f.set(x, 0, z, (z & 1) == 0 ? "spruce_planks" : "stripped_spruce_wood");
            if (z % 4 == 3 || z == len - 1)
                for (int x : new int[] {-2, 2}) {
                    f.fill(x, -10, z, x, 0, z, log("spruce_log", 'y'));
                    f.set(x, 1, z, "spruce_fence");
                    if (z == len - 1 || z % 8 == 3) {
                        f.set(x, 2, z, "spruce_fence");
                        f.set(x, 3, z, "lantern");
                    }
                }
            if (z % 5 == 2 && Canvas.rnd() < 0.6) f.set(Canvas.pick(-1, 1), 1, z, Canvas.pick("barrel[facing=up]", "spruce_slab[type=bottom]", "chest[facing=west]"));
        }
        for (int x = -2; x <= 2; x++) f.set(x, 0, len, "spruce_slab[type=bottom]");
        f.set(-2, 1, len - 2, "cobweb");
    }

    // ------------------------------------------------------------------ stilt quarter

    /** Stone piers with arches carrying a timber deck at local y 0, over the area x 0..w-1, z 0..d-1. */
    static void stiltDeck(B.Frame f, int w, int d, int seabed) {
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                f.set(x, 0, z, (x + z) % 3 == 0 ? "stripped_spruce_wood" : "spruce_planks");
                Canvas.setGround(f.wx(x, z), f.wz(x, z), f.oy());
                Canvas.use(f.wx(x, z), f.wz(x, z), f.wx(x, z), f.wz(x, z));
            }
        for (int x = 0; x < w; x += 5)
            for (int z = 0; z < d; z += 5) {
                int px = Math.min(x, w - 2), pz = Math.min(z, d - 2);
                for (int y = seabed; y <= -1; y++)
                    for (int a = 0; a < 2; a++)
                        for (int b = 0; b < 2; b++)
                            f.set(px + a, y, pz + b, y > -4 ? Canvas.pick("stone_bricks", "mossy_stone_bricks", "deepslate_bricks")
                                    : Canvas.pick("mossy_cobblestone", "mossy_stone_bricks", "cobbled_deepslate"));
            }
        // arches along the outer edges between the piers
        for (int x = 0; x < w; x++)
            for (int z : new int[] {0, d - 1}) {
                int m = x % 5;
                if (m == 0 || m == 1) continue;
                f.set(x, -1, z, "stone_bricks");
                if (m == 2) f.set(x, -2, z, B.stairsTop("stone_brick", B.Dir.WEST));
                if (m == 4) f.set(x, -2, z, B.stairsTop("stone_brick", B.Dir.EAST));
            }
        for (int z = 0; z < d; z++)
            for (int x : new int[] {0, w - 1}) {
                int m = z % 5;
                if (m == 0 || m == 1) continue;
                f.set(x, -1, z, "stone_bricks");
                if (m == 2) f.set(x, -2, z, B.stairsTop("stone_brick", B.Dir.NORTH));
                if (m == 4) f.set(x, -2, z, B.stairsTop("stone_brick", B.Dir.SOUTH));
            }
        // railing and lanterns around the edge
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || x == w - 1 || z == 0 || z == d - 1;
                if (edge && (x + z) % 6 == 0) {
                    f.setIfAir(x, 1, z, "spruce_fence");
                    f.setIfAir(x, 2, z, "lantern");
                }
            }
    }

    /** A tall Junopii-style chimney stack of pale stone with dark bands. */
    static void tallChimney(B.Frame f, int x, int z, int y0, int height) {
        for (int y = y0; y < y0 + height; y++) {
            String s = (y - y0) % 6 == 5 ? "polished_deepslate" : "calcite";
            f.set(x, y, z, s);
            f.set(x + 1, y, z, s);
        }
        f.set(x, y0 + height, z, "campfire[lit=true,signal_fire=false]");
        f.set(x + 1, y0 + height, z, "stone_brick_slab[type=bottom]");
    }

    // ------------------------------------------------------------------ boathouse (naust)

    /** Stone boathouse with a turf roof, open towards the water (local +z), a boat inside. Floor = water at y -2. */
    static void boathouse(B.Frame f, int w, int d) {
        for (int x = -1; x <= w; x++)
            for (int z = -1; z < d; z++) {
                boolean side = x == -1 || x == w || z == -1;
                for (int y = -6; y <= 3; y++) {
                    if (side) f.set(x, y, z, y < -2 ? "cobblestone" : Build.stone());
                    else if (y <= -3) f.set(x, y, z, "gravel");
                    else if (y == -2) f.set(x, y, z, "water");
                    else f.set(x, y, z, "air");
                }
            }
        // curved turf roof
        int lo = -2, hi = w + 1, y = 3;
        while (lo <= hi) {
            for (int z = -2; z <= d; z++) {
                String edgeL = B.stairs("spruce", B.Dir.EAST), edgeR = B.stairs("spruce", B.Dir.WEST);
                boolean eave = lo == -2;
                f.set(lo, y, z, eave ? edgeL : lo == hi ? "moss_block" : Canvas.pick("moss_block", "grass_block", "moss_block"));
                f.set(hi, y, z, eave ? edgeR : lo == hi ? "moss_block" : Canvas.pick("moss_block", "grass_block", "moss_block"));
                if (!eave && Canvas.rnd() < 0.3) {
                    f.setIfAir(lo, y + 1, z, Canvas.pick("short_grass", "fern", "poppy"));
                    f.setIfAir(hi, y + 1, z, Canvas.pick("short_grass", "fern", "dandelion"));
                }
            }
            lo++;
            hi--;
            y++;
        }
        for (int x = 0; x < w; x++) {
            f.set(x, 3, d - 1, log("dark_oak_log", 'x'));
            int roofY = 3 + Math.min(x + 2, w + 1 - x);
            for (int yy = 4; yy < roofY; yy++) f.set(x, yy, d - 1, "dark_oak_planks");
        }
        f.set(w / 2, 2, d - 1, "lantern[hanging=true]");
        f.summon(w / 2.0 - 0.5, -1.4, d / 2.0, "spruce_boat", "{Tags:[\"nordia_spawn\"],Rotation:[0f,0f]}");
    }

    // ------------------------------------------------------------------ crane

    /** A treadwheel harbour crane: timber tower, jib over the water (local +z) and a big wheel. */
    static void crane(B.Frame f) {
        f.foundation(-2, -2, 2, 2, 0, "cobblestone");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) f.set(x, 0, z, "spruce_planks");
        for (int x : new int[] {-2, 2}) for (int z : new int[] {-2, 2}) f.fill(x, 1, z, x, 12, z, log("dark_oak_log", 'y'));
        for (int y = 4; y <= 12; y += 4)
            for (int k = -2; k <= 2; k++) {
                f.set(k, y, -2, log("stripped_dark_oak_log", 'x'));
                f.set(k, y, 2, log("stripped_dark_oak_log", 'x'));
                f.set(-2, y, k, log("stripped_dark_oak_log", 'z'));
                f.set(2, y, k, log("stripped_dark_oak_log", 'z'));
            }
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                int hs = Math.max(Math.abs(x), Math.abs(z));
                f.set(x, 13 + (3 - hs), z, hs == 0 ? "dark_oak_planks" : B.stairs("dark_oak",
                        Math.abs(x) >= Math.abs(z) ? (x < 0 ? B.Dir.EAST : B.Dir.WEST) : (z < 0 ? B.Dir.SOUTH : B.Dir.NORTH)));
            }
        // jib
        f.fill(0, 12, 3, 0, 12, 12, log("dark_oak_log", 'z'));
        f.fill(0, 13, 3, 0, 13, 6, "dark_oak_fence");
        f.fill(0, 7, 12, 0, 11, 12, "iron_chain[axis=y]");
        f.set(0, 6, 12, "barrel[facing=up]");
        // treadwheel on the side
        for (int a = 0; a < 360; a += 8) {
            double r = Math.toRadians(a);
            int y = 6 + (int) Math.round(Math.sin(r) * 4.5), z = (int) Math.round(Math.cos(r) * 4.5);
            f.set(-3, y, z, "spruce_planks");
            f.set(-4, y, z, "spruce_planks");
        }
        f.fill(-4, 6, 0, -2, 6, 0, log("dark_oak_log", 'x'));
    }

    // ------------------------------------------------------------------ beacon lighthouse

    /** Round stone tower with a gallery and a great fire cage on top. Local y 0 = ground. */
    static void beacon(B.Frame f) {
        f.foundation(-5, -5, 5, 5, 0, "cobblestone");
        int h = 22;
        for (int y = 0; y <= h; y++)
            for (int x = -4; x <= 4; x++)
                for (int z = -4; z <= 4; z++) {
                    double d = Math.hypot(x, z);
                    double r = 4.3 - y * 0.05;
                    if (d > r) continue;
                    f.set(x, y, z, d > r - 1.2 ? (y % 7 == 6 ? "polished_andesite" : Build.stone()) : "air");
                }
        f.set(0, 1, -4, "spruce_door[facing=north,half=lower,hinge=left]");
        f.set(0, 2, -4, "spruce_door[facing=north,half=upper,hinge=left]");
        for (int y = 6; y < h; y += 5) f.set(y % 2 == 0 ? 3 : -3, y, 0, "glass_pane");
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++) {
                double d = Math.hypot(x, z);
                if (d > 5.4) continue;
                f.set(x, h + 1, z, "spruce_planks");
                if (d > 4.5) f.set(x, h + 2, z, "spruce_fence");
            }
        // the fire cage
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                boolean corner = Math.abs(x) == 2 && Math.abs(z) == 2;
                if (corner) f.fill(x, h + 2, z, x, h + 7, z, log("dark_oak_log", 'y'));
                else if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                    f.set(x, h + 2, z, "magma_block");
                    f.set(x, h + 3, z, "campfire[lit=true,signal_fire=true]");
                }
            }
        f.set(0, h + 4, 0, "shroomlight");
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) f.set(x, h + 8, z, Math.abs(x) == 2 || Math.abs(z) == 2 ? B.slab("dark_oak") : "dark_oak_planks");
        f.set(0, h + 9, 0, "lightning_rod");
    }

    // ------------------------------------------------------------------ harbour arms

    /** Crenellated sea wall along the outer rim of the arms, boat sheds and fish racks on the inner side. */
    static void armDetails() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                if (!Terrain.arm(u, v) || Terrain.h(u, v) < 1) continue;
                boolean outer = false, inner = false;
                for (B.Dir d : B.Dir.values()) {
                    int nu = u + d.dx, nv = v + d.dz;
                    if (Terrain.arm(nu, nv) && Terrain.h(nu, nv) >= 1) continue;
                    if (Terrain.inBasin(nu, nv)) inner = true;
                    else outer = true;
                }
                int g = Terrain.h(u, v);
                if (outer && !inner && Terrain.radius(u, v) > 40) {
                    Canvas.fill(u, g + 1, v, u, g + 2, v, Terrain.masonry());
                    if (Math.floorMod(u + v, 2) == 0) Canvas.set(u, g + 3, v, "stone_brick_wall");
                    else Canvas.set(u, g + 3, v, "stone_brick_slab[type=bottom]");
                    Canvas.use(u, v, u, v);
                } else if (inner && Math.floorMod(u * 3 + v * 7, 11) == 0) {
                    Canvas.setIfAir(u, g + 1, v, "stone_brick_wall");
                }
            }
        // fish-drying racks and nets on both arms
        for (int sign : new int[] {-1, 1}) {
            for (int k = 0; k < 3; k++) {
                double a = Math.toRadians(180 - 40 - k * 22) * sign;
                int u = (int) Math.round(Math.sin(a) * 38), v = Terrain.CV + (int) Math.round(Math.cos(a) * 38);
                if (!Terrain.arm(u, v)) continue;
                int g = Terrain.h(u, v);
                for (int x = -2; x <= 2; x++) {
                    if (!Terrain.arm(u + x, v)) continue;
                    Canvas.setIfAir(u + x, g + 1, v, Math.abs(x) == 2 ? "spruce_fence" : "air");
                    Canvas.setIfAir(u + x, g + 2, v, Math.abs(x) == 2 ? "spruce_fence" : "cobweb");
                    Canvas.set(u + x, g + 3, v, "stripped_spruce_log[axis=x]");
                    if (Math.abs(x) < 2) Canvas.setIfAir(u + x, g + 1, v + 1, Canvas.pick("barrel[facing=up]", "dried_kelp_block", "air"));
                }
            }
        }
    }

    static final List<String[]> SAILS = List.of(new String[] {"red_wool", "white_wool"}, new String[] {"red_wool", "red_wool", "white_wool"},
            new String[] {"blue_wool", "white_wool"}, new String[] {"yellow_wool", "red_wool"});
}
