import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Generates the NORDIA spawn town "Nordhamn" — a Nordic harbour town on an island — as a vanilla data pack.
 *
 * <p>The town is modelled in an in-memory voxel canvas (relative coordinates, origin = centre of the market square,
 * y 0 = the first air layer above the ground, water surface at y -2), then compiled into merged {@code fill} commands
 * split over several ticks. It also renders preview images so the design can be reviewed without a game client.
 *
 * <pre>
 * java tools/spawn/Nordhamn.java [outDir] [blockList]
 *   outDir     default tools/spawn/build  → datapack/nordia_spawn + preview/*.png
 *   blockList  optional file with one valid block id per line; every block used is validated against it
 * In game:  /execute positioned X 64 Z run function nordia:spawn/build   (X Z = open ocean near a coast)
 * </pre>
 *
 * This is world decoration only: no plugin code, no economy, no database.
 */
public final class Nordhamn {

    // ------------------------------------------------------------------ canvas

    static final int MINX = -64, MAXX = 63, MINZ = -64, MAXZ = 63, MINY = -24, MAXY = 60;
    static final int SX = MAXX - MINX + 1, SY = MAXY - MINY + 1, SZ = MAXZ - MINZ + 1;
    static final short[] VOX = new short[SX * SY * SZ];
    static final List<String> PALETTE = new ArrayList<>();
    static final Map<String, Short> PALETTE_INDEX = new HashMap<>();
    static final List<String> ENTITIES = new ArrayList<>();
    static final boolean[][] LAND = new boolean[SX][SZ];
    static final int[][] DIST = new int[SX][SZ];
    static final boolean[][] PAVED = new boolean[SX][SZ];
    static final boolean[][] OCCUPIED = new boolean[SX][SZ];
    static final boolean[][] RESERVED = new boolean[SX][SZ];
    static final Random RNG = new Random(1848);

    static {
        id("air");
    }

    static short id(String state) {
        return PALETTE_INDEX.computeIfAbsent(state, k -> {
            PALETTE.add(k);
            return (short) (PALETTE.size() - 1);
        });
    }

    static boolean in(int x, int y, int z) {
        return x >= MINX && x <= MAXX && y >= MINY && y <= MAXY && z >= MINZ && z <= MAXZ;
    }

    static boolean inXZ(int x, int z) {
        return x >= MINX && x <= MAXX && z >= MINZ && z <= MAXZ;
    }

    static int idx(int x, int y, int z) {
        return ((y - MINY) * SZ + (z - MINZ)) * SX + (x - MINX);
    }

    static void set(int x, int y, int z, String state) {
        if (in(x, y, z)) VOX[idx(x, y, z)] = id(state);
    }

    static String get(int x, int y, int z) {
        return in(x, y, z) ? PALETTE.get(VOX[idx(x, y, z)]) : "air";
    }

    static boolean isAir(int x, int y, int z) {
        return in(x, y, z) && VOX[idx(x, y, z)] == 0;
    }

    static void fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, state);
    }

    static void mark(boolean[][] mask, int x0, int z0, int x1, int z1) {
        for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                if (inXZ(x, z)) mask[x - MINX][z - MINZ] = true;
    }

    static boolean at(boolean[][] mask, int x, int z) {
        return inXZ(x, z) && mask[x - MINX][z - MINZ];
    }

    /** Claims a building footprint (plus a margin kept free of trees) and warns about overlaps. */
    static void claim(String name, int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++)
                if (at(OCCUPIED, x, z)) {
                    System.out.println("WARN overlap: " + name + " at " + x + "," + z);
                    z = z1 + 1;
                    break;
                }
        mark(OCCUPIED, x0, z0, x1, z1);
        mark(RESERVED, x0 - 2, z0 - 2, x1 + 2, z1 + 2);
    }

    static double rnd() {
        return RNG.nextDouble();
    }

    static <T> T pick(List<T> options) {
        return options.get(RNG.nextInt(options.size()));
    }

    // ------------------------------------------------------------------ block helpers

    enum Dir {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        final int dx, dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        String n() {
            return name().toLowerCase(Locale.ROOT);
        }

        Dir opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        boolean alongX() {
            return this == NORTH || this == SOUTH; // a wall facing north/south runs along x
        }
    }

    static String stairs(String mat, Dir facing) {
        return mat + "_stairs[facing=" + facing.n() + ",half=bottom]";
    }

    static String stairsTop(String mat, Dir facing) {
        return mat + "_stairs[facing=" + facing.n() + ",half=top]";
    }

    static String slab(String mat, boolean top) {
        return mat + "_slab[type=" + (top ? "top" : "bottom") + "]";
    }

    static String shutter(String mat, Dir outward) {
        return mat + "_trapdoor[facing=" + outward.n() + ",half=bottom,open=true]";
    }

    static String log(String mat, char axis) {
        return mat + "[axis=" + axis + "]";
    }

    static void door(int x, int y, int z, String mat, Dir facing, String hinge) {
        set(x, y, z, mat + "_door[facing=" + facing.n() + ",half=lower,hinge=" + hinge + "]");
        set(x, y + 1, z, mat + "_door[facing=" + facing.n() + ",half=upper,hinge=" + hinge + "]");
    }

    static String cobble() {
        double r = rnd();
        if (r < 0.36) return "stone_bricks";
        if (r < 0.50) return "cobblestone";
        if (r < 0.64) return "andesite";
        if (r < 0.77) return "polished_andesite";
        if (r < 0.88) return "cracked_stone_bricks";
        if (r < 0.95) return "mossy_stone_bricks";
        return "tuff";
    }

    static String flower() {
        return pick(List.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium",
                "lily_of_the_valley", "red_tulip", "white_tulip", "blue_orchid"));
    }

    // ------------------------------------------------------------------ terrain

    static boolean basin(int x, int z) {
        return z >= 31 && z <= 47 && Math.abs(x) <= 46;
    }

    static boolean land(int x, int z) {
        return at(LAND, x, z);
    }

    static boolean quay(int x, int z) {
        if (!land(x, z)) return false;
        for (Dir d : Dir.values()) if (!land(x + d.dx, z + d.dz) && basin(x + d.dx, z + d.dz)) return true;
        return false;
    }

    static void terrain() {
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++) {
                double nx = x / 61.0, nz = (z + 4) / 58.0;
                double a = Math.atan2(nz, nx);
                double r = Math.pow(Math.abs(nx), 4) + Math.pow(Math.abs(nz), 4);
                double wobble = 1 + 0.05 * Math.sin(a * 5 + 0.7) + 0.03 * Math.sin(a * 13 + 2.1);
                boolean l = r * wobble <= 1.0;
                int ax = Math.abs(x);
                if (z >= 26 && z <= 46 && ax >= 44 && ax <= 60 && !(z > 42 && ax > 57) && !(z > 44 && ax > 54)) l = true;
                if (basin(x, z)) l = false;
                LAND[x - MINX][z - MINZ] = l;
            }
        distanceTransform();
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++) {
                int d = DIST[x - MINX][z - MINZ];
                if (land(x, z)) {
                    if (quay(x, z)) {
                        fill(x, MINY, z, x, -10, z, "stone");
                        for (int y = -9; y <= -2; y++)
                            set(x, y, z, rnd() < 0.2 ? "mossy_stone_bricks" : rnd() < 0.2 ? "cracked_stone_bricks" : "stone_bricks");
                        set(x, -1, z, "polished_andesite");
                    } else if (d <= 1) {
                        fill(x, MINY, z, x, -5, z, "stone");
                        fill(x, -4, z, x, -2, z, "sand");
                    } else if (d <= 3) {
                        fill(x, MINY, z, x, -5, z, "stone");
                        fill(x, -4, z, x, -1, z, "sand");
                    } else {
                        fill(x, MINY, z, x, -6, z, "stone");
                        fill(x, -5, z, x, -2, z, "dirt");
                        set(x, -1, z, "grass_block");
                    }
                } else {
                    int bed = basin(x, z) ? -9 : -3 - Math.min(12, (int) Math.round(d * 1.1));
                    fill(x, MINY, z, x, bed - 1, z, "stone");
                    double r = rnd();
                    set(x, bed, z, r < 0.65 ? "sand" : r < 0.9 ? "gravel" : "clay");
                    fill(x, bed + 1, z, x, -2, z, "water");
                    if (!basin(x, z) && d > 2 && rnd() < 0.05 && bed + 1 < -2) set(x, bed + 1, z, "seagrass");
                }
            }
        // Boulders along the shore.
        for (int i = 0; i < 70; i++) {
            int x = MINX + RNG.nextInt(SX), z = MINZ + RNG.nextInt(SZ);
            if (!land(x, z) || DIST[x - MINX][z - MINZ] > 2 || quay(x, z)) continue;
            int top = get(x, -1, z).equals("sand") ? 0 : -1;
            String m = pick(List.of("mossy_cobblestone", "andesite", "cobblestone", "tuff", "stone"));
            set(x, top, z, m);
            if (rnd() < 0.5) set(x + 1, top, z, pick(List.of("andesite", "mossy_cobblestone")));
            if (rnd() < 0.3) set(x, top + 1, z, "mossy_cobblestone");
        }
    }

    static void distanceTransform() {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++) DIST[x - MINX][z - MINZ] = Integer.MAX_VALUE;
        // land: distance to nearest water; water: distance to nearest land — seed with the cells on each boundary
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++)
                for (Dir d : Dir.values()) {
                    int nx = x + d.dx, nz = z + d.dz;
                    if (inXZ(nx, nz) && land(nx, nz) != land(x, z)) {
                        DIST[x - MINX][z - MINZ] = 1;
                        queue.add(new int[] {x, z});
                        break;
                    }
                }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int dist = DIST[c[0] - MINX][c[1] - MINZ];
            for (Dir d : Dir.values()) {
                int nx = c[0] + d.dx, nz = c[1] + d.dz;
                if (!inXZ(nx, nz) || land(nx, nz) != land(c[0], c[1])) continue;
                if (DIST[nx - MINX][nz - MINZ] > dist + 1) {
                    DIST[nx - MINX][nz - MINZ] = dist + 1;
                    queue.add(new int[] {nx, nz});
                }
            }
        }
    }

    // ------------------------------------------------------------------ streets

    static void pave(int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (!land(x, z) || quay(x, z)) continue;
                set(x, -1, z, cobble());
                mark(PAVED, x, z, x, z);
            }
        mark(RESERVED, x0, z0, x1, z1);
    }

    static void lane(int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (!land(x, z) || !get(x, -1, z).equals("grass_block")) continue;
                set(x, -1, z, rnd() < 0.85 ? "dirt_path" : "coarse_dirt");
                mark(PAVED, x, z, x, z);
            }
        mark(RESERVED, x0, z0, x1, z1);
    }

    /** Walks from a door step outwards until it reaches a paved cell, laying a garden path. */
    static void doorPath(int x, int z, Dir d) {
        List<int[]> cells = new ArrayList<>();
        for (int k = 1; k <= 30; k++) {
            int cx = x + d.dx * k, cz = z + d.dz * k;
            if (at(PAVED, cx, cz)) {
                for (int[] c : cells) {
                    if (get(c[0], -1, c[1]).equals("grass_block")) set(c[0], -1, c[1], "dirt_path");
                    mark(PAVED, c[0], c[1], c[0], c[1]);
                    mark(RESERVED, c[0], c[1], c[0], c[1]);
                }
                return;
            }
            if (at(OCCUPIED, cx, cz) || !land(cx, cz)) return;
            cells.add(new int[] {cx, cz});
        }
    }

    static void streets() {
        pave(-17, -23, 17, 15);   // plaza + market square (redrawn by square())
        pave(-4, 16, 4, 26);      // south avenue to the harbour
        pave(-46, 26, 46, 29);    // quay promenade (the quay edge itself is set by terrain)
        pave(-58, -3, -18, 3);    // west street
        pave(18, -3, 58, 3);      // east street
        lane(-56, -23, -18, -22); // north-west lane
        lane(18, -23, 56, -22);   // north-east lane
        lane(-48, -52, -47, -24); // lane up to the windmill
        lane(20, -47, 21, -24);   // church path
        lane(22, -48, 22, -48);
    }

    // ------------------------------------------------------------------ generic architecture

    record Style(String wall, String corner, String trim, String roof, String ridge, String shutter, String door,
                 String foundation, String step, String floor) {}

    static final Style FALU = new Style("red_terracotta", "smooth_quartz", "smooth_quartz", "dark_oak",
            "dark_oak_planks", "spruce", "spruce", "cobblestone", "cobblestone", "spruce_planks");
    static final Style FALU_DARK = new Style("red_terracotta", "smooth_quartz", "smooth_quartz", "deepslate_tile",
            "deepslate_tiles", "dark_oak", "dark_oak", "stone_bricks", "stone_brick", "spruce_planks");
    static final Style OCHRE = new Style("yellow_terracotta", "smooth_quartz", "smooth_quartz", "deepslate_tile",
            "deepslate_tiles", "dark_oak", "dark_oak", "stone_bricks", "stone_brick", "spruce_planks");
    static final Style WHITE = new Style("calcite", "smooth_quartz", "spruce_planks", "dark_oak",
            "dark_oak_planks", "dark_oak", "dark_oak", "cobblestone", "cobblestone", "spruce_planks");
    static final Style GREY_BLUE = new Style("light_blue_terracotta", "smooth_quartz", "smooth_quartz",
            "deepslate_tile", "deepslate_tiles", "spruce", "spruce", "stone_bricks", "stone_brick", "birch_planks");

    /** Sets a cell on a wall given by the side and the coordinate along it. */
    static void onWall(int x0, int z0, int x1, int z1, Dir side, int along, int y, String state) {
        switch (side) {
            case NORTH -> set(along, y, z0, state);
            case SOUTH -> set(along, y, z1, state);
            case WEST -> set(x0, y, along, state);
            case EAST -> set(x1, y, along, state);
        }
    }

    /** Sets the cell just outside a wall. */
    static void outside(int x0, int z0, int x1, int z1, Dir side, int along, int y, String state) {
        switch (side) {
            case NORTH -> set(along, y, z0 - 1, state);
            case SOUTH -> set(along, y, z1 + 1, state);
            case WEST -> set(x0 - 1, y, along, state);
            case EAST -> set(x1 + 1, y, along, state);
        }
    }

    static void shell(int x0, int z0, int x1, int z1, int y0, int y1, String wall, String corner) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, wall);
                set(x, y, z1, wall);
            }
            for (int z = z0; z <= z1; z++) {
                set(x0, y, z, wall);
                set(x1, y, z, wall);
            }
            if (corner != null) {
                set(x0, y, z0, corner);
                set(x1, y, z0, corner);
                set(x0, y, z1, corner);
                set(x1, y, z1, corner);
            }
        }
    }

    static void band(int x0, int z0, int x1, int z1, int y, String state) {
        for (int x = x0 + 1; x < x1; x++) {
            set(x, y, z0, state);
            set(x, y, z1, state);
        }
        for (int z = z0 + 1; z < z1; z++) {
            set(x0, y, z, state);
            set(x1, y, z, state);
        }
    }

    /** Windows along one wall, centred, every {@code step} cells; skips cells near the door. */
    static void windowRow(int x0, int z0, int x1, int z1, Dir side, int yLo, int yHi, int step, String trim,
                          String shutterMat, int avoid, String glass) {
        int a0 = side.alongX() ? x0 : z0, a1 = side.alongX() ? x1 : z1;
        int first = a0 + 2, last = a1 - 2;
        if (last < first) return;
        int start = first + ((last - first) % step) / 2;
        for (int a = start; a <= last; a += step) {
            if (Math.abs(a - avoid) <= 1) continue;
            for (int y = yLo; y <= yHi; y++) onWall(x0, z0, x1, z1, side, a, y, glass);
            if (trim != null) {
                onWall(x0, z0, x1, z1, side, a, yLo - 1, trim);
                onWall(x0, z0, x1, z1, side, a, yHi + 1, trim);
            }
            if (shutterMat != null)
                for (int y = yLo; y <= yHi; y++) {
                    outside(x0, z0, x1, z1, side, a - 1, y, shutter(shutterMat, side));
                    outside(x0, z0, x1, z1, side, a + 1, y, shutter(shutterMat, side));
                }
        }
    }

    /** Window positions of a wall (same rule as windowRow) — used for bushes under ground floor windows. */
    static List<Integer> windowSlots(int a0, int a1, int step, int avoid) {
        List<Integer> out = new ArrayList<>();
        int first = a0 + 2, last = a1 - 2;
        if (last < first) return out;
        for (int a = first + ((last - first) % step) / 2; a <= last; a += step) if (Math.abs(a - avoid) > 1) out.add(a);
        return out;
    }

    /**
     * Gable roof with a one-block overhang; rises one block per row from the wall top {@code h}.
     *
     * @return the height of the roof at every "across" coordinate is h + distance from the eave; returns ridge y
     */
    static int roof(int x0, int z0, int x1, int z1, int h, boolean ridgeX, String mat, String full, String gable) {
        int a0 = ridgeX ? z0 : x0, a1 = ridgeX ? z1 : x1;
        int b0 = ridgeX ? x0 : z0, b1 = ridgeX ? x1 : z1;
        Dir up0 = ridgeX ? Dir.SOUTH : Dir.EAST, up1 = ridgeX ? Dir.NORTH : Dir.WEST;
        int lo = a0 - 1, hi = a1 + 1, y = h, top = h;
        while (lo <= hi) {
            for (int b = b0 - 1; b <= b1 + 1; b++) {
                if (lo == hi) setAB(ridgeX, lo, y, b, full);
                else {
                    setAB(ridgeX, lo, y, b, stairs(mat, up0));
                    setAB(ridgeX, hi, y, b, stairs(mat, up1));
                }
            }
            top = y;
            lo++;
            hi--;
            y++;
        }
        for (int a = a0; a <= a1; a++) {
            int roofY = h + Math.min(a - (a0 - 1), (a1 + 1) - a);
            for (int yy = h + 1; yy < roofY; yy++) {
                setAB(ridgeX, a, yy, b0, gable);
                setAB(ridgeX, a, yy, b1, gable);
            }
        }
        int mid = (a0 + a1) / 2;
        int roofMid = h + Math.min(mid - (a0 - 1), (a1 + 1) - mid);
        if (roofMid - h >= 4) { // attic windows in both gables
            for (int b : new int[] {b0, b1}) {
                setAB(ridgeX, mid, h + 2, b, "glass_pane");
                setAB(ridgeX, mid, h + 3, b, "glass_pane");
            }
        }
        return top;
    }

    static int roofHeightAt(int a0, int a1, int h, int a) {
        return h + Math.min(a - (a0 - 1), (a1 + 1) - a);
    }

    static void setAB(boolean ridgeX, int a, int y, int b, String state) {
        if (ridgeX) set(b, y, a, state);
        else set(a, y, b, state);
    }

    /** A complete Nordic house: foundation, framed walls, windows with shutters, door, gable roof, chimney. */
    static void house(String name, int x0, int z0, int x1, int z1, int floors, boolean ridgeX, Dir doorSide, Style st,
                      boolean chimney) {
        claim(name, x0, z0, x1, z1);
        int h = floors * 4;
        fill(x0, -3, z0, x1, 0, z1, st.foundation());
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, st.floor());
        shell(x0, z0, x1, z1, 1, h, st.wall(), st.corner());
        band(x0, z0, x1, z1, h, st.trim());
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        for (int k = 1; k < floors; k++) {
            fill(x0 + 1, 4 * k, z0 + 1, x1 - 1, 4 * k, z1 - 1, st.floor());
            band(x0, z0, x1, z1, 4 * k, st.trim());
        }
        int doorAlong = doorSide.alongX() ? (x0 + x1) / 2 : (z0 + z1) / 2;
        for (Dir side : Dir.values()) {
            int avoid = side == doorSide ? doorAlong : Integer.MIN_VALUE / 2;
            for (int k = 0; k < floors; k++)
                windowRow(x0, z0, x1, z1, side, 4 * k + 2, 4 * k + 3, 3, st.trim(), st.shutter(), avoid, "glass_pane");
            // bushes and flower beds under the ground floor windows
            int a0 = side.alongX() ? x0 : z0, a1 = side.alongX() ? x1 : z1;
            for (int a : windowSlots(a0, a1, 3, avoid))
                if (rnd() < 0.55) outside(x0, z0, x1, z1, side, a, 0,
                        rnd() < 0.5 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
        }
        // door + step + path
        int dx = doorSide == Dir.EAST ? x1 : doorSide == Dir.WEST ? x0 : doorAlong;
        int dz = doorSide == Dir.SOUTH ? z1 : doorSide == Dir.NORTH ? z0 : doorAlong;
        door(dx, 1, dz, st.door(), doorSide, "left");
        set(dx, 3, dz, st.trim());
        set(dx + doorSide.dx, 0, dz + doorSide.dz, stairs(st.step(), doorSide.opposite()));
        doorPath(dx + doorSide.dx, dz + doorSide.dz, doorSide);
        roof(x0, z0, x1, z1, h, ridgeX, st.roof(), st.ridge(), st.wall());
        if (chimney) {
            int cx = ridgeX ? x0 + 2 : (x0 + x1) / 2 + 1;
            int cz = ridgeX ? (z0 + z1) / 2 + 1 : z0 + 2;
            int a0 = ridgeX ? z0 : x0, a1 = ridgeX ? z1 : x1, a = ridgeX ? cz : cx;
            int top = roofHeightAt(a0, a1, h, a) + 2;
            fill(cx, 1, cz, cx, top, cz, "bricks");
            set(cx, top + 1, cz, "campfire[lit=true,signal_fire=false]");
        }
        // interior: lanterns and a little furniture on every floor
        for (int k = 0; k < floors; k++) {
            int y = 4 * k + 1;
            set(x0 + 1, y, z0 + 1, "lantern");
            set(x1 - 1, y, z1 - 1, "lantern");
            set(x1 - 1, y, z0 + 1, pick(List.of("barrel[facing=up]", "crafting_table", "bookshelf", "chest[facing=south]")));
            set(x0 + 1, y, z1 - 1, pick(List.of("barrel[facing=up]", "bookshelf", "composter", "loom")));
        }
    }

    static void pyramid(int cx, int cz, int[][] levels, String mat, String block) {
        // levels: {halfSize, yFrom, yTo}
        for (int[] lv : levels) {
            int hs = lv[0];
            for (int y = lv[1]; y <= lv[2]; y++) {
                fill(cx - hs, y, cz - hs, cx + hs, y, cz + hs, block);
                if (hs == 0) continue;
                for (int i = -hs; i <= hs; i++) {
                    set(cx + i, y, cz - hs, stairs(mat, Dir.SOUTH));
                    set(cx + i, y, cz + hs, stairs(mat, Dir.NORTH));
                }
                for (int i = -hs + 1; i <= hs - 1; i++) {
                    set(cx - hs, y, cz + i, stairs(mat, Dir.EAST));
                    set(cx + hs, y, cz + i, stairs(mat, Dir.WEST));
                }
            }
        }
    }

    static void lamp(int x, int z) {
        if (!isAir(x, 0, z) || !isAir(x, 3, z)) return;
        set(x, 0, z, "cobblestone_wall");
        set(x, 1, z, "dark_oak_fence");
        set(x, 2, z, "dark_oak_fence");
        set(x, 3, z, "lantern");
    }

    static void flagpole(int x, int z, Dir flies, int height) {
        set(x, 0, z, "stone_bricks");
        fill(x, 1, z, x, height, z, "spruce_fence");
        set(x, height + 1, z, "gold_block");
        // NORDIA flag: blue field with a yellow Nordic cross, 9 x 5
        for (int i = 1; i <= 9; i++)
            for (int j = 0; j < 5; j++) {
                int y = height - 4 + j;
                boolean cross = i == 3 || i == 4 || j == 2;
                String c = cross ? "yellow_wool" : "blue_wool";
                set(x + flies.dx * i, y, z + flies.dz * i, c);
            }
    }

    // ------------------------------------------------------------------ the market square

    static void square() {
        for (int x = -17; x <= 17; x++)
            for (int z = -23; z <= 15; z++) {
                double d = Math.hypot(x, z);
                String s;
                if (x == -17 || x == 17 || z == -23 || z == 15) s = "stone_bricks";
                else if (Math.abs(d - 6.5) < 0.55 || Math.abs(d - 11.5) < 0.55) s = "stone_bricks";
                else if (d > 7 && d < 11 && (Math.abs(x) == Math.abs(z) || x == 0 || z == 0)) s = "polished_diorite";
                else if (z < -16) s = rnd() < 0.8 ? "polished_andesite" : "andesite";
                else s = ((x + z) & 1) == 0 ? "polished_andesite" : "andesite";
                set(x, -1, z, s);
            }
        // fountain
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++) {
                double d = Math.hypot(x, z);
                if (d <= 3.6) {
                    set(x, -1, z, "stone_bricks");
                    set(x, 0, z, "water");
                } else if (d <= 4.6) {
                    set(x, 0, z, "stone_bricks");
                    set(x, 1, z, slab("stone_brick", false));
                }
            }
        for (int[] p : new int[][] {{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) set(p[0], -1, p[1], "sea_lantern");
        set(0, 0, 0, "chiseled_stone_bricks");
        fill(0, 1, 0, 0, 3, 0, "stone_brick_wall");
        fill(-1, 4, -1, 1, 4, 1, "polished_andesite");
        set(0, 4, 0, "chiseled_stone_bricks");
        set(0, 5, 0, "water");
        mark(OCCUPIED, -5, -5, 5, 5);
        // market stalls — later rentable shop plots for players
        String[][] colours = {{"red_wool", "white_wool"}, {"blue_wool", "white_wool"}, {"yellow_wool", "white_wool"},
                {"green_wool", "white_wool"}};
        int c = 0;
        for (int[] zr : new int[][] {{-13, -10}, {-8, -5}, {5, 8}, {10, 13}}) {
            stall(-16, zr[0], -14, zr[1], Dir.EAST, colours[c++ % 4]);
            stall(14, zr[0], 16, zr[1], Dir.WEST, colours[c++ % 4]);
        }
        for (int[] xr : new int[][] {{-13, -10}, {-8, -5}, {5, 8}, {10, 13}}) {
            stall(xr[0], -14, xr[1], -12, Dir.SOUTH, colours[c++ % 4]);
            stall(xr[0], 12, xr[1], 14, Dir.NORTH, colours[c++ % 4]);
        }
        for (int[] p : new int[][] {{-6, -6}, {6, -6}, {-6, 6}, {6, 6}}) lamp(p[0], p[1]);
        for (int x : new int[] {-16, -9, 9, 16}) lamp(x, -20);
        // flower beds at the plaza corners
        for (int[] b : new int[][] {{-16, -22, -12, -17}, {12, -22, 16, -17}})
            for (int x = b[0]; x <= b[2]; x++)
                for (int z = b[1]; z <= b[3]; z++) {
                    boolean edge = x == b[0] || x == b[2] || z == b[1] || z == b[3];
                    set(x, -1, z, edge ? "stone_bricks" : "grass_block");
                    set(x, 0, z, edge ? slab("stone_brick", false) : rnd() < 0.3 ? "flowering_azalea_leaves[persistent=true]" : flower());
                }
    }

    static void stall(int x0, int z0, int x1, int z1, Dir facing, String[] colours) {
        mark(OCCUPIED, x0, z0, x1, z1);
        for (int[] p : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) fill(p[0], 0, p[1], p[0], 2, p[1], "spruce_fence");
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int stripe = facing.alongX() ? x : z;
                set(x, 3, z, (stripe & 1) == 0 ? colours[0] : colours[1]);
            }
        set((x0 + x1) / 2, 2, (z0 + z1) / 2, "lantern[hanging=true]");
        if (rnd() < 0.6) {
            float yaw = switch (facing) {
                case SOUTH -> 0;
                case WEST -> 90;
                case NORTH -> 180;
                case EAST -> -90;
            };
            double vx = facing.alongX() ? x0 + 1.5 + RNG.nextInt(2) : (x0 + x1) / 2.0 + 0.5;
            double vz = facing.alongX() ? (z0 + z1) / 2.0 + 0.5 : z0 + 1.5 + RNG.nextInt(2);
            npc(vx, 0, vz, yaw, pick(List.of("farmer", "butcher", "fisherman", "shepherd", "fletcher", "leatherworker",
                    "toolsmith", "armorer")), null);
        }
        // counter at the front, goods at the back
        List<String> goods = List.of("barrel[facing=up]", "hay_block", "melon", "pumpkin", "decorated_pot", "composter",
                "chest[facing=" + facing.n() + "]");
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                if (corner) continue;
                boolean front = switch (facing) {
                    case EAST -> x == x1;
                    case WEST -> x == x0;
                    case SOUTH -> z == z1;
                    case NORTH -> z == z0;
                };
                boolean back = switch (facing) {
                    case EAST -> x == x0;
                    case WEST -> x == x1;
                    case SOUTH -> z == z0;
                    case NORTH -> z == z1;
                };
                if (front) set(x, 0, z, "stripped_spruce_wood");
                else if (back) set(x, 0, z, pick(goods));
            }
    }

    // ------------------------------------------------------------------ landmark buildings

    static void townHall() {
        int x0 = -15, x1 = 15, z0 = -46, z1 = -26, h = 10;
        claim("town hall", x0, z0, x1, z1);
        fill(x0, -3, z0, x1, 0, z1, "stone_bricks");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "polished_andesite");
        shell(x0, z0, x1, z1, 1, 4, "stone_bricks", "smooth_quartz");
        shell(x0, z0, x1, z1, 5, h, "yellow_terracotta", "smooth_quartz");
        band(x0, z0, x1, z1, 5, "smooth_quartz");
        band(x0, z0, x1, z1, h, "smooth_quartz");
        for (int x : new int[] {-10, -5, 5, 10}) {
            fill(x, 5, z1, x, h, z1, "smooth_quartz");
            fill(x, 5, z0, x, h, z0, "smooth_quartz");
        }
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        fill(x0 + 1, 5, z0 + 1, x1 - 1, 5, z1 - 1, "spruce_planks");
        for (Dir side : Dir.values()) {
            windowRow(x0, z0, x1, z1, side, 2, 3, 3, null, null, side == Dir.SOUTH ? 0 : Integer.MIN_VALUE / 2, "glass_pane");
            windowRow(x0, z0, x1, z1, side, 7, 8, 3, null, "dark_oak", Integer.MIN_VALUE / 2, "glass_pane");
        }
        roof(x0, z0, x1, z1, h, true, "deepslate_tile", "deepslate_tiles", "yellow_terracotta");
        // chandeliers in the hall
        for (int x = -12; x <= 12; x += 6)
            for (int z : new int[] {-40, -32}) set(x, 4, z, "lantern[hanging=true]");
        for (int x = -12; x <= 12; x += 6) set(x, 6, -36, "lantern");
        // counter and archive
        fill(-8, 1, -40, 8, 1, -40, "stripped_spruce_wood");
        fill(-14, 1, -45, 14, 3, -45, "bookshelf");
        fill(-14, 1, -45, -14, 3, -27, "bookshelf");
        fill(14, 1, -45, 14, 3, -27, "bookshelf");

        // clock tower in front of the facade
        int tx0 = -3, tx1 = 3, tz0 = -29, tz1 = -23, top = 28;
        claim("town hall tower", tx0, -25, tx1, tz1);
        fill(tx0, -3, tz0, tx1, 0, tz1, "stone_bricks");
        shell(tx0, tz0, tx1, tz1, 1, 5, "stone_bricks", "smooth_quartz");
        shell(tx0, tz0, tx1, tz1, 6, top, "yellow_terracotta", "smooth_quartz");
        for (int y : new int[] {5, 17, top}) band(tx0, tz0, tx1, tz1, y, "smooth_quartz");
        fill(tx0 + 1, 1, tz0 + 1, tx1 - 1, top - 1, tz1 - 1, "air");
        fill(tx0 + 1, 24, tz0 + 1, tx1 - 1, 24, tz1 - 1, "spruce_planks");
        // grand entrance arch
        fill(-1, 1, tz1, 1, 3, tz1, "air");
        fill(-1, 1, z1, 1, 3, z1, "air");
        set(-1, 4, tz1, stairsTop("stone_brick", Dir.EAST));
        set(1, 4, tz1, stairsTop("stone_brick", Dir.WEST));
        set(0, 4, tz1, "chiseled_stone_bricks");
        set(-1, 3, tz1, stairsTop("stone_brick", Dir.EAST));
        set(1, 3, tz1, stairsTop("stone_brick", Dir.WEST));
        fill(-4, 0, -22, 4, 0, -22, stairs("stone_brick", Dir.NORTH));
        set(-2, 3, -22, "air");
        set(-2, 2, tz1 + 1, "air");
        set(0, 3, tz1 - 1, "lantern[hanging=true]");
        // tall windows and clocks
        for (Dir side : new Dir[] {Dir.EAST, Dir.WEST, Dir.SOUTH}) {
            for (int y = 9; y <= 13; y++) onWall(tx0, tz0, tx1, tz1, side, side.alongX() ? 0 : -26, y, "glass_pane");
            clock(tx0, tz0, tx1, tz1, side, side.alongX() ? 0 : -26, 21);
            for (int y = 25; y <= 27; y++)
                for (int a = -1; a <= 1; a++) onWall(tx0, tz0, tx1, tz1, side, (side.alongX() ? 0 : -26) + a, y, "air");
        }
        for (int y = 25; y <= 27; y++) for (int a = -1; a <= 1; a++) set(a, y, tz0, "air");
        set(0, 27, -26, "bell[attachment=ceiling,facing=south]");
        pyramid(0, -26, new int[][] {{4, 29, 29}, {3, 30, 31}, {2, 32, 34}, {1, 35, 38}, {0, 39, 42}},
                "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper");
        set(0, 43, -26, "gold_block");
        set(0, 44, -26, "waxed_lightning_rod");
        for (int x : new int[] {-6, 6}) lamp(x, -21);
        label(0.5, 7.2, -21.5, "RÅDHUSET", "Fastigheter · Tomter · Städer", "#F3C969", 2.2f);
    }

    static void clock(int x0, int z0, int x1, int z1, Dir side, int centre, int cy) {
        for (int u = -2; u <= 2; u++)
            for (int v = -2; v <= 2; v++) {
                if (Math.abs(u) == 2 && Math.abs(v) == 2) continue;
                String s = Math.max(Math.abs(u), Math.abs(v)) == 2 ? "gold_block" : "smooth_quartz";
                if ((u == 0 && v >= 0 && v <= 1) || (v == 0 && u == (side == Dir.WEST ? -1 : 1))) s = "black_concrete";
                onWall(x0, z0, x1, z1, side, centre + u, cy + v, s);
            }
    }

    static void church() {
        int x0 = 30, x1 = 46, z0 = -53, z1 = -43, h = 7;
        claim("church", x0, z0, x1 + 4, z1);
        fill(x0, -3, z0, x1, 0, z1, "stone_bricks");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "polished_andesite");
        shell(x0, z0, x1, z1, 1, h, "calcite", "calcite");
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        for (Dir side : new Dir[] {Dir.NORTH, Dir.SOUTH})
            windowRow(x0, z0, x1, z1, side, 2, 5, 4, null, null, Integer.MIN_VALUE / 2, "glass_pane");
        roof(x0, z0, x1, z1, h, true, "deepslate_tile", "deepslate_tiles", "calcite");
        // chancel
        int cx0 = x1, cx1 = x1 + 4, cz0 = -51, cz1 = -45;
        fill(cx0, -3, cz0, cx1, 0, cz1, "stone_bricks");
        shell(cx0, cz0, cx1, cz1, 1, 5, "calcite", "calcite");
        fill(cx0, 1, cz0 + 1, cx1 - 1, 5, cz1 - 1, "air");
        fill(cx0 + 1, 0, cz0 + 1, cx1 - 1, 0, cz1 - 1, "polished_andesite");
        roof(cx0 + 1, cz0, cx1, cz1, 5, true, "deepslate_tile", "deepslate_tiles", "calcite");
        set(cx1, 3, -48, "glass_pane");
        set(cx1, 4, -48, "glass_pane");
        // altar, candles, pews, chandeliers
        fill(48, 1, -49, 48, 1, -47, "smooth_quartz");
        set(48, 2, -49, "white_candle[candles=4,lit=true]");
        set(48, 2, -47, "white_candle[candles=4,lit=true]");
        set(48, 2, -48, "gold_block");
        for (int x = 33; x <= 43; x += 2)
            for (int z : new int[] {-51, -50, -46, -45}) set(x, 1, z, stairs("spruce", Dir.WEST));
        for (int x = 34; x <= 42; x += 4) {
            fill(x, 9, -48, x, 13, -48, "iron_chain[axis=y]");
            set(x, 8, -48, "lantern[hanging=true]");
        }
        // tower with belfry and black spire
        int tx0 = 23, tx1 = 29, tz0 = -51, tz1 = -45;
        claim("church tower", tx0, tz0, tx1 - 1, tz1);
        fill(tx0, -3, tz0, tx1, 0, tz1, "stone_bricks");
        shell(tx0, tz0, tx1, tz1, 1, 17, "calcite", "calcite");
        fill(tx0 + 1, 1, tz0 + 1, tx1 - 1, 15, tz1 - 1, "air");
        fill(tx0 + 1, 12, tz0 + 1, tx1 - 1, 12, tz1 - 1, "spruce_planks");
        fill(tx0 + 1, 16, tz0 + 1, tx1 - 1, 16, tz1 - 1, "spruce_planks");
        fill(tx1, 1, -49, tx1, 5, -47, "air");
        for (Dir side : Dir.values()) {
            int c = side.alongX() ? 26 : -48;
            for (int y = 13; y <= 15; y++) for (int a = -1; a <= 1; a++) onWall(tx0, tz0, tx1, tz1, side, c + a, y, "air");
            for (int y = 6; y <= 8; y++) onWall(tx0, tz0, tx1, tz1, side, c, y, "glass_pane");
        }
        set(26, 15, -48, "bell[attachment=ceiling,facing=west]");
        pyramid(26, -48, new int[][] {{4, 18, 18}, {3, 19, 20}, {2, 21, 24}, {1, 25, 31}, {0, 32, 37}},
                "polished_blackstone_brick", "polished_blackstone_bricks");
        set(26, 38, -48, "gold_block");
        set(26, 39, -48, "waxed_lightning_rod");
        door(tx0, 1, -48, "dark_oak", Dir.WEST, "left");
        set(tx0, 3, -48, "calcite");
        set(tx0 - 1, 0, -48, stairs("stone_brick", Dir.EAST));
        lamp(22, -46);
        lamp(22, -50);
        label(26.5, 20.5, -43.0, "NORDHAMNS KYRKA", "", "#E8E8E8", 1.6f);
    }

    static void bank() {
        int x0 = 24, x1 = 44, z0 = -20, z1 = -8, h = 8;
        claim("bank", x0, z0, x1, -4);
        fill(x0, -3, z0, x1, 0, z1, "stone_bricks");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "polished_diorite");
        shell(x0, z0, x1, z1, 1, h, "polished_andesite", "stone_bricks");
        band(x0, z0, x1, z1, h, "smooth_quartz");
        band(x0, z0, x1, z1, 1, "stone_bricks");
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        for (Dir side : Dir.values())
            windowRow(x0, z0, x1, z1, side, 3, 6, 3, "smooth_quartz", null, side == Dir.SOUTH ? 34 : Integer.MIN_VALUE / 2, "glass_pane");
        roof(x0, z0, x1, z1, h, true, "waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper", "polished_andesite");
        // portico with columns and pediment
        fill(x0 + 1, 0, -7, x1 - 1, 0, -5, "smooth_quartz");
        fill(x0 + 1, 0, -4, x1 - 1, 0, -4, stairs("quartz", Dir.NORTH));
        for (int x = 26; x <= 42; x += 4) {
            fill(x, 1, -6, x, 6, -6, "quartz_pillar[axis=y]");
            set(x, 7, -6, "chiseled_quartz_block");
        }
        fill(x0 + 1, 8, -7, x1 - 1, 8, -5, "smooth_quartz");
        int[][] tiers = {{25, 43}, {28, 40}, {31, 37}, {33, 35}};
        for (int t = 0; t < tiers.length; t++) {
            int y = 9 + t;
            fill(tiers[t][0], y, -7, tiers[t][1], y, -5, "smooth_quartz");
            fill(tiers[t][0], y, -7, tiers[t][0], y, -5, stairs("smooth_quartz", Dir.EAST));
            fill(tiers[t][1], y, -7, tiers[t][1], y, -5, stairs("smooth_quartz", Dir.WEST));
        }
        set(34, 10, -4, "gold_block");
        door(33, 1, z1, "dark_oak", Dir.SOUTH, "left");
        door(35, 1, z1, "dark_oak", Dir.SOUTH, "right");
        fill(34, 1, z1, 34, 2, z1, "air");
        set(34, 3, z1, "chiseled_quartz_block");
        // vault with gold visible through the windows, counters and chandeliers
        fill(26, 1, -18, 29, 2, -16, "gold_block");
        fill(39, 1, -18, 42, 2, -16, "gold_block");
        fill(30, 1, -13, 38, 1, -13, "polished_andesite");
        for (int x = 27; x <= 41; x += 7) set(x, 1, -13, "lantern");
        for (int x = 28; x <= 40; x += 6) {
            fill(x, 9, -14, x, 11, -14, "iron_chain[axis=y]");
            set(x, 8, -14, "lantern[hanging=true]");
        }
        for (int x : new int[] {24, 44}) lamp(x, -3);
        label(34.5, 13.6, -5.5, "BANKEN", "Lån · Aktier · Investeringar", "#F3C969", 2.0f);
    }

    static void jobCentre() {
        int x0 = -44, x1 = -24, z0 = -20, z1 = -9, h = 6;
        claim("job centre", x0, z0, x1, z1);
        fill(x0, -3, z0, x1, 0, z1, "cobblestone");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "spruce_planks");
        shell(x0, z0, x1, z1, 1, h, "spruce_planks", "stripped_dark_oak_log[axis=y]");
        for (int x = x0 + 4; x < x1; x += 4) {
            fill(x, 1, z0, x, h, z0, "stripped_dark_oak_log[axis=y]");
            fill(x, 1, z1, x, h, z1, "stripped_dark_oak_log[axis=y]");
        }
        band(x0, z0, x1, z1, h, log("stripped_dark_oak_log", 'x'));
        for (int z = z0 + 1; z < z1; z++) {
            set(x0, h, z, log("stripped_dark_oak_log", 'z'));
            set(x1, h, z, log("stripped_dark_oak_log", 'z'));
        }
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        for (int x = x0 + 2; x < x1; x += 4)
            if (Math.abs(x - -34) > 1)
                for (int zz : new int[] {z0, z1}) {
                    fill(x, 2, zz, x + 1, 4, zz, "glass_pane");
                }
        for (int z = z0 + 3; z <= z1 - 3; z += 3) {
            fill(x0, 2, z, x0, 4, z, "glass_pane");
            fill(x1, 2, z, x1, 4, z, "glass_pane");
        }
        roof(x0, z0, x1, z1, h, true, "dark_oak", "dark_oak_planks", "spruce_planks");
        door(-34, 1, z1, "spruce", Dir.SOUTH, "left");
        door(-35, 1, z1, "spruce", Dir.SOUTH, "right");
        set(-34, 0, z1 + 1, stairs("cobblestone", Dir.NORTH));
        set(-35, 0, z1 + 1, stairs("cobblestone", Dir.NORTH));
        // job board next to the entrance
        fill(-42, 0, z1 + 1, -38, 4, z1 + 1, "dark_oak_planks");
        fill(-41, 1, z1 + 1, -39, 3, z1 + 1, "birch_planks");
        set(-40, 5, z1 + 1, stairs("dark_oak", Dir.NORTH));
        set(-41, 5, z1 + 1, stairs("dark_oak", Dir.EAST));
        set(-39, 5, z1 + 1, stairs("dark_oak", Dir.WEST));
        set(-42, 5, z1 + 1, slab("dark_oak", false));
        set(-38, 5, z1 + 1, slab("dark_oak", false));
        // interior: desks and lanterns
        for (int x = x0 + 3; x <= x1 - 3; x += 4) {
            set(x, 1, -15, "lectern[facing=south]");
            set(x, 5, -14, "lantern[hanging=true]");
        }
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 2, z0 + 1, "bookshelf");
        for (int x : new int[] {-46, -22}) lamp(x, -8);
        label(-34.0, 12.0, -8.5, "ARBETSFÖRMEDLINGEN", "Jobb · Kontrakt · Företag", "#F3C969", 1.8f);
    }

    static void customsHouse() {
        house("customs house", -21, 16, -9, 24, 2, true, Dir.EAST, FALU_DARK, true);
        label(-7.0, 6.0, 20.5, "TULLHUSET", "Ankomst · Hjälp · Arrival", "#F3C969", 1.6f);
    }

    static void warehouse() {
        int x0 = 9, x1 = 33, z0 = 16, z1 = 24, h = 9;
        claim("warehouse", x0, z0, x1, z1);
        fill(x0, -3, z0, x1, 0, z1, "cobblestone");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "spruce_planks");
        shell(x0, z0, x1, z1, 1, 4, "cobblestone", "stone_bricks");
        shell(x0, z0, x1, z1, 5, h, "red_terracotta", "stripped_spruce_log[axis=y]");
        band(x0, z0, x1, z1, 5, log("stripped_spruce_log", 'x'));
        for (int z = z0 + 1; z < z1; z++) {
            set(x0, 5, z, log("stripped_spruce_log", 'z'));
            set(x1, 5, z, log("stripped_spruce_log", 'z'));
        }
        band(x0, z0, x1, z1, h, "smooth_quartz");
        fill(x0 + 1, 1, z0 + 1, x1 - 1, h + 20, z1 - 1, "air");
        fill(x0 + 1, 5, z0 + 1, x1 - 1, 5, z1 - 1, "spruce_planks");
        for (int cx : new int[] {14, 21, 28}) {
            fill(cx - 1, 1, z1, cx + 1, 3, z1, "air");
            fill(cx - 1, 4, z1, cx + 1, 4, z1, log("dark_oak_log", 'x'));
            fill(cx, 6, z1, cx, 7, z1, "air");
            fill(cx, 9, z1, cx, 9, z1 + 2, log("dark_oak_log", 'z'));
            fill(cx, 7, z1 + 2, cx, 8, z1 + 2, "iron_chain[axis=y]");
            set(cx, 6, z1 + 2, "barrel[facing=up]");
        }
        for (Dir side : new Dir[] {Dir.NORTH, Dir.EAST, Dir.WEST})
            windowRow(x0, z0, x1, z1, side, 7, 8, 4, null, "spruce", Integer.MIN_VALUE / 2, "glass_pane");
        roof(x0, z0, x1, z1, h, true, "dark_oak", "dark_oak_planks", "red_terracotta");
        // goods inside and outside
        for (int x = x0 + 1; x < x1; x++)
            for (int z = z0 + 1; z <= z0 + 3; z++)
                if (rnd() < 0.7) {
                    set(x, 1, z, pick(List.of("barrel[facing=up]", "hay_block", "chest[facing=south]", "spruce_planks")));
                    if (rnd() < 0.5) set(x, 2, z, pick(List.of("barrel[facing=up]", "hay_block")));
                }
        for (int x = x0 + 2; x < x1; x += 6) set(x, 4, 20, "lantern[hanging=true]");
        label(21.5, 14.0, 27.0, "MAGASINET", "Frakt · Lager · Logistik", "#F3C969", 1.8f);
    }

    static void houses() {
        house("nw1", -56, -34, -50, -28, 1, false, Dir.EAST, FALU, true);
        house("nw2", -30, -38, -22, -32, 2, true, Dir.SOUTH, OCHRE, true);
        house("nw3", -30, -52, -22, -46, 1, true, Dir.WEST, FALU, true);
        house("nw4", -56, -18, -50, -11, 1, false, Dir.SOUTH, WHITE, true);
        house("ne1", 22, -36, 30, -30, 1, true, Dir.SOUTH, FALU, true);
        house("ne2", 36, -37, 44, -30, 2, true, Dir.SOUTH, GREY_BLUE, true);
        house("ne3", 50, -18, 56, -11, 1, false, Dir.SOUTH, FALU, true);
        house("sw1", -34, 8, -26, 14, 1, true, Dir.NORTH, FALU, true);
        house("sw2", -46, 8, -38, 14, 2, true, Dir.NORTH, OCHRE, true);
        house("sw3", -57, 7, -51, 14, 1, false, Dir.NORTH, FALU, true);
        house("sw4", -34, 18, -26, 24, 1, true, Dir.SOUTH, WHITE, true);
        house("sw5", -46, 18, -38, 24, 1, true, Dir.SOUTH, FALU, true);
        house("se1", 38, 8, 46, 14, 1, true, Dir.NORTH, FALU, true);
        house("se2", 50, 7, 56, 14, 1, false, Dir.NORTH, OCHRE, true);
        house("se3", 38, 18, 46, 24, 2, true, Dir.SOUTH, FALU_DARK, true);
    }

    static void windmill() {
        int cx = -42, cz = -42;
        claim("windmill", cx - 4, cz - 4, cx + 4, cz + 4);
        fill(cx - 4, -3, cz - 4, cx + 4, 0, cz + 4, "cobblestone");
        for (int y = 1; y <= 16; y++) {
            int r = y <= 6 ? 4 : y <= 11 ? 3 : 2;
            shell(cx - r, cz - r, cx + r, cz + r, y, y, "spruce_planks", "dark_oak_log[axis=y]");
            fill(cx - r + 1, y, cz - r + 1, cx + r - 1, y, cz + r - 1, "air");
            if (y == 6 || y == 11) {
                fill(cx - r, y, cz - r, cx + r, y, cz + r, "dark_oak_planks");
                fill(cx - r + 1, y, cz - r + 1, cx + r - 1, y, cz + r - 1, "air");
            }
        }
        for (int y : new int[] {3, 8, 13}) {
            int r = y <= 6 ? 4 : y <= 11 ? 3 : 2;
            set(cx - r, y, cz, "glass_pane");
            set(cx + r, y, cz, "glass_pane");
            set(cx, y, cz - r, "glass_pane");
        }
        pyramid(cx, cz, new int[][] {{3, 17, 17}, {2, 18, 18}, {1, 19, 19}, {0, 20, 20}}, "dark_oak", "dark_oak_planks");
        door(cx, 1, cz + 4, "spruce", Dir.SOUTH, "left");
        set(cx, 0, cz + 5, stairs("cobblestone", Dir.NORTH));
        doorPath(cx, cz + 5, Dir.SOUTH);
        set(cx, 1, cz - 3, "lantern");
        // axle and sails in the plane z = cz + 5
        int hy = 14, pz = cz + 5;
        fill(cx, hy, cz + 3, cx, hy, pz, log("dark_oak_log", 'z'));
        for (int k = 1; k <= 10; k++) {
            set(cx, hy + k, pz, log("stripped_spruce_log", 'y'));
            set(cx, hy - k, pz, log("stripped_spruce_log", 'y'));
            set(cx + k, hy, pz, log("stripped_spruce_log", 'x'));
            set(cx - k, hy, pz, log("stripped_spruce_log", 'x'));
            if (k >= 3) {
                for (int w = 1; w <= 2; w++) {
                    set(cx + w, hy + k, pz, "white_wool");
                    set(cx - w, hy - k, pz, "white_wool");
                    set(cx + k, hy - w, pz, "white_wool");
                    set(cx - k, hy + w, pz, "white_wool");
                }
                set(cx + 3, hy + k, pz, "spruce_fence");
                set(cx - 3, hy - k, pz, "spruce_fence");
                set(cx + k, hy - 3, pz, "spruce_fence");
                set(cx - k, hy + 3, pz, "spruce_fence");
            }
        }
        label(cx + 0.5, 23.0, cz + 0.5, "KVARNEN", "", "#E8E8E8", 1.4f);
    }

    // ------------------------------------------------------------------ harbour

    static void harbour() {
        // bollards and ladders along the quay edge
        for (int x = -44; x <= 44; x += 5) if (quay(x, 30)) set(x, 0, 30, "stone_brick_wall");
        // wooden piers
        for (int px : new int[] {-32, 30}) {
            fill(px - 1, -1, 31, px + 1, -1, 44, "spruce_planks");
            for (int z = 31; z <= 44; z += 4)
                for (int x : new int[] {px - 1, px + 1}) fill(x, -8, z, x, -2, z, log("spruce_log", 'y'));
            lamp(px - 1, 44);
            lamp(px + 1, 44);
            mark(OCCUPIED, px - 1, 31, px + 1, 44);
        }
        // crates on the quay
        for (int i = 0; i < 40; i++) {
            int x = 8 + RNG.nextInt(28), z = 25 + RNG.nextInt(3);
            if (Math.abs(x - 14) <= 1 || Math.abs(x - 21) <= 1 || Math.abs(x - 28) <= 1) continue;
            if (!isAir(x, 0, z)) continue;
            set(x, 0, z, pick(List.of("barrel[facing=up]", "hay_block", "spruce_planks", "chest[facing=south]")));
            if (rnd() < 0.4) set(x, 1, z, pick(List.of("barrel[facing=up]", "hay_block")));
        }
        // jib crane
        fill(37, 0, 28, 37, 9, 28, log("spruce_log", 'y'));
        fill(37, 10, 26, 37, 10, 33, log("dark_oak_log", 'z'));
        set(37, 9, 27, stairsTop("dark_oak", Dir.SOUTH));
        fill(37, 5, 33, 37, 9, 33, "iron_chain[axis=y]");
        set(37, 4, 33, "barrel[facing=up]");
        // breakwaters
        for (int side : new int[] {-1, 1}) {
            int xa = side < 0 ? -58 : 8, xb = side < 0 ? -8 : 58;
            for (int x = xa; x <= xb; x++)
                for (int z = 48; z <= 51; z++) {
                    boolean edge = z == 48 || z == 51;
                    fill(x, MINY, z, x, -3, z, "stone");
                    set(x, -2, z, edge ? pick(List.of("mossy_cobblestone", "andesite", "cobblestone")) : "stone_bricks");
                    set(x, -1, z, edge ? (rnd() < 0.6 ? pick(List.of("mossy_cobblestone", "andesite", "tuff")) : "water") : "stone_bricks");
                    if (edge && get(x, -1, z).equals("water")) set(x, -1, z, "air");
                }
            for (int x = xa; x <= xb; x += 8) lamp(x, 49);
            mark(OCCUPIED, xa, 48, xb, 51);
        }
        // arms connect to the breakwaters
        for (int x : new int[] {-58, -57, -56, 56, 57, 58})
            for (int z = 44; z <= 47; z++) {
                fill(x, MINY, z, x, -2, z, "stone");
                set(x, -1, z, "stone_bricks");
            }
        // green harbour light at the west breakwater head
        fill(-10, 0, 49, -8, 5, 51, "stone_bricks");
        fill(-9, 6, 50, -9, 7, 50, "green_stained_glass");
        set(-9, 8, 50, "sea_lantern");
        set(-9, 9, 50, "waxed_oxidized_cut_copper");
        // boat houses on the arms
        smallShed(-58, 32, -54, 37, Dir.EAST);
        smallShed(-58, 39, -54, 43, Dir.EAST);
        smallShed(54, 32, 58, 37, Dir.WEST);
        // benches along the promenade
        for (int x = -40; x <= 40; x += 10) {
            if (Math.abs(x) < 8) continue;
            if (isAir(x, 0, 26) && isAir(x + 1, 0, 26)) {
                set(x, 0, 26, stairs("spruce", Dir.NORTH));
                set(x + 1, 0, 26, stairs("spruce", Dir.NORTH));
            }
        }
        for (int x = -44; x <= 44; x += 11) lamp(x, 28);
    }

    static void smallShed(int x0, int z0, int x1, int z1, Dir door) {
        claim("shed", x0, z0, x1, z1);
        fill(x0, -3, z0, x1, 0, z1, "cobblestone");
        shell(x0, z0, x1, z1, 1, 3, "red_terracotta", "stripped_spruce_log[axis=y]");
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 12, z1 - 1, "air");
        fill(x0 + 1, 0, z0 + 1, x1 - 1, 0, z1 - 1, "spruce_planks");
        int dz = (z0 + z1) / 2;
        int dx = door == Dir.EAST ? x1 : x0;
        fill(dx, 1, dz - 1, dx, 2, dz + 1, "air");
        set(dx, 1, dz, "air");
        roof(x0, z0, x1, z1, 3, false, "dark_oak", "dark_oak_planks", "red_terracotta");
        set(x0 + 1, 1, z0 + 1, "lantern");
        set(x1 - 1, 1, z1 - 1, "barrel[facing=up]");
        // a little jetty in front
        int jx = door == Dir.EAST ? x1 + 1 : x0 - 1;
        for (int k = 0; k < 4; k++) fill(jx + door.dx * k, -1, dz - 1, jx + door.dx * k, -1, dz + 1, "spruce_planks");
        fill(jx + door.dx * 3, -8, dz - 1, jx + door.dx * 3, -2, dz - 1, log("spruce_log", 'y'));
        fill(jx + door.dx * 3, -8, dz + 1, jx + door.dx * 3, -2, dz + 1, log("spruce_log", 'y'));
    }

    static void lighthouse() {
        int cx = 11, cz = 49;
        mark(OCCUPIED, cx - 5, cz - 5, cx + 5, cz + 5);
        for (int x = cx - 5; x <= cx + 5; x++)
            for (int z = cz - 5; z <= cz + 5; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= 5.3) {
                    fill(x, MINY, z, x, -2, z, "stone");
                    set(x, -1, z, d > 4.4 ? "mossy_stone_bricks" : "stone_bricks");
                }
            }
        for (int y = 0; y <= 27; y++)
            for (int x = cx - 3; x <= cx + 3; x++)
                for (int z = cz - 3; z <= cz + 3; z++) {
                    double d = Math.hypot(x - cx, z - cz);
                    if (d > 3.3) continue;
                    boolean wall = d > 2.2;
                    if (!wall) {
                        set(x, y, z, "air");
                        continue;
                    }
                    String s = y <= 1 ? "stone_bricks" : ((y - 2) / 4) % 2 == 0 ? "white_concrete" : "red_concrete";
                    set(x, y, z, s);
                }
        door(cx, 1, cz - 3, "spruce", Dir.NORTH, "left");
        for (int y = 6; y <= 22; y += 8) set(cx, y, cz + 3, "glass_pane");
        // gallery
        for (int x = cx - 4; x <= cx + 4; x++)
            for (int z = cz - 4; z <= cz + 4; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= 4.4) set(x, 28, z, "dark_oak_planks");
                if (d > 3.5 && d <= 4.4) set(x, 29, z, "iron_bars");
            }
        // lantern room
        for (int y = 29; y <= 32; y++)
            for (int x = cx - 2; x <= cx + 2; x++)
                for (int z = cz - 2; z <= cz + 2; z++) {
                    double d = Math.hypot(x - cx, z - cz);
                    if (d > 2.3) continue;
                    set(x, y, z, d > 1.3 ? "glass" : "sea_lantern");
                }
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= 3.3) set(x, 33, z, "waxed_oxidized_cut_copper");
                if (d <= 2.3) set(x, 34, z, "waxed_oxidized_cut_copper");
                if (d <= 1.3) set(x, 35, z, "waxed_oxidized_cut_copper");
            }
        set(cx, 36, cz, "gold_block");
        set(cx, 37, cz, "waxed_lightning_rod");
        label(cx + 0.5, 39.5, cz + 0.5, "FYREN", "", "#E8E8E8", 1.6f);
    }

    // ------------------------------------------------------------------ the arrival ship

    static final int SHIP_Z = 36;

    static void ship() {
        int zc = SHIP_Z;
        for (int x = -16; x <= 16; x++) {
            double t = x / 16.5;
            int hw = (int) Math.round(4.4 * Math.sqrt(Math.max(0, 1 - Math.pow(Math.abs(t), 2.6))));
            hw = Math.max(1, hw);
            int gy = 1 + (int) Math.round(2.5 * Math.pow(Math.abs(t), 4));
            int ky = -6 + (int) Math.round(3 * Math.pow(Math.abs(t), 3));
            for (int y = ky; y <= gy; y++) {
                double f = y >= 0 ? 1 : Math.sqrt((y - ky + 0.6) / (0.0 - ky + 0.6));
                int hwy = Math.max(0, (int) Math.round(hw * f));
                for (int dz = -hwy; dz <= hwy; dz++) {
                    boolean edge = Math.abs(dz) == hwy || y == ky;
                    String s;
                    if (edge) s = (y & 1) == 0 ? "dark_oak_planks" : "spruce_planks";
                    else if (y < 0) s = "spruce_planks";
                    else if (y == 0) s = "stripped_spruce_wood";
                    else s = "air";
                    set(x, y, zc + dz, s);
                }
            }
            if (gy >= 1) for (int dz = -hw + 1; dz <= hw - 1; dz++) for (int y = 1; y <= gy; y++) {
                if (Math.abs(dz) < hw) set(x, y, zc + dz, "air");
            }
            // shields along the rail
            if (Math.abs(x) <= 12 && (x & 1) == 0) {
                String shield = pick(List.of("yellow_wool", "blue_wool", "red_wool", "white_wool"));
                set(x, 1, zc + hw, shield);
                if (x < -5 || x > -2) set(x, 1, zc - hw, pick(List.of("yellow_wool", "blue_wool", "red_wool")));
            }
            // oars on the sea side
            if (Math.abs(x) <= 12 && x % 3 == 0) fill(x, 0, zc + hw + 1, x, 0, zc + hw + 3, log("stripped_spruce_log", 'z'));
        }
        // dragon prow and curled stern
        int[][] prow = {{17, 1}, {17, 2}, {18, 2}, {18, 3}, {18, 4}, {18, 5}, {19, 5}, {19, 6}, {19, 7}, {20, 7}, {20, 8}};
        for (int[] p : prow) set(p[0], p[1], zc, "dark_oak_planks");
        fill(20, 8, zc - 1, 22, 9, zc + 1, "dark_oak_planks");
        set(23, 8, zc, stairs("dark_oak", Dir.WEST));
        set(22, 7, zc, stairsTop("dark_oak", Dir.WEST));
        set(21, 9, zc - 1, "gold_block");
        set(21, 9, zc + 1, "gold_block");
        set(20, 10, zc, stairs("dark_oak", Dir.EAST));
        int[][] stern = {{-17, 1}, {-17, 2}, {-18, 2}, {-18, 3}, {-18, 4}, {-19, 4}, {-19, 5}, {-19, 6}, {-18, 7}, {-17, 7}, {-17, 6}};
        for (int[] p : stern) set(p[0], p[1], zc, "dark_oak_planks");
        // mast, yard and striped sail
        fill(0, 1, zc, 0, 19, zc, log("spruce_log", 'y'));
        fill(0, 17, zc - 8, 0, 17, zc + 8, log("stripped_spruce_log", 'z'));
        for (int z = zc - 7; z <= zc + 7; z++)
            for (int y = 7; y <= 16; y++) set(1, y, z, ((z - zc + 7) / 2) % 2 == 0 ? "red_wool" : "white_wool");
        fill(0, 20, zc, 0, 20, zc, "gold_block");
        // deck cargo and lights
        set(-10, 1, zc - 1, "barrel[facing=up]");
        set(-10, 1, zc, "barrel[facing=up]");
        set(-11, 1, zc, "chest[facing=east]");
        set(8, 1, zc + 1, "barrel[facing=up]");
        set(9, 1, zc + 1, "hay_block");
        set(12, 1, zc - 1, "barrel[facing=up]");
        for (int x : new int[] {-12, -3, 6, 13}) set(x, 1, zc + (x == -3 ? 2 : 0), "lantern");
        // gangway from the quay (z 30) to the deck
        for (int x = -5; x <= -4; x++) {
            set(x, 0, 31, slab("spruce", false));
            int hwAt = (int) Math.round(4.4 * Math.sqrt(1 - Math.pow(Math.abs(x / 16.5), 2.6)));
            for (int z = 32; z < zc - hwAt + 1; z++) set(x, 0, z, slab("spruce", true));
            set(x, 1, zc - hwAt, "air");
            set(x, 0, zc - hwAt, "stripped_spruce_wood");
        }
        set(-6, 1, 31, "spruce_fence");
        set(-3, 1, 31, "spruce_fence");
        mark(OCCUPIED, -19, zc - 5, 23, zc + 8);
        label(-4.5, 5.0, zc + 0.5, "VÄLKOMMEN TILL NORDIA", "Gå i land och följ gatan till torget", "#FFD37A", 1.4f);
    }

    static void gate() {
        for (int x : new int[] {-6, 6}) {
            fill(x, 0, 23, x, 6, 23, "stone_bricks");
            set(x, 0, 23, "chiseled_stone_bricks");
            set(x, 7, 23, "chiseled_stone_bricks");
        }
        fill(-7, 8, 23, 7, 8, 23, log("dark_oak_log", 'x'));
        fill(-8, 9, 22, 8, 9, 22, stairs("dark_oak", Dir.SOUTH));
        fill(-8, 9, 24, 8, 9, 24, stairs("dark_oak", Dir.NORTH));
        fill(-8, 9, 23, 8, 9, 23, "dark_oak_planks");
        fill(-8, 10, 23, 8, 10, 23, slab("dark_oak", false));
        for (int x : new int[] {-3, 0, 3}) set(x, 7, 23, "lantern[hanging=true]");
        mark(OCCUPIED, -6, 23, -6, 23);
        mark(OCCUPIED, 6, 23, 6, 23);
        label(0.5, 11.8, 23.5, "NORDHAMN", "Välkommen · Welcome", "#FFD37A", 2.6f);
        flagpole(-12, 27, Dir.WEST, 11);
        flagpole(12, 27, Dir.EAST, 11);
    }

    // ------------------------------------------------------------------ nature and light

    static void lamps() {
        for (int x = -54; x <= -20; x += 8) lamp(x, -3);
        for (int x = 20; x <= 54; x += 8) lamp(x, 3);
        for (int z = 17; z <= 25; z += 4) {
            lamp(-4, z);
            lamp(4, z);
        }
        for (int x = -52; x <= -20; x += 9) lamp(x, -24);
        for (int x = 20; x <= 52; x += 9) lamp(x, -24);
        for (int z = -48; z <= -28; z += 10) lamp(-49, z);
    }

    static void spruce(int x, int z, int h) {
        fill(x, 0, z, x, h - 1, z, log("spruce_log", 'y'));
        for (int y = 2; y <= h; y++) {
            int r = (int) Math.round((h - y) * 0.42 + ((y & 1) == 0 ? 0.6 : 0));
            r = Math.min(r, 3);
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 0.5 || (dx == 0 && dz == 0 && y < h)) continue;
                    if (isAir(x + dx, y, z + dz)) set(x + dx, y, z + dz, "spruce_leaves[persistent=true]");
                }
        }
        set(x, h, z, "spruce_leaves[persistent=true]");
        set(x, h + 1, z, "spruce_leaves[persistent=true]");
    }

    static void birch(int x, int z, int h) {
        fill(x, 0, z, x, h - 1, z, log("birch_log", 'y'));
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy * 1.8 + dz * dz);
                    if (d > 2.7 + rnd() * 0.5) continue;
                    if (isAir(x + dx, h - 1 + dy, z + dz)) set(x + dx, h - 1 + dy, z + dz, "birch_leaves[persistent=true]");
                }
    }

    static void trees() {
        List<int[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < 6000 && placed.size() < 140; attempt++) {
            int x = MINX + 2 + RNG.nextInt(SX - 4), z = MINZ + 2 + RNG.nextInt(SZ - 4);
            if (!land(x, z) || DIST[x - MINX][z - MINZ] < 3 || !get(x, -1, z).equals("grass_block")) continue;
            boolean free = true;
            for (int dx = -2; dx <= 2 && free; dx++)
                for (int dz = -2; dz <= 2 && free; dz++)
                    if (at(RESERVED, x + dx, z + dz) || at(PAVED, x + dx, z + dz)) free = false;
            if (!free) continue;
            for (int[] p : placed) if (Math.abs(p[0] - x) + Math.abs(p[1] - z) < 6) free = false;
            if (!free) continue;
            boolean isSpruce = rnd() < 0.62;
            int h = isSpruce ? 8 + RNG.nextInt(5) : 6 + RNG.nextInt(3);
            for (int y = 0; y <= h + 1 && free; y++) if (!isAir(x, y, z)) free = false;
            if (!free) continue;
            if (isSpruce) spruce(x, z, h);
            else birch(x, z, h);
            set(x, -1, z, rnd() < 0.5 ? "podzol" : "grass_block");
            placed.add(new int[] {x, z});
        }
    }

    static void vegetation() {
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++) {
                if (!get(x, -1, z).equals("grass_block") || !isAir(x, 0, z) || at(PAVED, x, z)) continue;
                double r = rnd();
                if (r < 0.20) set(x, 0, z, "short_grass");
                else if (r < 0.245) set(x, 0, z, flower());
                else if (r < 0.26 && isAir(x, 1, z)) {
                    set(x, 0, z, "tall_grass[half=lower]");
                    set(x, 1, z, "tall_grass[half=upper]");
                } else if (r < 0.27) set(x, 0, z, "bush");
                else if (r < 0.275) set(x, 0, z, "firefly_bush");
                else if (r < 0.29) set(x, 0, z, "pink_petals[flower_amount=4]");
            }
    }

    /** Invisible light blocks keep monsters from spawning in the town. */
    static void lightGrid() {
        for (int x = MINX + 4; x <= MAXX; x += 9)
            for (int z = MINZ + 4; z <= MAXZ; z += 9) {
                if (land(x, z)) {
                    for (int y = 0; y <= 2; y++)
                        if (isAir(x, y, z) && !isAir(x, y - 1, z)) {
                            set(x, y, z, "light[level=15]");
                            break;
                        }
                    if (isAir(x, 14, z)) set(x, 14, z, "light[level=13]");
                } else if (get(x, -4, z).equals("water")) {
                    set(x, -4, z, "light[level=15,waterlogged=true]");
                }
            }
    }

    // ------------------------------------------------------------------ entities

    static void label(double x, double y, double z, String title, String subtitle, String colour, float scale) {
        String text = subtitle.isEmpty()
                ? "{text:\"" + title + "\",color:\"" + colour + "\",bold:true}"
                : "[{text:\"" + title + "\",color:\"" + colour + "\",bold:true},{text:\"\\n" + subtitle
                  + "\",color:\"#D8D8D8\",bold:false}]";
        ENTITIES.add(String.format(Locale.ROOT,
                "summon text_display ~%.1f ~%.1f ~%.1f {Tags:[\"nordia_spawn\"],billboard:\"center\",shadow:1b,"
                + "background:1073741824,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
                + "translation:[0f,0f,0f],scale:[%.1ff,%.1ff,%.1ff]},text:%s}",
                x, y, z, scale, scale, scale, text));
    }

    /** A stationary villager for atmosphere. Interactive NPCs belong in the plugin, not in this data pack. */
    static void npc(double x, double y, double z, float yaw, String profession, String name) {
        String custom = name == null ? "" : ",CustomName:\"" + name + "\",CustomNameVisible:1b";
        ENTITIES.add(String.format(Locale.ROOT,
                "summon villager ~%.1f ~%.1f ~%.1f {Tags:[\"nordia_spawn\"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,"
                + "Silent:1b,Rotation:[%.0ff,0f],VillagerData:{profession:\"minecraft:%s\",level:5,type:\"minecraft:taiga\"}%s}",
                x, y, z, yaw, profession, custom));
    }

    static void cat(double x, double y, double z, String variant) {
        ENTITIES.add(String.format(Locale.ROOT,
                "summon cat ~%.1f ~%.1f ~%.1f {Tags:[\"nordia_spawn\"],PersistenceRequired:1b,Invulnerable:1b,Sitting:1b,"
                + "variant:\"minecraft:%s\"}", x, y, z, variant));
    }

    static void people() {
        npc(-6.5, 0, 20.5, 90, "cartographer", "Tullaren");
        npc(-2.5, 0, 29.5, 180, "fisherman", null);
        npc(24.5, 0, 26.5, 0, "fisherman", null);
        npc(-31.5, -0.9, 43.5, 180, "fisherman", null);
        npc(0.5, 1, -38.5, 180, "librarian", null);
        npc(34.5, 1, -12.5, 180, "cartographer", null);
        npc(-34.5, 1, -14.5, 0, "mason", null);
        npc(40.5, 1, -48.5, 90, "cleric", null);
        cat(3.5, 0, 6.5, "black");
        cat(-20.5, 0, 26.5, "tabby");
        cat(10.5, 1, 36.5, "white");
    }

    static void boats() {
        double[][] spots = {{-35.5, 38.5, 0}, {-28.5, 42.5, 180}, {33.5, 37.5, 0}, {26.5, 41.5, 90}, {-20.5, 44.5, 90},
                {-51.5, 34.5, 90}};
        String[] kinds = {"spruce_boat", "oak_boat", "spruce_chest_boat", "birch_boat", "dark_oak_boat", "spruce_boat"};
        for (int i = 0; i < spots.length; i++)
            ENTITIES.add(String.format(Locale.ROOT, "summon %s ~%.1f ~-1.6 ~%.1f {Tags:[\"nordia_spawn\"],Rotation:[%.0ff,0f]}",
                    kinds[i], spots[i][0], spots[i][1], spots[i][2]));
    }

    // ------------------------------------------------------------------ compile to commands

    static final Set<String> ATTACHED = Set.of("lantern", "short_grass", "fern", "bush", "firefly_bush", "pink_petals",
            "seagrass", "flower_pot", "bell", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
            "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "tall_grass",
            "white_candle", "campfire", "lectern");
    static final Set<String> TWO_HALVES = Set.of("tall_grass", "large_fern", "lilac", "rose_bush", "peony", "sunflower");

    static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    static int phase(String state) {
        String b = base(state);
        if (b.equals("water")) return 1;
        if (ATTACHED.contains(b) || b.endsWith("_door") || b.endsWith("_carpet")) return 2;
        return 0;
    }

    static boolean strict(String state) {
        String b = base(state);
        return b.endsWith("_door") || TWO_HALVES.contains(b);
    }

    record Cmd(String text, long volume) {}

    static String rel(int v) {
        return v == 0 ? "~" : "~" + v;
    }

    static List<Cmd> compile() {
        List<Cmd> out = new ArrayList<>();
        boolean[] done = new boolean[VOX.length];
        int[] phaseOf = new int[PALETTE.size()];
        for (int i = 0; i < PALETTE.size(); i++) phaseOf[i] = phase(PALETTE.get(i));
        for (int ph = 0; ph < 3; ph++) {
            for (int y = MINY; y <= MAXY; y++)
                for (int z = MINZ; z <= MAXZ; z++)
                    for (int x = MINX; x <= MAXX; x++) {
                        int i = idx(x, y, z);
                        short v = VOX[i];
                        if (done[i] || phaseOf[v] != ph) continue;
                        String state = PALETTE.get(v);
                        if (ph == 2) {
                            done[i] = true;
                            out.add(new Cmd("setblock " + rel(x) + " " + rel(y) + " " + rel(z) + " " + state
                                    + (strict(state) ? " strict" : ""), 1));
                            continue;
                        }
                        int x2 = x;
                        while (x2 + 1 <= MAXX && same(x2 + 1, y, z, v, done) && (x2 + 2 - x) <= 32768) x2++;
                        int z2 = z;
                        while (z2 + 1 <= MAXZ && (long) (x2 - x + 1) * (z2 + 2 - z) <= 32768 && rowSame(x, x2, y, z2 + 1, v, done)) z2++;
                        int y2 = y;
                        while (y2 + 1 <= MAXY && (long) (x2 - x + 1) * (z2 - z + 1) * (y2 + 2 - y) <= 32768
                               && layerSame(x, x2, y2 + 1, z, z2, v, done)) y2++;
                        for (int yy = y; yy <= y2; yy++)
                            for (int zz = z; zz <= z2; zz++)
                                for (int xx = x; xx <= x2; xx++) done[idx(xx, yy, zz)] = true;
                        long vol = (long) (x2 - x + 1) * (y2 - y + 1) * (z2 - z + 1);
                        if (vol == 1) out.add(new Cmd("setblock " + rel(x) + " " + rel(y) + " " + rel(z) + " " + state, 1));
                        else out.add(new Cmd("fill " + rel(x) + " " + rel(y) + " " + rel(z) + " " + rel(x2) + " " + rel(y2)
                                + " " + rel(z2) + " " + state, vol));
                    }
        }
        return out;
    }

    static boolean same(int x, int y, int z, short v, boolean[] done) {
        int i = idx(x, y, z);
        return !done[i] && VOX[i] == v;
    }

    static boolean rowSame(int x0, int x1, int y, int z, short v, boolean[] done) {
        for (int x = x0; x <= x1; x++) if (!same(x, y, z, v, done)) return false;
        return true;
    }

    static boolean layerSame(int x0, int x1, int y, int z0, int z1, short v, boolean[] done) {
        for (int z = z0; z <= z1; z++) if (!rowSame(x0, x1, y, z, v, done)) return false;
        return true;
    }

    // ------------------------------------------------------------------ data pack

    static void writeDatapack(Path root, List<Cmd> cmds) throws IOException {
        if (Files.exists(root)) try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        Path fn = root.resolve("data/nordia/function/spawn");
        Files.createDirectories(fn);
        write(root.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "description": "NORDIA — Nordhamn spawn town",
                    "pack_format": 107,
                    "min_format": [107, 0],
                    "max_format": [107, 1]
                  }
                }
                """);
        List<List<Cmd>> parts = new ArrayList<>();
        List<Cmd> current = new ArrayList<>();
        long volume = 0;
        for (Cmd c : cmds) {
            if (!current.isEmpty() && (current.size() >= 1500 || volume + c.volume() > 260_000)) {
                parts.add(current);
                current = new ArrayList<>();
                volume = 0;
            }
            current.add(c);
            volume += c.volume();
        }
        if (!current.isEmpty()) parts.add(current);
        String at = "execute at @e[type=marker,tag=nordia_spawn_origin,limit=1] run ";
        write(fn.resolve("build.mcfunction"), String.join("\n",
                "# Builds Nordhamn with its market square centre at the execution position (y = first air layer).",
                "# Usage: /execute positioned <x> 64 <z> run function nordia:spawn/build",
                "kill @e[type=marker,tag=nordia_spawn_origin]",
                "execute align xyz run summon marker ~ ~ ~ {Tags:[\"nordia_spawn_origin\"]}",
                "execute align xyz run forceload add ~-64 ~-64 ~63 ~63",
                "tellraw @a {\"text\":\"[NORDIA] Bygger Nordhamn — " + parts.size() + " steg...\",\"color\":\"gold\"}",
                "schedule function nordia:spawn/stage_0 100t", ""));
        for (int k = 0; k < parts.size(); k++) {
            StringBuilder sb = new StringBuilder();
            for (Cmd c : parts.get(k)) sb.append(c.text()).append('\n');
            write(fn.resolve("part_" + k + ".mcfunction"), sb.toString());
            String next = k + 1 < parts.size() ? "stage_" + (k + 1) : "entities";
            write(fn.resolve("stage_" + k + ".mcfunction"), String.join("\n",
                    at + "function nordia:spawn/part_" + k,
                    "title @a actionbar {\"text\":\"Bygger Nordhamn " + (k + 1) + "/" + parts.size() + "\",\"color\":\"gold\"}",
                    "schedule function nordia:spawn/" + next + " 4t", ""));
        }
        StringBuilder ents = new StringBuilder();
        ents.append(at).append("kill @e[tag=nordia_spawn,distance=..120]\n");
        for (String e : ENTITIES) ents.append(at).append(e).append('\n');
        ents.append("schedule function nordia:spawn/finish 2t\n");
        write(fn.resolve("entities.mcfunction"), ents.toString());
        write(fn.resolve("finish.mcfunction"), String.join("\n",
                at + "setworldspawn ~-8 ~1 ~" + SHIP_Z,
                at + "forceload remove ~-64 ~-64 ~63 ~63",
                "kill @e[type=marker,tag=nordia_spawn_origin]",
                "tellraw @a {\"text\":\"[NORDIA] Nordhamn är klart!\",\"color\":\"green\"}", ""));
        System.out.printf("datapack: %d commands in %d parts -> %s%n", cmds.size(), parts.size(), root);
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static void validate(Path blockList) throws IOException {
        if (blockList == null) return;
        Set<String> known = new TreeSet<>(Files.readAllLines(blockList).stream().map(String::trim).toList());
        known.addAll(List.of("air", "water", "light", "tall_grass"));
        Set<String> unknown = new TreeSet<>();
        for (String s : PALETTE) if (!known.contains(base(s))) unknown.add(base(s));
        if (!unknown.isEmpty()) throw new IllegalStateException("Unknown blocks: " + unknown);
        System.out.println("validated " + PALETTE.size() + " block states");
    }

    // ------------------------------------------------------------------ previews

    static int colour(String state) {
        String b = base(state);
        Map<String, Integer> exact = Map.ofEntries(
                Map.entry("grass_block", 0x6A9F45), Map.entry("water", 0x3566C8), Map.entry("sand", 0xDBCF98),
                Map.entry("gravel", 0x837E7C), Map.entry("clay", 0x9FA4B1), Map.entry("dirt", 0x866043),
                Map.entry("podzol", 0x5B3F1D), Map.entry("dirt_path", 0x9A7F48), Map.entry("coarse_dirt", 0x77553A),
                Map.entry("red_terracotta", 0x8F3D2E), Map.entry("yellow_terracotta", 0xBA8524),
                Map.entry("light_blue_terracotta", 0x75718B), Map.entry("calcite", 0xE0E1DD),
                Map.entry("smooth_quartz", 0xECE6DE), Map.entry("white_concrete", 0xD2D8D9),
                Map.entry("red_concrete", 0x9A2323), Map.entry("black_concrete", 0x0A0C10),
                Map.entry("gold_block", 0xF6D23E), Map.entry("sea_lantern", 0xB8D6CF), Map.entry("lantern", 0xE9B44C),
                Map.entry("glass", 0xBFE3F0), Map.entry("glass_pane", 0x9FC7D6), Map.entry("bricks", 0x96604E),
                Map.entry("white_wool", 0xEEEFEF), Map.entry("red_wool", 0xA72A24), Map.entry("yellow_wool", 0xF5C22A),
                Map.entry("blue_wool", 0x363A9E), Map.entry("green_wool", 0x566E1C), Map.entry("hay_block", 0xB59A22),
                Map.entry("iron_bars", 0x8B8B8B), Map.entry("polished_diorite", 0xC2C2C4),
                Map.entry("light", 0), Map.entry("air", 0));
        if (exact.containsKey(b)) return exact.get(b);
        if (b.contains("copper")) return 0x4FA383;
        if (b.contains("blackstone")) return 0x302A30;
        if (b.contains("deepslate")) return 0x3A3A3E;
        if (b.contains("dark_oak")) return 0x45301C;
        if (b.contains("birch_leaves")) return 0x7FA84E;
        if (b.contains("azalea")) return 0x6E9A3A;
        if (b.contains("leaves")) return 0x3D6340;
        if (b.contains("birch")) return 0xD7CFA6;
        if (b.contains("spruce")) return 0x735434;
        if (b.contains("quartz")) return 0xEAE3D9;
        if (b.contains("mossy")) return 0x6C7A5D;
        if (b.contains("stone_brick") || b.equals("chiseled_stone_bricks")) return 0x7B7B7B;
        if (b.contains("cobble")) return 0x7A7A7A;
        if (b.contains("andesite")) return 0x8A8B8B;
        if (b.contains("tuff")) return 0x6C6D66;
        if (b.contains("stone")) return 0x7E7E7E;
        if (b.contains("barrel") || b.contains("chest")) return 0x8B6A3C;
        if (b.contains("grass") || b.contains("bush") || b.contains("fern") || b.contains("seagrass")) return 0x5E9A3A;
        if (b.contains("petals")) return 0xE7A5C8;
        if (Set.of("poppy", "red_tulip").contains(b)) return 0xC42A22;
        if (Set.of("dandelion").contains(b)) return 0xF1D531;
        if (Set.of("cornflower", "blue_orchid").contains(b)) return 0x4B6BD6;
        if (b.contains("daisy") || b.contains("white_tulip") || b.contains("valley") || b.contains("bluet")) return 0xEDEDED;
        if (b.contains("allium")) return 0xB06AE0;
        if (b.contains("melon")) return 0x6F9A2B;
        if (b.contains("pumpkin")) return 0xD88A1C;
        return 0xB0A090;
    }

    static boolean opaque(int x, int y, int z) {
        if (!in(x, y, z)) return false;
        String b = base(get(x, y, z));
        return !(b.equals("air") || b.equals("light") || b.equals("water") || b.contains("glass") || b.contains("fence")
                 || b.contains("lantern") || b.contains("pane") || b.contains("_bars") || b.contains("chain")
                 || b.contains("grass") && !b.equals("grass_block") || b.contains("leaves") || b.contains("door")
                 || b.contains("trapdoor") || b.contains("stairs") || b.contains("slab") || b.contains("wall")
                 || phase(get(x, y, z)) == 2);
    }

    static void renderPreviews(Path dir) throws IOException {
        Files.createDirectories(dir);
        // top-down map
        int k = 6;
        BufferedImage top = new BufferedImage(SX * k, SZ * k, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = top.createGraphics();
        for (int x = MINX; x <= MAXX; x++)
            for (int z = MINZ; z <= MAXZ; z++)
                for (int y = MAXY; y >= MINY; y--) {
                    String s = get(x, y, z);
                    int c = colour(s);
                    if (c == 0) continue;
                    double shade = 0.75 + Math.max(-0.35, Math.min(0.45, y / 60.0));
                    g.setColor(shadeColour(c, shade));
                    g.fillRect((x - MINX) * k, (z - MINZ) * k, k, k);
                    break;
                }
        g.dispose();
        ImageIO.write(top, "png", dir.resolve("top.png").toFile());
        renderIso(dir.resolve("iso_se.png"), 0, 5, MINX, MAXX, MINZ, MAXZ);
        renderIso(dir.resolve("iso_sw.png"), 1, 5, MINX, MAXX, MINZ, MAXZ);
        renderIso(dir.resolve("iso_ne.png"), 2, 5, MINX, MAXX, MINZ, MAXZ);
        renderIso(dir.resolve("zoom_centre.png"), 0, 12, -30, 30, -30, 45);
        renderIso(dir.resolve("zoom_north.png"), 0, 12, -40, 50, -62, -20);
        renderIso(dir.resolve("zoom_harbour.png"), 1, 12, -30, 30, 10, 56);
        System.out.println("previews -> " + dir);
    }

    static Color shadeColour(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), gg = (int) Math.min(255, ((rgb >> 8) & 255) * f),
                b = (int) Math.min(255, (rgb & 255) * f);
        return new Color(Math.max(0, r), Math.max(0, gg), Math.max(0, b));
    }

    /**
     * Isometric render. view 0 = camera south-east, 1 = south-west, 2 = north-east. Rotating the canvas lets the
     * same painter's loop (draw far to near, bottom to top) work for every view.
     */
    static void renderIso(Path file, int view, int k, int bx0, int bx1, int bz0, int bz1) throws IOException {
        int n = Math.max(bx1 - bx0, bz1 - bz0) + 1;
        int w = 2 * n * k + 40, hgt = n * k + (MAXY + 20) * k;
        BufferedImage img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(0xCFE3F2));
        g.fillRect(0, 0, w, hgt);
        int ox = w / 2, oy = (MAXY + 6) * k;
        int cxm = (bx0 + bx1) / 2, czm = (bz0 + bz1) / 2;
        int half = n / 2 + 1;
        for (int s = -2 * half; s <= 2 * half; s++)
            for (int u = -half; u <= half; u++) {
                int v = s - u;
                if (v < -half || v > half) continue;
                int[] xz = unrotate(view, u, v);
                int x = cxm + xz[0], z = czm + xz[1];
                if (x < bx0 || x > bx1 || z < bz0 || z > bz1) continue;
                for (int y = -12; y <= MAXY; y++) {
                    String st = get(x, y, z);
                    int c = colour(st);
                    if (c == 0) continue;
                    int[] fx = unrotate(view, 1, 0), fz = unrotate(view, 0, 1);
                    boolean topOpen = !opaque(x, y + 1, z);
                    boolean eastOpen = !opaque(x + fx[0], y, z + fx[1]);
                    boolean southOpen = !opaque(x + fz[0], y, z + fz[1]);
                    if (!topOpen && !eastOpen && !southOpen) continue;
                    double fu = u, fv = v;
                    if (topOpen) poly(g, ox, oy, k, c, 1.0, new double[][] {{fu, y + 1, fv}, {fu + 1, y + 1, fv}, {fu + 1, y + 1, fv + 1}, {fu, y + 1, fv + 1}});
                    if (eastOpen) poly(g, ox, oy, k, c, 0.62, new double[][] {{fu + 1, y, fv}, {fu + 1, y + 1, fv}, {fu + 1, y + 1, fv + 1}, {fu + 1, y, fv + 1}});
                    if (southOpen) poly(g, ox, oy, k, c, 0.8, new double[][] {{fu, y, fv + 1}, {fu + 1, y, fv + 1}, {fu + 1, y + 1, fv + 1}, {fu, y + 1, fv + 1}});
                }
            }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    /** Maps view coordinates (u, v) back to world offsets (dx, dz). */
    static int[] unrotate(int view, int u, int v) {
        return switch (view) {
            case 0 -> new int[] {u, v};       // looking from +x +z
            case 1 -> new int[] {-v, u};      // looking from -x +z
            default -> new int[] {v, -u};     // looking from +x -z
        };
    }

    static void poly(Graphics2D g, int ox, int oy, int k, int rgb, double shade, double[][] pts) {
        Polygon p = new Polygon();
        for (double[] q : pts) p.addPoint((int) Math.round(ox + (q[0] - q[2]) * k), (int) Math.round(oy + (q[0] + q[2]) * k / 2.0 - q[1] * k));
        g.setColor(shadeColour(rgb, shade));
        g.fillPolygon(p);
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "tools/spawn/build");
        Path blocks = args.length > 1 ? Path.of(args[1]) : null;
        terrain();
        streets();
        square();
        townHall();
        church();
        bank();
        jobCentre();
        customsHouse();
        warehouse();
        houses();
        windmill();
        harbour();
        lighthouse();
        ship();
        gate();
        lamps();
        trees();
        vegetation();
        lightGrid();
        boats();
        people();
        validate(blocks);
        List<Cmd> cmds = compile();
        writeDatapack(out.resolve("datapack/nordia_spawn"), cmds);
        renderPreviews(out.resolve("preview"));
    }
}
