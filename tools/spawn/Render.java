import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Preview renderer: a small voxel ray tracer (sub-block shapes, sun + shadows, water reflection, glowing lights,
 * texture-like pixel noise) over the canvas plus the natural terrain around it. Perspective and orthographic cameras.
 */
final class Render {

    // ------------------------------------------------------------------ materials

    static final class Mat {
        int top, side;
        boolean invisible, water, glass, emissive, thin;
        double[][] boxes; // null = full cube
        String base;
    }

    static Mat[] MATS;

    static void prepare() {
        MATS = new Mat[Canvas.PALETTE.size()];
        for (int i = 0; i < MATS.length; i++) MATS[i] = mat(Canvas.PALETTE.get(i));
    }

    static final Map<String, int[]> COLOURS = new HashMap<>();

    static void c(String name, int top, int side) {
        COLOURS.put(name, new int[] {top, side});
    }

    static void c(String name, int both) {
        c(name, both, both);
    }

    static {
        c("stone", 0x7F7F7F); c("andesite", 0x88898A); c("polished_andesite", 0x858887); c("diorite", 0xBDBDBD);
        c("calcite", 0xDFE0DC); c("tuff", 0x6C6D66); c("tuff_bricks", 0x62675F); c("chiseled_tuff", 0x5E6359);
        c("polished_tuff", 0x626761); c("cobblestone", 0x7A7A7A); c("mossy_cobblestone", 0x6C7C58);
        c("stone_bricks", 0x7A7979); c("mossy_stone_bricks", 0x707A63); c("cracked_stone_bricks", 0x747372);
        c("chiseled_stone_bricks", 0x777676); c("smooth_stone", 0x9E9E9E); c("deepslate", 0x505055);
        c("cobbled_deepslate", 0x4D4D50); c("deepslate_bricks", 0x464648); c("deepslate_tiles", 0x363639);
        c("polished_deepslate", 0x484849); c("blackstone", 0x2A2328); c("polished_blackstone_bricks", 0x302A30);
        c("polished_blackstone", 0x353038); c("dirt", 0x866043); c("coarse_dirt", 0x77553A); c("rooted_dirt", 0x906A4B);
        c("podzol", 0x5B3F1D, 0x7A5A3A); c("grass_block", 0x6A9F45, 0x7E6C45); c("dirt_path", 0x947A41, 0x866043);
        c("mud", 0x3C393D); c("packed_mud", 0x8E6B50); c("mud_bricks", 0x89684F); c("clay", 0xA0A6B3);
        c("gravel", 0x837E7C); c("sand", 0xDBD3A0); c("moss_block", 0x596E2D); c("moss_carpet", 0x596E2D);
        c("spruce_log", 0x6B4E2F, 0x3A2717); c("stripped_spruce_log", 0x73593A); c("spruce_wood", 0x3A2717);
        c("stripped_spruce_wood", 0x73593A); c("dark_oak_log", 0x4F3219, 0x3C2E1A); c("dark_oak_wood", 0x3C2E1A);
        c("stripped_dark_oak_log", 0x604A30); c("stripped_dark_oak_wood", 0x604A30); c("oak_log", 0xA6844F, 0x6D5533);
        c("stripped_oak_log", 0xB0925A); c("birch_log", 0xC5B77B, 0xD8D7D2); c("spruce_planks", 0x735534);
        c("dark_oak_planks", 0x42291A); c("oak_planks", 0xA2834F); c("birch_planks", 0xC0AF79);
        c("white_wool", 0xE9ECEC); c("red_wool", 0xA12722); c("yellow_wool", 0xF8C527); c("blue_wool", 0x35399D);
        c("green_wool", 0x546D1B); c("black_wool", 0x151519); c("orange_wool", 0xF07613); c("brown_wool", 0x724728);
        c("light_gray_wool", 0x8E8E86); c("gray_wool", 0x3E4447); c("cyan_wool", 0x158991);
        c("red_terracotta", 0x8F3D2E); c("yellow_terracotta", 0xBA8523); c("white_terracotta", 0xD1B2A1);
        c("light_blue_terracotta", 0x716C89); c("brown_terracotta", 0x4D3323); c("orange_terracotta", 0xA15325);
        c("terracotta", 0x985E43); c("cyan_terracotta", 0x575B5B); c("green_terracotta", 0x4C532A);
        c("black_terracotta", 0x251710); c("gray_terracotta", 0x392A23); c("light_gray_terracotta", 0x876A61);
        c("white_concrete", 0xCFD5D6); c("red_concrete", 0x8E2121); c("black_concrete", 0x080A0F);
        c("waxed_oxidized_copper", 0x52A284); c("waxed_oxidized_cut_copper", 0x4F9A7E); c("gold_block", 0xF6D03D);
        c("iron_block", 0xDCDCDC); c("smooth_quartz", 0xECE6DF); c("quartz_block", 0xECE6DF); c("quartz_pillar", 0xE9E3D8);
        c("chiseled_quartz_block", 0xE7E1D5); c("bricks", 0x96614E); c("glass", 0xC0E0F0); c("glass_pane", 0xB0D0E0);
        c("sea_lantern", 0xC8E4DC); c("shroomlight", 0xF5A04B); c("glowstone", 0xF0CC80); c("lantern", 0xF5C060);
        c("soul_lantern", 0x7FD8E0); c("campfire", 0xF09040); c("magma_block", 0xB0501A);
        c("spruce_leaves", 0x3B5E3B); c("birch_leaves", 0x6E9A45); c("oak_leaves", 0x4A7A2C); c("dark_oak_leaves", 0x3D6A22);
        c("azalea_leaves", 0x6E9A3A); c("flowering_azalea_leaves", 0x86A050); c("cherry_leaves", 0xE7B3C7);
        c("mangrove_leaves", 0x5E8A2A); c("hay_block", 0xB5991F, 0xA6891B); c("barrel", 0x86643A);
        c("chest", 0xA0722F); c("bookshelf", 0x6B5334); c("crafting_table", 0x7B5D38); c("decorated_pot", 0x8B5A3C);
        c("bell", 0xE8C046); c("anvil", 0x444444); c("furnace", 0x6E6E6E); c("smoker", 0x5E5A55); c("cauldron", 0x414141);
        c("water", 0x2F5FC0); c("iron_bars", 0x6E6E6E); c("iron_chain", 0x3E4452); c("cobweb", 0xE0E0E0);
        c("pumpkin", 0xC57718); c("melon", 0x6F9A2B); c("dried_kelp_block", 0x3A4A2A); c("target", 0xE0C8B0);
        c("short_grass", 0x5E9A3A); c("tall_grass", 0x5E9A3A); c("fern", 0x4E8A32); c("large_fern", 0x4E8A32);
        c("bush", 0x4F8A30); c("firefly_bush", 0x5A7A30); c("seagrass", 0x3A7A2A); c("kelp", 0x3A7A2A);
        c("poppy", 0xC42A22); c("red_tulip", 0xC42A22); c("dandelion", 0xF1D531); c("cornflower", 0x4B6BD6);
        c("blue_orchid", 0x3AA0D6); c("oxeye_daisy", 0xEDEDED); c("white_tulip", 0xEDEDED); c("azure_bluet", 0xE8E8F0);
        c("lily_of_the_valley", 0xF0F0F0); c("allium", 0xB06AE0); c("pink_tulip", 0xE8A0C0); c("orange_tulip", 0xE8801C);
        c("pink_petals", 0xE7A5C8); c("wildflowers", 0xE8D060); c("sweet_berry_bush", 0x3F6A2A); c("lily_pad", 0x3F8A2A);
        c("cave_vines", 0x5A7A2A); c("cave_vines_plant", 0x5A7A2A); c("vine", 0x3F6A1A); c("glow_lichen", 0x7A9A8A);
        c("resin_bricks", 0xC7582A); c("resin_block", 0xD8691E); c("pale_oak_planks", 0xE3D9D3); c("mangrove_planks", 0x773231);
        c("stripped_birch_log", 0xC4B07B); c("light_gray_concrete", 0x7D7D73); c("gray_concrete", 0x36393D);
        c("polished_deepslate", 0x484849); c("smooth_sandstone", 0xDBD3A0); c("smooth_stone_slab", 0x9E9E9E);
        c("prismarine_bricks", 0x63AB9E); c("prismarine", 0x63A396); c("iron_trapdoor", 0xC8C8C8); c("cherry_leaves", 0xE7B3C7);
        c("stone_path", 0x9A7F48); c("lilac", 0xB58FB6); c("wheat", 0xC7B04A); c("carrots", 0x4E9A2A); c("potatoes", 0x4E9A2A);
        c("beetroots", 0x4E9A2A); c("farmland", 0x5B3A1E); c("green_terracotta", 0x4C532A); c("cyan_terracotta", 0x575B5B);
        c("mud_bricks", 0x89684F); c("stripped_dark_oak_wood", 0x604A30); c("white_carpet", 0xE9ECEC);
        c("candle", 0xE8D8B0); c("white_candle", 0xF0F0F0); c("light", 0);
    }

    static Mat mat(String state) {
        Mat m = new Mat();
        String b = B.base(state);
        m.base = b;
        int[] col = colour(b);
        m.top = col[0];
        m.side = col[1];
        m.invisible = b.equals("air") || b.equals("light") || b.equals("barrier");
        m.water = b.equals("water");
        m.glass = b.contains("glass") && !b.contains("pane");
        m.emissive = b.contains("lantern") || b.equals("shroomlight") || b.equals("glowstone") || b.equals("campfire")
                     || b.equals("magma_block") || b.equals("cave_vines") || b.equals("cave_vines_plant") && state.contains("berries=true")
                     || b.contains("candle") && state.contains("lit=true") || b.equals("froglight") || b.contains("froglight");
        m.boxes = shape(state, b);
        m.thin = m.boxes != null;
        return m;
    }

    static int[] colour(String b) {
        int[] c = COLOURS.get(b);
        if (c != null) return c;
        for (String suffix : new String[] {"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door",
                "_button", "_pressure_plate", "_hanging_sign", "_wall_hanging_sign", "_sign", "_wall_sign"}) {
            if (b.endsWith(suffix)) {
                String m = b.substring(0, b.length() - suffix.length());
                for (String cand : new String[] {m, m + "s", m + "_planks", m + "_block", m.replace("_brick", "_bricks"),
                        m.replace("_tile", "_tiles"), m.replace("brick", "bricks")}) {
                    if (COLOURS.containsKey(cand)) return COLOURS.get(cand);
                }
            }
        }
        if (b.startsWith("potted_")) return new int[] {0x8B5A3C, 0x8B5A3C};
        if (b.endsWith("_carpet")) return colour(b.replace("_carpet", "_wool"));
        if (b.endsWith("_banner") || b.endsWith("_wall_banner")) return colour(b.replace("_wall_banner", "_wool").replace("_banner", "_wool"));
        if (b.endsWith("_candle")) return new int[] {0xE0C890, 0xE0C890};
        if (b.contains("copper")) return new int[] {0x4F9A7E, 0x4F9A7E};
        if (b.contains("quartz")) return new int[] {0xEAE3D9, 0xEAE3D9};
        if (b.contains("bars")) return new int[] {0x6E6E6E, 0x6E6E6E};
        return new int[] {0xB0A090, 0xB0A090};
    }

    static double[][] shape(String state, String b) {
        if (b.endsWith("_slab")) {
            if (state.contains("type=double")) return null;
            return state.contains("type=top") ? new double[][] {{0, .5, 0, 1, 1, 1}} : new double[][] {{0, 0, 0, 1, .5, 1}};
        }
        if (b.endsWith("_stairs")) {
            boolean top = state.contains("half=top");
            double[] slab = top ? new double[] {0, .5, 0, 1, 1, 1} : new double[] {0, 0, 0, 1, .5, 1};
            double y0 = top ? 0 : .5, y1 = top ? .5 : 1;
            double[] back = switch (prop(state, "facing")) {
                case "north" -> new double[] {0, y0, 0, 1, y1, .5};
                case "south" -> new double[] {0, y0, .5, 1, y1, 1};
                case "east" -> new double[] {.5, y0, 0, 1, y1, 1};
                default -> new double[] {0, y0, 0, .5, y1, 1};
            };
            return new double[][] {slab, back};
        }
        if (b.endsWith("_fence") || b.endsWith("_wall") || b.equals("iron_bars") || b.endsWith("_pane") || b.equals("glass_pane")
            || b.equals("iron_chain") || b.contains("lightning_rod") || b.equals("end_rod")) {
            double r = b.endsWith("_wall") ? .25 : b.equals("iron_chain") ? .06 : .125;
            double h = b.endsWith("_wall") ? 1 : 1;
            return new double[][] {{.5 - r, 0, .5 - r, .5 + r, h, .5 + r}};
        }
        if (b.endsWith("_fence_gate")) return new double[][] {{0, .3, .4, 1, .95, .6}};
        if (b.endsWith("_trapdoor")) {
            boolean open = state.contains("open=true");
            if (!open) return state.contains("half=top") ? new double[][] {{0, .81, 0, 1, 1, 1}} : new double[][] {{0, 0, 0, 1, .19, 1}};
            return switch (prop(state, "facing")) {
                case "north" -> new double[][] {{0, 0, .81, 1, 1, 1}};
                case "south" -> new double[][] {{0, 0, 0, 1, 1, .19}};
                case "east" -> new double[][] {{0, 0, 0, .19, 1, 1}};
                default -> new double[][] {{.81, 0, 0, 1, 1, 1}};
            };
        }
        if (b.endsWith("_door")) {
            return switch (prop(state, "facing")) {
                case "north" -> new double[][] {{0, 0, .81, 1, 1, 1}};
                case "south" -> new double[][] {{0, 0, 0, 1, 1, .19}};
                case "east" -> new double[][] {{0, 0, 0, .19, 1, 1}};
                default -> new double[][] {{.81, 0, 0, 1, 1, 1}};
            };
        }
        if (b.endsWith("_carpet") || b.equals("moss_carpet") || b.equals("pink_petals") || b.equals("leaf_litter")
            || b.equals("wildflowers") || b.equals("lily_pad")) return new double[][] {{0, 0, 0, 1, .07, 1}};
        if (b.contains("lantern")) return state.contains("hanging=true") ? new double[][] {{.31, .06, .31, .69, .56, .69}}
                : new double[][] {{.31, 0, .31, .69, .5, .69}};
        if (b.equals("campfire")) return new double[][] {{0, 0, 0, 1, .44, 1}};
        if (b.equals("bell")) return new double[][] {{.25, .2, .25, .75, .8, .75}};
        if (b.equals("flower_pot") || b.startsWith("potted_")) return new double[][] {{.31, 0, .31, .69, .45, .69}};
        if (b.contains("candle")) return new double[][] {{.35, 0, .35, .65, .4, .65}};
        if (b.contains("_sign") || b.endsWith("_banner")) return new double[][] {{.1, .3, .45, .9, .9, .55}};
        if (B.isPlant(b) || b.equals("cobweb") || b.equals("seagrass")) {
            double hgt = b.contains("tall") || b.contains("large") || b.contains("bush") ? .95 : .6;
            return new double[][] {{.25, 0, .25, .75, hgt, .75}};
        }
        if (b.equals("lectern") || b.endsWith("_button") || b.endsWith("pressure_plate")) return new double[][] {{.2, 0, .2, .8, .8, .8}};
        return null;
    }

    static String prop(String state, String key) {
        int i = state.indexOf(key + "=");
        if (i < 0) return "";
        int j = state.indexOf(i < 0 ? "," : ",", i);
        int k = state.indexOf(']', i);
        int end = j < 0 ? k : Math.min(j, k);
        return state.substring(i + key.length() + 1, end);
    }

    // ------------------------------------------------------------------ context (the real world around the canvas)

    static int CTX = 200;
    /** When set, the canvas is ignored and everything is read from the world (renders what was actually built). */
    static boolean WORLD_ONLY = false;
    static final java.util.concurrent.ConcurrentHashMap<String, Mat> CTX_MATS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Loads every chunk the renderer may read, so later parallel reads never modify the reader cache. */
    static void prepareContext() {
        if (Terrain.nat == null) return;
        for (int u = Canvas.MINX - CTX; u <= Canvas.MAXX + CTX + 15; u += 16)
            for (int v = Canvas.MINZ - CTX; v <= Canvas.MAXZ + CTX + 15; v += 16)
                Terrain.nat.surface(Terrain.worldX + u, Terrain.worldZ + v);
        for (String b : new String[] {"air", "water", "cave_air"}) CTX_MATS.computeIfAbsent(b, Render::mat);
    }

    static Mat ctxMat(int x, int y, int z) {
        int wxw = Terrain.worldX + x, wzw = Terrain.worldZ + z;
        if (wxw >= -648 && wxw <= -521 && wzw >= 236 && wzw <= 249) { // the first spawn draft, removed in the world
            if (y > Canvas.SEA) return null;
            return CTX_MATS.computeIfAbsent(y > -14 ? "water" : "sand", Render::mat);
        }
        if (Terrain.nat == null) return y <= -1 ? CTX_MATS.computeIfAbsent("grass_block", Render::mat) : null;
        if (x < Canvas.MINX - CTX || x > Canvas.MAXX + CTX || z < Canvas.MINZ - CTX || z > Canvas.MAXZ + CTX) return null;
        String b = Terrain.nat.block(Terrain.worldX + x, y + 64, Terrain.worldZ + z);
        if (b == null) return null;
        Mat m = CTX_MATS.get(b);
        if (m == null) m = CTX_MATS.computeIfAbsent(b, Render::mat);
        return m.invisible || b.equals("cave_air") ? null : m;
    }

    // ------------------------------------------------------------------ ray tracing

    static final double[] SUN = norm(new double[] {-0.45, 0.62, 0.64});

    static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / l, v[1] / l, v[2] / l};
    }

    record Hit(double t, int nx, int ny, int nz, int x, int y, int z, Mat m) {}

    static Mat cell(int x, int y, int z) {
        if (WORLD_ONLY) return y < -40 || y > 200 ? null : ctxMat(x, y, z);
        if (Canvas.in(x, y, z)) {
            Mat m = MATS[Canvas.VOX[Canvas.idx(x, y, z)]];
            return m.invisible ? null : m;
        }
        if (Canvas.inXZ(x, z)) return null; // above or below the canvas box: open air
        return ctxMat(x, y, z);
    }

    static boolean fenceLike(String b) {
        return b.endsWith("_fence") || b.endsWith("_wall") || b.equals("iron_bars") || b.endsWith("pane");
    }

    /** DDA march through the voxel grid. Returns null on miss. */
    static Hit trace(double ox, double oy, double oz, double dx, double dy, double dz, double maxT, boolean stopAtWater,
                     boolean ignoreGlass) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tx = ((sx > 0 ? x + 1 - ox : ox - x)) * tdx, ty = ((sy > 0 ? y + 1 - oy : oy - y)) * tdy,
                tz = ((sz > 0 ? z + 1 - oz : oz - z)) * tdz;
        double t = 0;
        int nx = 0, ny = 0, nz = 0;
        while (t < maxT) {
            int top = WORLD_ONLY ? 200 : Canvas.MAXY + 2;
            if (y < Canvas.MINY - 30 || y > top && dy > 0) return null;
            if (y <= top) {
                Mat m = cell(x, y, z);
                if (m != null && !(ignoreGlass && (m.glass || m.base.endsWith("pane")))) {
                    if (m.water) {
                        if (stopAtWater) return new Hit(t, nx, ny, nz, x, y, z, m);
                    } else if (m.boxes == null && !fenceLike(m.base)) {
                        return new Hit(t, nx, ny, nz, x, y, z, m);
                    } else {
                        double[][] boxes = fenceLike(m.base) ? fenceBoxes(m, x, y, z) : m.boxes;
                        double best = Double.MAX_VALUE;
                        int bnx = 0, bny = 0, bnz = 0;
                        for (double[] bx : boxes) {
                            double[] r = slabTest(ox, oy, oz, dx, dy, dz, x + bx[0], y + bx[1], z + bx[2], x + bx[3], y + bx[4], z + bx[5]);
                            if (r != null && r[0] < best && r[0] >= t - 1e-6) {
                                best = r[0];
                                bnx = (int) r[1];
                                bny = (int) r[2];
                                bnz = (int) r[3];
                            }
                        }
                        if (best < Double.MAX_VALUE) return new Hit(best, bnx, bny, bnz, x, y, z, m);
                    }
                }
            }
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += tdx;
                nx = -sx;
                ny = 0;
                nz = 0;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += tdy;
                nx = 0;
                ny = -sy;
                nz = 0;
            } else {
                z += sz;
                t = tz;
                tz += tdz;
                nx = 0;
                ny = 0;
                nz = -sz;
            }
        }
        return null;
    }

    static double[][] fenceBoxes(Mat m, int x, int y, int z) {
        double r = m.base.endsWith("_wall") ? .25 : .125;
        double[][] out = new double[5][];
        int n = 0;
        out[n++] = new double[] {.5 - r, 0, .5 - r, .5 + r, 1, .5 + r};
        int[][] ds = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : ds) {
            Mat o = cell(x + d[0], y, z + d[1]);
            if (o == null || o.water || B.isPlant(o.base)) continue;
            if (!(o.boxes == null || fenceLike(o.base))) continue;
            double a = m.base.endsWith("_wall") ? .19 : .06;
            double y0 = m.base.endsWith("_fence") ? .4 : 0, y1 = m.base.endsWith("_fence") ? .9 : (m.base.endsWith("_wall") ? .85 : 1);
            if (d[0] == 1) out[n++] = new double[] {.5, y0, .5 - a, 1, y1, .5 + a};
            if (d[0] == -1) out[n++] = new double[] {0, y0, .5 - a, .5, y1, .5 + a};
            if (d[1] == 1) out[n++] = new double[] {.5 - a, y0, .5, .5 + a, y1, 1};
            if (d[1] == -1) out[n++] = new double[] {.5 - a, y0, 0, .5 + a, y1, .5};
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static double[] slabTest(double ox, double oy, double oz, double dx, double dy, double dz, double x0, double y0, double z0,
                             double x1, double y1, double z1) {
        double tmin = -1e9, tmax = 1e9;
        int axis = -1, sign = 0;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {x0, y0, z0}, hi = {x1, y1, z1};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a]) return null;
                continue;
            }
            double t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            int s = -1;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
                s = 1;
            }
            if (t1 > tmin) {
                tmin = t1;
                axis = a;
                sign = s;
            }
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return null;
        }
        if (tmax < 0 || axis < 0) return null;
        double[] r = {tmin, 0, 0, 0};
        r[1 + axis] = sign;
        return r;
    }

    static double[] sky(double dx, double dy, double dz) {
        double t = Math.max(0, dy);
        double[] c = {0.66 + (0.32 - 0.66) * t, 0.76 + (0.54 - 0.76) * t, 0.90 + (0.86 - 0.90) * t};
        double sun = Math.max(0, dx * SUN[0] + dy * SUN[1] + dz * SUN[2]);
        double glow = Math.pow(sun, 64) * 1.5 + Math.pow(sun, 6) * 0.15;
        return new double[] {c[0] + glow, c[1] + glow * 0.9, c[2] + glow * 0.7};
    }

    static double texture(Hit h, double px, double py, double pz) {
        int tx = (int) Math.floor(px * 16), ty = (int) Math.floor(py * 16), tz = (int) Math.floor(pz * 16);
        long k = tx * 73856093L ^ ty * 19349663L ^ tz * 83492791L ^ (h.m().base.hashCode() * 2654435761L);
        k = (k ^ (k >>> 15)) * 2246822519L;
        return 0.88 + ((k >>> 20) & 255) / 255.0 * 0.24;
    }

    /** fogStart: distance travelled before the scene starts (orthographic cameras start far away). */
    static double[] shade(double ox, double oy, double oz, double dx, double dy, double dz, int depth, double fogStart) {
        Hit h = trace(ox, oy, oz, dx, dy, dz, 1100, true, false);
        if (h == null) return sky(dx, dy, dz);
        double px = ox + dx * h.t(), py = oy + dy * h.t(), pz = oz + dz * h.t();
        Mat m = h.m();
        if (m.water && depth < 2) {
            double[] refl = shade(px, py + 1e-3, pz, dx, -dy, dz, depth + 1, 0);
            double[] deep = {0.10, 0.22, 0.33};
            double fres = 0.22 + 0.6 * Math.pow(1 - Math.abs(dy), 4);
            double[] c = {refl[0] * fres + deep[0] * (1 - fres), refl[1] * fres + deep[1] * (1 - fres), refl[2] * fres + deep[2] * (1 - fres)};
            return fog(c, h.t() - fogStart, dx, dz);
        }
        int rgb = h.ny() > 0 ? m.top : m.side;
        double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
        double tex = texture(h, px - h.nx() * 1e-4, py - h.ny() * 1e-4, pz - h.nz() * 1e-4);
        r *= tex;
        g *= tex;
        b *= tex;
        if (m.emissive) return new double[] {Math.min(1.6, r * 1.5), Math.min(1.6, g * 1.45), Math.min(1.6, b * 1.3)};
        double ndl = h.nx() * SUN[0] + h.ny() * SUN[1] + h.nz() * SUN[2];
        double light = 0.40 + (h.ny() > 0 ? 0.08 : h.ny() < 0 ? -0.12 : 0);
        if (ndl > 0) {
            Hit s = trace(px + h.nx() * 1e-3 + SUN[0] * 1e-3, py + h.ny() * 1e-3 + SUN[1] * 1e-3, pz + h.nz() * 1e-3 + SUN[2] * 1e-3,
                    SUN[0], SUN[1], SUN[2], 160, false, true);
            if (s == null) light += 0.78 * ndl;
        }
        double[] c = {r * light * 1.05, g * light * 0.99, b * light * 0.92};
        return fog(c, h.t() - fogStart, dx, dz);
    }

    static double[] fog(double[] c, double dist, double dx, double dz) {
        double f = Math.max(0, Math.min(1, (dist - 60) / 700.0));
        f = f * f;
        double[] sk = sky(dx, 0.05, dz);
        return new double[] {c[0] * (1 - f) + sk[0] * f, c[1] * (1 - f) + sk[1] * f, c[2] * (1 - f) + sk[2] * f};
    }

    static int toRgb(double[] c) {
        int r = (int) Math.round(255 * Math.min(1, Math.pow(Math.max(0, c[0]), 0.9)));
        int g = (int) Math.round(255 * Math.min(1, Math.pow(Math.max(0, c[1]), 0.9)));
        int b = (int) Math.round(255 * Math.min(1, Math.pow(Math.max(0, c[2]), 0.9)));
        return (r << 16) | (g << 8) | b;
    }

    /** Perspective view from (x,y,z) looking at (tx,ty,tz). */
    static void view(Path file, int w, int h, double x, double y, double z, double tx, double ty, double tz, double fovDeg)
            throws IOException {
        double[] f = norm(new double[] {tx - x, ty - y, tz - z});
        double[] right = norm(new double[] {-f[2], 0, f[0]});
        double[] up = {right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]};
        double scale = Math.tan(Math.toRadians(fovDeg) / 2);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        IntStream.range(0, h).parallel().forEach(py -> {
            for (int px = 0; px < w; px++) {
                double[] acc = new double[3];
                for (int s = 0; s < 4; s++) {
                    double sx = (px + (s & 1) * 0.5 + 0.25) / w * 2 - 1, sy = 1 - (py + (s >> 1) * 0.5 + 0.25) / h * 2;
                    double ax = sx * scale * w / h, ay = sy * scale;
                    double[] d = norm(new double[] {f[0] + right[0] * ax + up[0] * ay, f[1] + right[1] * ax + up[1] * ay,
                            f[2] + right[2] * ax + up[2] * ay});
                    double[] c = shade(x, y, z, d[0], d[1], d[2], 0, 0);
                    acc[0] += c[0] / 4;
                    acc[1] += c[1] / 4;
                    acc[2] += c[2] / 4;
                }
                img.setRGB(px, py, toRgb(acc));
            }
        });
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    /** Orthographic view centred on (cx,cy,cz), seen from the given yaw (degrees, 0 = from the south). */
    static void ortho(Path file, int w, int h, double cx, double cy, double cz, double yawDeg, double pitchDeg, double blocksWide)
            throws IOException {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double[] f = norm(new double[] {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)});
        double[] right = Math.abs(f[1]) > 0.999 ? new double[] {1, 0, 0} : norm(new double[] {-f[2], 0, f[0]});
        double[] up = {right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]};
        double pix = blocksWide / w;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        IntStream.range(0, h).parallel().forEach(py -> {
            for (int px = 0; px < w; px++) {
                double[] acc = new double[3];
                for (int s = 0; s < 4; s++) {
                    double ox = (px + (s & 1) * 0.5 + 0.25 - w / 2.0) * pix, oy = (h / 2.0 - py - (s >> 1) * 0.5 - 0.25) * pix;
                    double sx = cx + right[0] * ox + up[0] * oy - f[0] * 400, sy = cy + right[1] * ox + up[1] * oy - f[1] * 400,
                            sz = cz + right[2] * ox + up[2] * oy - f[2] * 400;
                    double[] c = shade(sx, sy, sz, f[0], f[1], f[2], 0, 400);
                    acc[0] += c[0] / 4;
                    acc[1] += c[1] / 4;
                    acc[2] += c[2] / 4;
                }
                img.setRGB(px, py, toRgb(acc));
            }
        });
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }
}
