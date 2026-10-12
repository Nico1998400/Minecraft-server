package se.nordia.plugins.paper.world;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Bell;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Wall;

import static org.bukkit.Material.*;
import static org.bukkit.block.BlockFace.EAST;
import static org.bukkit.block.BlockFace.NORTH;
import static org.bukkit.block.BlockFace.SOUTH;
import static org.bukkit.block.BlockFace.UP;
import static org.bukkit.block.BlockFace.WEST;

/**
 * The spawn showpieces: a raised, landscaped plateau south of the house row with a promenade, a river and a lake, and
 * eight landmarks in a row - fountain plaza, gatehouse, clock tower, windmill, arch bridge, lighthouse, market and
 * monument. Style follows the spawn: stone brick, spruce and dark oak timber, red tiled roofs. Rebuilding wipes and
 * recreates the whole plateau.
 */
final class BuildLandmarks extends HouseKit {

    static final int COUNT = 8;
    static final int FIRST_CX = 24;
    static final int STRIDE = 48;
    static final int CZ = 158;
    static final int PROM_Z = 132;
    static final int LIFT = 5;

    private static final int X0 = 0;
    private static final int X1 = FIRST_CX + (COUNT - 1) * STRIDE + 23;
    private static final int Z0 = 124;
    private static final int Z1 = 186;
    private static final int RAMP_X = 14;
    private static final int RAMP_N = 30;
    private static final int RAMP_S = 14;
    private static final int SKY = 56;
    private static final int RIVER_X0 = FIRST_CX + 4 * STRIDE - 22;
    private static final int LAKE_CX = FIRST_CX + 5 * STRIDE;

    private static final Material[] ROCKS = {STONE, COBBLESTONE, MOSSY_COBBLESTONE, ANDESITE, STONE, COBBLESTONE};
    private static final Material[] WOOLS = {RED_WOOL, BLUE_WOOL, YELLOW_WOOL, GREEN_WOOL, ORANGE_WOOL, PURPLE_WOOL,
            CYAN_WOOL, MAGENTA_WOOL, LIME_WOOL, LIGHT_BLUE_WOOL};

    /** The last x the plateau reaches, for messages. */
    static int lastX() {
        return X1;
    }

    private final int base;
    private final int g;

    BuildLandmarks(World world) {
        super(world);
        base = world.getHighestBlockYAt(X0 - RAMP_X - 30, CZ);
        g = base + LIFT;
    }

    /** Builds everything and returns a spot on the promenade to stand at. */
    Location build() {
        terrain();
        promenade();
        avenue();
        waterways();
        for (int i = 0; i < COUNT; i++) {
            int cx = FIRST_CX + i * STRIDE;
            rnd.setSeed(7700L + i * 31L);
            switch (i) {
                case 0 -> {
                    forecourt(cx, CZ - 21);
                    fountain(cx, CZ);
                }
                case 1 -> {
                    forecourt(cx, CZ - 5);
                    gatehouse(cx, CZ);
                }
                case 2 -> {
                    forecourt(cx, CZ - 14);
                    clockTower(cx, CZ);
                }
                case 3 -> {
                    forecourt(cx, CZ - 6);
                    windmill(cx, CZ);
                }
                case 4 -> {
                    forecourt(cx, CZ - 14);
                    bridge(cx);
                }
                case 5 -> lighthouse(cx, CZ);
                case 6 -> {
                    forecourt(cx, CZ - 15);
                    market(cx, CZ);
                }
                default -> {
                    forecourt(cx, CZ - 19);
                    monument(cx, CZ);
                }
            }
            tidy(cx - 24, g - 1, CZ - 26, cx + 23, g + 48, CZ + 26);
        }
        scatter();
        trees();
        return new Location(w, FIRST_CX + 0.5, g + 1, PROM_Z + 0.5);
    }

    // ---- terrain, promenade, avenue

    private static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    private int height(int x, int z) {
        double fx = smooth((x - (X0 - RAMP_X)) / (double) RAMP_X) * smooth(((X1 + RAMP_X) - x) / (double) RAMP_X);
        double fz = smooth((z - (Z0 - RAMP_N)) / (double) RAMP_N) * smooth(((Z1 + RAMP_S) - z) / (double) RAMP_S);
        return base + (int) Math.round(LIFT * fx * fz);
    }

    /** Lifts the plateau (with soft slopes on every side) and clears everything above it. */
    private void terrain() {
        for (int x = X0 - RAMP_X - 1; x <= X1 + RAMP_X + 1; x++) {
            for (int z = Z0 - RAMP_N - 1; z <= Z1 + RAMP_S + 1; z++) {
                int h = height(x, z);
                for (int y = base + 1; y < h; y++) {
                    b(x, y, z, DIRT);
                }
                if (h > base) {
                    b(x, h, z, GRASS_BLOCK);
                }
                for (int y = h + 1; y <= base + LIFT + SKY; y++) {
                    if (!air(x, y, z)) {
                        b(x, y, z, AIR);
                    }
                }
            }
        }
    }

    private void promenade() {
        for (int x = X0 - 2; x <= X1 + 2; x++) {
            for (int dz = -2; dz <= 2; dz++) {
                Material m = dz == 0 ? POLISHED_ANDESITE : Math.abs(dz) == 1 ? STONE_BRICKS : (x % 3 == 0 ? COBBLESTONE : STONE_BRICKS);
                b(x, g, PROM_Z + dz, m);
            }
            if (x % 12 == 6) {
                for (int s = -1; s <= 1; s += 2) {
                    fill(x, g + 1, PROM_Z + 3 * s, x, g + 3, PROM_Z + 3 * s, DARK_OAK_FENCE);
                    lantern(x, g + 4, PROM_Z + 3 * s, false);
                }
            }
            if (x % 24 == 18) {
                chair(x, g + 1, PROM_Z - 3, STONE_BRICK_STAIRS, NORTH);
                chair(x + 1, g + 1, PROM_Z - 3, STONE_BRICK_STAIRS, NORTH);
                chair(x, g + 1, PROM_Z + 3, STONE_BRICK_STAIRS, SOUTH);
                chair(x + 1, g + 1, PROM_Z + 3, STONE_BRICK_STAIRS, SOUTH);
            }
        }
    }

    /** A broad road from the end of the house street down the slope to the promenade. */
    private void avenue() {
        for (int z = BuildHouses.STREET_Z - 2; z <= PROM_Z + 2; z++) {
            for (int x = 5; x <= 9; x++) {
                b(x, height(x, z), z, x == 5 || x == 9 ? COBBLESTONE : DIRT_PATH);
            }
            if (z % 10 == 0) {
                for (int x : new int[] {4, 10}) {
                    int y = height(x, z);
                    fill(x, y + 1, z, x, y + 3, z, DARK_OAK_FENCE);
                    lantern(x, y + 4, z, false);
                }
            }
        }
        for (int z = BuildHouses.STREET_Z - 2; z <= BuildHouses.STREET_Z + 2; z++) {
            for (int x = 10; x <= 13; x++) {
                b(x, base, z, DIRT_PATH);
            }
        }
    }

    /** A 3-wide path from the promenade towards a landmark. */
    private void forecourt(int cx, int endZ) {
        for (int z = PROM_Z + 3; z <= endZ; z++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (at(x, g, z) == GRASS_BLOCK) {
                    b(x, g, z, rnd.nextInt(4) == 0 ? COBBLESTONE : DIRT_PATH);
                }
            }
        }
    }

    // ---- river and lake

    private boolean inWater(int x, int z) {
        double dz = z - CZ;
        boolean river = x >= RIVER_X0 + 6 && x <= LAKE_CX && Math.abs(dz) <= 6;
        boolean cap = x >= RIVER_X0 && x < RIVER_X0 + 6 && Math.hypot(x - (RIVER_X0 + 6), dz) <= 6.2;
        double ang = Math.atan2(dz, x - LAKE_CX);
        double r = 18 + 1.6 * Math.sin(3 * ang) + 1.2 * Math.sin(5 * ang + 1);
        boolean lake = Math.hypot(x - LAKE_CX, dz) <= r;
        return river || cap || lake;
    }

    private void waterways() {
        Material[] beds = {SAND, SAND, GRAVEL, CLAY};
        Material[] shore = {SAND, SAND, GRAVEL, GRASS_BLOCK};
        for (int x = RIVER_X0 - 1; x <= LAKE_CX + 22; x++) {
            for (int z = CZ - 22; z <= CZ + 22; z++) {
                if (!inWater(x, z)) {
                    continue;
                }
                b(x, g, z, AIR);
                for (int y = g - 1; y >= base + 2; y--) {
                    b(x, y, z, WATER);
                }
                b(x, base + 1, z, beds[rnd.nextInt(beds.length)]);
            }
        }
        for (int x = RIVER_X0 - 2; x <= LAKE_CX + 23; x++) {
            for (int z = CZ - 23; z <= CZ + 23; z++) {
                if (inWater(x, z)) {
                    if (rnd.nextInt(14) == 0 && inWater(x + 1, z) && inWater(x - 1, z) && inWater(x, z + 1) && inWater(x, z - 1)) {
                        b(x, g, z, LILY_PAD);
                    }
                    continue;
                }
                if (inWater(x + 1, z) || inWater(x - 1, z) || inWater(x, z + 1) || inWater(x, z - 1)) {
                    b(x, g, z, shore[rnd.nextInt(shore.length)]);
                }
            }
        }
    }

    // ---- small helpers

    private Material brick() {
        int r = rnd.nextInt(10);
        return r == 0 ? CRACKED_STONE_BRICKS : r == 1 ? MOSSY_STONE_BRICKS : STONE_BRICKS;
    }

    private Material rock() {
        return ROCKS[rnd.nextInt(ROCKS.length)];
    }

    private void fillMix(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    b(x, y, z, brick());
                }
            }
        }
    }

    private void ringMix(int x1, int z1, int x2, int z2, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                b(x, y, z1, brick());
                b(x, y, z2, brick());
            }
            for (int z = z1 + 1; z < z2; z++) {
                b(x1, y, z, brick());
                b(x2, y, z, brick());
            }
        }
    }

    /** A thick circular wall of radius r (cells with r-1 &lt; distance &lt;= r+0.5). */
    private void cyl(int cx, int cz, double r, int y1, int y2, Material m) {
        int reach = (int) Math.ceil(r) + 1;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= r + 0.5 && d > r - 1.0) {
                    for (int y = y1; y <= y2; y++) {
                        b(cx + dx, y, cz + dz, m);
                    }
                }
            }
        }
    }

    private void cylBricks(int cx, int cz, double r, int y1, int y2) {
        int reach = (int) Math.ceil(r) + 1;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= r + 0.5 && d > r - 1.0) {
                    for (int y = y1; y <= y2; y++) {
                        b(cx + dx, y, cz + dz, brick());
                    }
                }
            }
        }
    }

    /** Replaces the first wall cells met walking from (cx,cz) in direction (sx,sz), starting at distance {@code from}. */
    private void punch(int cx, int cz, int y, int sx, int sz, int from, Material m) {
        boolean seen = false;
        for (int d = from; d <= 10; d++) {
            int x = cx + sx * d, z = cz + sz * d;
            if (air(x, y, z)) {
                if (seen) {
                    break;
                }
                continue;
            }
            seen = true;
            b(x, y, z, m);
        }
    }

    /** A two-high arrow slit cut into a solid wall. */
    private void notch(int x, int y, int z) {
        b(x, y, z, AIR);
        b(x, y + 1, z, AIR);
    }

    /** A hip roof of at most {@code levels} rings over a rectangle (stairs on the edges, blocks on the corners). */
    private void hip(int x0, int z0, int x1, int z1, int y, int levels, Material stairMat, Material block) {
        int a = x0 - 1, c = z0 - 1, d = x1 + 1, e = z1 + 1;
        for (int level = 0; level < levels && a <= d && c <= e; level++, y++, a++, c++, d--, e--) {
            for (int x = a; x <= d; x++) {
                for (int z = c; z <= e; z++) {
                    boolean edgeX = x == a || x == d, edgeZ = z == c || z == e;
                    if (!edgeX && !edgeZ) {
                        continue;
                    }
                    if (edgeX && edgeZ || d - a < 2 || e - c < 2) {
                        b(x, y, z, block);
                    } else if (z == c) {
                        stair(x, y, z, stairMat, SOUTH, false);
                    } else if (z == e) {
                        stair(x, y, z, stairMat, NORTH, false);
                    } else if (x == a) {
                        stair(x, y, z, stairMat, EAST, false);
                    } else {
                        stair(x, y, z, stairMat, WEST, false);
                    }
                }
            }
        }
    }

    /** Concentric stair ring (high side towards the centre) at Chebyshev distance {@code half}. */
    private void stairRing(int cx, int cz, int half, int y, Material stairMat, Material cornerMat) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != half) {
                    continue;
                }
                boolean sideX = Math.abs(dx) == half, sideZ = Math.abs(dz) == half;
                if (sideX && sideZ) {
                    b(cx + dx, y, cz + dz, cornerMat);
                } else if (sideX) {
                    stair(cx + dx, y, cz + dz, stairMat, dx > 0 ? WEST : EAST, false);
                } else {
                    stair(cx + dx, y, cz + dz, stairMat, dz > 0 ? NORTH : SOUTH, false);
                }
            }
        }
    }

    private void square(int cx, int cz, int half, int y1, int y2, Material m) {
        fill(cx - half, y1, cz - half, cx + half, y2, cz + half, m);
    }

    /** Fence + lantern lamp post. */
    private void lamp(int x, int y, int z, int height) {
        fill(x, y + 1, z, x, y + height, z, DARK_OAK_FENCE);
        lantern(x, y + height + 1, z, false);
    }

    /** 3x3 stone planter with a flowering azalea. */
    private void planter(int px, int pz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    b(px, g, pz, MOSS_BLOCK);
                    b(px, g + 1, pz, FLOWERING_AZALEA);
                } else {
                    b(px + dx, g + 1, pz + dz, STONE_BRICKS);
                }
            }
        }
    }

    /** Joins stone-brick walls (and the fences and panes of {@link #connect}) to their neighbours. */
    private void tidy(int x1, int y1, int z1, int x2, int y2, int z2) {
        connect(x1, y1, z1, x2, y2, z2);
        BlockFace[] sides = {NORTH, EAST, SOUTH, WEST};
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Block block = w.getBlockAt(x, y, z);
                    if (!Tag.WALLS.isTagged(block.getType())) {
                        continue;
                    }
                    Wall wall = (Wall) block.getBlockData();
                    boolean[] join = new boolean[4];
                    for (int k = 0; k < 4; k++) {
                        Block n = block.getRelative(sides[k]);
                        join[k] = Tag.WALLS.isTagged(n.getType()) || Tag.FENCE_GATES.isTagged(n.getType())
                                || n.getBlockData().isFaceSturdy(sides[k].getOppositeFace(), BlockSupport.FULL);
                        wall.setHeight(sides[k], join[k] ? Wall.Height.LOW : Wall.Height.NONE);
                    }
                    boolean straight = join[0] && join[2] && !join[1] && !join[3] || join[1] && join[3] && !join[0] && !join[2];
                    wall.setUp(!straight || !block.getRelative(UP).getType().isAir());
                    block.setBlockData(wall, false);
                }
            }
        }
    }

    // ---- 1. fountain plaza

    private void fountain(int cx, int cz) {
        Material[] rubble = {COBBLESTONE, ANDESITE, STONE, STONE_BRICKS, MOSSY_COBBLESTONE};
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 20) {
                    continue;
                }
                boolean spoke = dx == 0 || dz == 0 || Math.abs(dx) == Math.abs(dz);
                Material m;
                if (d <= 7) {
                    m = STONE_BRICKS;
                } else if (d <= 9) {
                    m = POLISHED_ANDESITE;
                } else if (d <= 13) {
                    m = spoke ? SMOOTH_STONE : STONE_BRICKS;
                } else if (d <= 14) {
                    m = DEEPSLATE_BRICKS;
                } else if (d <= 19) {
                    m = rubble[rnd.nextInt(rubble.length)];
                } else {
                    m = STONE_BRICKS;
                }
                b(cx + dx, g, cz + dz, m);
            }
        }
        // lower basin
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 5) {
                    b(cx + dx, g, cz + dz, WATER);
                    b(cx + dx, g - 1, cz + dz, STONE_BRICKS);
                } else if (d <= 6.5) {
                    b(cx + dx, g, cz + dz, STONE_BRICKS);
                    slab(cx + dx, g + 1, cz + dz, STONE_BRICK_SLAB, false);
                }
            }
        }
        for (int[] p : new int[][] {{6, 0}, {-6, 0}, {0, 6}, {0, -6}}) {
            fill(cx + p[0], g + 1, cz + p[1], cx + p[0], g + 2, cz + p[1], STONE_BRICK_WALL);
            lantern(cx + p[0], g + 3, cz + p[1], false);
        }
        for (int[] p : new int[][] {{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) {
            b(cx + p[0], g - 1, cz + p[1], SEA_LANTERN);
        }
        // pedestal, shaft and upper bowl
        square(cx, cz, 1, g, g + 1, STONE_BRICKS);
        square(cx, cz, 1, g + 2, g + 2, CHISELED_STONE_BRICKS);
        b(cx, g + 3, cz, STONE_BRICK_WALL);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 3.5) {
                    b(cx + dx, g + 4, cz + dz, STONE_BRICKS);
                    b(cx + dx, g + 5, cz + dz, d > 2 ? STONE_BRICKS : WATER);
                }
            }
        }
        b(cx, g + 4, cz, CHISELED_STONE_BRICKS);
        fill(cx, g + 5, cz, cx, g + 6, cz, STONE_BRICK_WALL);
        fill(cx, g + 7, cz, cx, g + 10, cz, END_ROD);
        // benches, lamps and planters
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int dx = (int) Math.round(10 * Math.cos(a)), dz = (int) Math.round(10 * Math.sin(a));
            BlockFace out = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? EAST : WEST) : (dz > 0 ? SOUTH : NORTH);
            boolean alongZ = out == EAST || out == WEST;
            for (int t = -1; t <= 1; t++) {
                chair(cx + dx + (alongZ ? 0 : t), g + 1, cz + dz + (alongZ ? t : 0), STONE_BRICK_STAIRS, out);
            }
            double la = (k + 0.5) * Math.PI / 4;
            lamp(cx + (int) Math.round(14.5 * Math.cos(la)), g, cz + (int) Math.round(14.5 * Math.sin(la)), 3);
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                planter(cx + 11 * sx, cz + 11 * sz);
            }
        }
    }

    // ---- 2. gatehouse with curtain walls

    private void gatehouse(int cx, int cz) {
        for (int s = -1; s <= 1; s += 2) {
            // curtain wall
            int lo = Math.min(cx + s * 13, cx + s * 18), hi = Math.max(cx + s * 13, cx + s * 18);
            fillMix(lo, g + 1, cz - 1, hi, g + 6, cz + 1);
            for (int x = lo; x <= hi; x++) {
                if (x % 2 == 0) {
                    b(x, g + 7, cz - 1, brick());
                    b(x, g + 7, cz + 1, brick());
                }
            }
            // end turret
            int tlo = Math.min(cx + s * 19, cx + s * 23), thi = Math.max(cx + s * 19, cx + s * 23);
            int tmid = (tlo + thi) / 2;
            fillMix(tlo, g + 1, cz - 2, thi, g + 10, cz + 2);
            notch(tmid, g + 6, cz - 2);
            notch(tmid, g + 6, cz + 2);
            int top = pyramid(tlo, cz - 2, thi, cz + 2, g + 11, BRICK_STAIRS, BRICKS);
            fill(tmid, top, cz, tmid, top + 2, cz, DARK_OAK_FENCE);
            b(tmid, top + 3, cz, RED_BANNER);
            // gate tower
            int a = Math.min(cx + s * 6, cx + s * 12), c = Math.max(cx + s * 6, cx + s * 12);
            int mid = (a + c) / 2;
            fillMix(a, g + 1, cz - 4, c, g + 16, cz + 4);
            for (int y : new int[] {g + 8, g + 13}) {
                notch(mid, y, cz - 4);
                notch(mid, y, cz + 4);
                notch(s > 0 ? c : a, y, cz);
            }
            int roof = pyramid(a, cz - 4, c, cz + 4, g + 17, BRICK_STAIRS, BRICKS);
            fill(mid, roof, cz, mid, roof + 3, cz, DARK_OAK_FENCE);
            b(mid, roof + 4, cz, RED_BANNER);
        }
        // the gate block between the towers
        fillMix(cx - 5, g + 1, cz - 3, cx + 5, g + 11, cz + 3);
        for (int x = cx - 5; x <= cx + 5; x++) {
            if ((x - cx) % 2 == 0) {
                b(x, g + 12, cz - 3, brick());
                b(x, g + 12, cz + 3, brick());
            }
        }
        for (int z = cz - 3; z <= cz + 3; z++) {
            fill(cx - 2, g + 1, z, cx + 2, g + 5, z, AIR);
            fill(cx - 1, g + 6, z, cx + 1, g + 6, z, AIR);
            stair(cx - 2, g + 5, z, STONE_BRICK_STAIRS, WEST, true);
            stair(cx + 2, g + 5, z, STONE_BRICK_STAIRS, EAST, true);
            stair(cx - 1, g + 6, z, STONE_BRICK_STAIRS, WEST, true);
            stair(cx + 1, g + 6, z, STONE_BRICK_STAIRS, EAST, true);
            for (int x = cx - 2; x <= cx + 2; x++) {
                b(x, g, z, POLISHED_ANDESITE);
            }
        }
        b(cx, g + 7, cz - 3, CHISELED_STONE_BRICKS);
        fill(cx - 1, g + 5, cz - 3, cx + 1, g + 5, cz - 3, IRON_BARS);
        b(cx, g + 6, cz - 3, IRON_BARS);
        lantern(cx, g + 6, cz - 1, true);
        lantern(cx, g + 6, cz + 1, true);
        for (int s = -1; s <= 1; s += 2) {
            torch(cx + 3 * s, g + 4, cz - 4, NORTH);
            torch(cx + 3 * s, g + 4, cz + 4, SOUTH);
            face(cx + 4 * s, g + 9, cz - 4, RED_WALL_BANNER, NORTH);
            face(cx + 4 * s, g + 9, cz + 4, RED_WALL_BANNER, SOUTH);
        }
        // road through the gate
        for (int z = cz - 12; z <= cz + 12; z++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                if (at(x, g, z) == GRASS_BLOCK || at(x, g, z) == DIRT_PATH) {
                    b(x, g, z, x == cx - 2 || x == cx + 2 ? COBBLESTONE : DIRT_PATH);
                }
            }
        }
    }

    // ---- 3. clock tower

    private void clockTower(int cx, int cz) {
        for (int dx = -13; dx <= 13; dx++) {
            for (int dz = -13; dz <= 13; dz++) {
                boolean edge = Math.abs(dx) == 13 || Math.abs(dz) == 13;
                b(cx + dx, g, cz + dz, edge ? STONE_BRICKS : rnd.nextInt(5) == 0 ? COBBLESTONE : POLISHED_ANDESITE);
            }
        }
        // hall
        ringMix(cx - 5, cz - 5, cx + 5, cz + 5, g + 1, g + 9);
        fill(cx - 4, g, cz - 4, cx + 4, g, cz + 4, DARK_OAK_PLANKS);
        fill(cx - 4, g + 9, cz - 4, cx + 4, g + 9, cz + 4, SPRUCE_PLANKS);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int y = g + 1; y <= g + 9; y++) {
                    log(cx + 5 * sx, y, cz + 5 * sz, DARK_OAK_LOG, Axis.Y);
                }
            }
        }
        fill(cx, g + 1, cz + 3, cx, g + 8, cz + 3, STONE_BRICKS);
        door(cx, g + 1, cz - 5, DARK_OAK_DOOR, NORTH);
        b(cx, g + 3, cz - 5, CHISELED_STONE_BRICKS);
        lantern(cx - 2, g + 8, cz, true);
        lantern(cx + 2, g + 8, cz, true);
        for (int x = cx - 3; x <= cx + 3; x += 2) {
            b(x, g + 1, cz + 4, BOOKSHELF);
            b(x, g + 2, cz + 4, BOOKSHELF);
        }
        for (int s = -1; s <= 1; s += 2) {
            window(cx + 3 * s, g + 3, cz - 5, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(cx + 5 * s, g + 3, cz - 2, s > 0 ? EAST : WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(cx + 5 * s, g + 3, cz + 2, s > 0 ? EAST : WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            torch(cx + s, g + 3, cz - 6, NORTH);
        }
        hip(cx - 5, cz - 5, cx + 5, cz + 5, g + 10, 3, DARK_OAK_STAIRS, DARK_OAK_PLANKS);
        // shaft
        ringMix(cx - 3, cz - 3, cx + 3, cz + 3, g + 10, g + 27);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int y = g + 10; y <= g + 27; y++) {
                    log(cx + 3 * sx, y, cz + 3 * sz, DARK_OAK_LOG, Axis.Y);
                }
            }
        }
        fill(cx - 2, g + 18, cz - 2, cx + 2, g + 18, cz + 2, SPRUCE_PLANKS);
        fill(cx - 2, g + 18, cz + 2, cx - 2, g + 18, cz + 2, SPRUCE_PLANKS);
        b(cx, g + 9, cz + 2, AIR);
        b(cx, g + 18, cz + 2, AIR);
        for (int y : new int[] {g + 13, g + 15}) {
            notch(cx - 3, y, cz);
            notch(cx + 3, y, cz);
        }
        for (int y = g + 1; y <= g + 28; y++) {
            face(cx, y, cz + 2, LADDER, NORTH);
        }
        clockFace(cx, g + 22, cz, 0, -1);
        clockFace(cx, g + 22, cz, 0, 1);
        clockFace(cx, g + 22, cz, 1, 0);
        clockFace(cx, g + 22, cz, -1, 0);
        // belfry
        fill(cx - 3, g + 28, cz - 3, cx + 3, g + 28, cz + 3, SPRUCE_PLANKS);
        b(cx, g + 28, cz + 2, AIR);
        ring(cx - 3, cz - 3, cx + 3, cz + 3, g + 29, g + 29, DARK_OAK_FENCE);
        ring(cx - 3, cz - 3, cx + 3, cz + 3, g + 32, g + 32, DARK_OAK_PLANKS);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int y = g + 29; y <= g + 32; y++) {
                    log(cx + 3 * sx, y, cz + 3 * sz, DARK_OAK_LOG, Axis.Y);
                }
            }
        }
        fill(cx - 3, g + 33, cz - 3, cx + 3, g + 33, cz + 3, DARK_OAK_PLANKS);
        Bell bell = (Bell) BELL.createBlockData();
        bell.setAttachment(Bell.Attachment.CEILING);
        data(cx, g + 32, cz, bell);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                lantern(cx + 2 * sx, g + 32, cz + 2 * sz, true);
            }
        }
        int top = pyramid(cx - 3, cz - 3, cx + 3, cz + 3, g + 34, DEEPSLATE_TILE_STAIRS, DEEPSLATE_TILES);
        fill(cx, top, cz, cx, top + 1, cz, DARK_OAK_FENCE);
        b(cx, top + 2, cz, LIGHTNING_ROD);
        // plaza decor
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                lamp(cx + 11 * sx, g, cz + 11 * sz, 3);
                chair(cx + 8 * sx, g + 1, cz + 9 * sz, STONE_BRICK_STAIRS, sz > 0 ? SOUTH : NORTH);
                chair(cx + 9 * sx, g + 1, cz + 9 * sz, STONE_BRICK_STAIRS, sz > 0 ? SOUTH : NORTH);
            }
        }
    }

    /** A 5x5 round clock face set into the shaft wall on side (sx,sz). */
    private void clockFace(int cx, int cy, int cz, int sx, int sz) {
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                double d = Math.hypot(u, v);
                if (d > 2.6) {
                    continue;
                }
                int x = cx + 3 * sx + (sz != 0 ? u : 0);
                int z = cz + 3 * sz + (sx != 0 ? u : 0);
                Material m = WHITE_CONCRETE;
                if (d > 1.9) {
                    m = u == 0 || v == 0 ? GOLD_BLOCK : BLACK_CONCRETE;
                } else if (u == 0 && v == 0 || u == 0 && v == 1 || u == 1 && v == 1) {
                    m = BLACK_CONCRETE;
                }
                b(x, cy + v, z, m);
            }
        }
    }

    // ---- 4. windmill with fields

    private void windmill(int cx, int cz) {
        // gallery floor first, the walls are laid over it
        disc(cx, cz, 6.5, g + 9, SPRUCE_PLANKS);
        disc(cx, cz, 4.2, g, SPRUCE_PLANKS);
        for (int y = g + 1; y <= g + 16; y++) {
            double r = 5.2 - 1.6 * (y - g - 1) / 15.0;
            if (y <= g + 4) {
                cylBricks(cx, cz, r, y, y);
            } else if (y == g + 5 || y == g + 10 || y == g + 15) {
                cyl(cx, cz, r, y, y, SPRUCE_LOG);
            } else {
                cyl(cx, cz, r, y, y, WHITE_CONCRETE);
            }
        }
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 5.5 && d <= 6.5) {
                    b(cx + dx, g + 10, cz + dz, DARK_OAK_FENCE);
                }
            }
        }
        // cap
        int y = g + 17;
        for (double r = 4.9; r >= 0.5; r -= 0.8, y++) {
            disc(cx, cz, r, y, DARK_OAK_PLANKS);
        }
        b(cx, y, cz, LIGHTNING_ROD);
        // door and windows
        door(cx, g + 1, cz - 5, SPRUCE_DOOR, NORTH);
        b(cx, g, cz - 6, COBBLESTONE);
        b(cx, g + 3, cz - 5, CHISELED_STONE_BRICKS);
        lantern(cx - 1, g + 4, cz - 6, false);
        for (int yy : new int[] {g + 7, g + 8}) {
            punch(cx, cz, yy, 1, 0, 1, GLASS_PANE);
            punch(cx, cz, yy, 0, 1, 1, GLASS_PANE);
        }
        for (int yy : new int[] {g + 13, g + 14}) {
            punch(cx, cz, yy, 1, 0, 1, GLASS_PANE);
            punch(cx, cz, yy, 0, 1, 1, GLASS_PANE);
            punch(cx, cz, yy, 0, -1, 1, GLASS_PANE);
        }
        // sails (on the west face)
        int hy = g + 13, x0 = cx - 6;
        log(cx - 5, hy, cz, DARK_OAK_LOG, Axis.X);
        log(cx - 6, hy, cz, DARK_OAK_LOG, Axis.X);
        b(cx - 7, hy, cz, DARK_OAK_PLANKS);
        for (int k = 1; k <= 12; k++) {
            log(x0, hy, cz + k, STRIPPED_SPRUCE_LOG, Axis.Z);
            log(x0, hy, cz - k, STRIPPED_SPRUCE_LOG, Axis.Z);
            if (k <= 11) {
                log(x0, hy + k, cz, STRIPPED_SPRUCE_LOG, Axis.Y);
                log(x0, hy - k, cz, STRIPPED_SPRUCE_LOG, Axis.Y);
            }
        }
        for (int k = 3; k <= 12; k++) {
            for (int j = 1; j <= 3; j++) {
                b(x0, hy + j, cz + k, WHITE_WOOL);
                b(x0, hy - j, cz - k, WHITE_WOOL);
                if (k <= 11) {
                    b(x0, hy + k, cz - j, WHITE_WOOL);
                    b(x0, hy - k, cz + j, WHITE_WOOL);
                }
            }
        }
        // hay, barrels and a cart-load beside the door
        for (int[] p : new int[][] {{7, 3}, {8, 3}, {7, 4}, {7, 5}}) {
            b(cx + p[0], g + 1, cz + p[1], HAY_BLOCK);
        }
        b(cx + 7, g + 2, cz + 3, HAY_BLOCK);
        b(cx + 4, g + 1, cz - 7, BARREL);
        b(cx + 5, g + 1, cz - 7, BARREL);
        b(cx + 4, g + 1, cz - 8, BARREL);
        // wheat field with a water channel and scarecrow
        int fx0 = cx + 10, fx1 = cx + 21, fz0 = cz - 7, fz1 = cz + 7;
        for (int x = fx0; x <= fx1; x++) {
            for (int z = fz0; z <= fz1; z++) {
                boolean border = x == fx0 || x == fx1 || z == fz0 || z == fz1;
                if (border) {
                    if (!(z == fz0 && (x == cx + 13 || x == cx + 14))) {
                        b(x, g + 1, z, DARK_OAK_FENCE);
                    }
                } else if (x == cx + 16) {
                    b(x, g, z, WATER);
                } else {
                    Farmland f = (Farmland) FARMLAND.createBlockData();
                    f.setMoisture(f.getMaximumMoisture());
                    data(x, g, z, f);
                    Ageable wheat = (Ageable) WHEAT.createBlockData();
                    wheat.setAge(5 + rnd.nextInt(wheat.getMaximumAge() - 4));
                    data(x, g + 1, z, wheat);
                }
            }
        }
        fill(cx + 13, g + 1, cz, cx + 13, g + 2, cz, DARK_OAK_FENCE);
        b(cx + 12, g + 2, cz, DARK_OAK_FENCE);
        b(cx + 14, g + 2, cz, DARK_OAK_FENCE);
        b(cx + 13, g + 3, cz, CARVED_PUMPKIN);
        b(cx + 13, g + 4, cz, DARK_OAK_PLANKS);
        for (int z = fz0 - 3; z < fz0; z++) {
            for (int x = cx + 13; x <= cx + 14; x++) {
                if (at(x, g, z) == GRASS_BLOCK) {
                    b(x, g, z, DIRT_PATH);
                }
            }
        }
    }

    // ---- 5. arch bridge over the river

    private void bridge(int cx) {
        final int half = 13;
        int[] top = new int[2 * half + 3];
        for (int i = -half - 1; i <= half + 1; i++) {
            double t = Math.min(1.0, Math.abs(i) / (double) half);
            top[i + half + 1] = g + (int) Math.round(4 * (1 - t * t));
        }
        for (int i = -half; i <= half; i++) {
            int z = CZ + i, t = top[i + half + 1];
            int prev = top[i + half], next = top[i + half + 2];
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int y = base + 1; y < t; y++) {
                    b(x, y, z, brick());
                }
                if (Math.abs(i) <= 6) {
                    int yy = g - 1 + (int) Math.floor(3 * Math.sqrt(1 - Math.pow(i / 6.5, 2)));
                    for (int y = base + 2; y <= yy; y++) {
                        b(x, y, z, y <= g - 1 ? WATER : AIR);
                    }
                }
                Material surface = Math.abs(x - cx) == 2 ? STONE_BRICKS : POLISHED_ANDESITE;
                if (next < t && prev >= t) {
                    stair(x, t, z, surface == STONE_BRICKS ? STONE_BRICK_STAIRS : POLISHED_ANDESITE_STAIRS, NORTH, false);
                } else if (prev < t && next >= t) {
                    stair(x, t, z, surface == STONE_BRICKS ? STONE_BRICK_STAIRS : POLISHED_ANDESITE_STAIRS, SOUTH, false);
                } else {
                    b(x, t, z, surface);
                }
            }
            b(cx - 2, t + 1, z, STONE_BRICK_WALL);
            b(cx + 2, t + 1, z, STONE_BRICK_WALL);
            if (Math.abs(i) == 4 || Math.abs(i) == half) {
                for (int s = -1; s <= 1; s += 2) {
                    b(cx + 2 * s, t + 2, z, STONE_BRICK_WALL);
                    lantern(cx + 2 * s, t + 3, z, false);
                }
            }
        }
        for (int z = CZ + half + 1; z <= CZ + half + 8; z++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (at(x, g, z) == GRASS_BLOCK) {
                    b(x, g, z, rnd.nextInt(4) == 0 ? COBBLESTONE : DIRT_PATH);
                }
            }
        }
    }

    // ---- 6. lighthouse on an island

    private void lighthouse(int cx, int cz) {
        // island
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                double d = Math.hypot(dx, dz);
                int h = (int) Math.round(3.2 * (1 - (d / 10.0) * (d / 10.0)));
                if (d > 10 || h <= 0) {
                    continue;
                }
                int top = g - 1 + h;
                for (int y = base + 1; y <= top; y++) {
                    b(cx + dx, y, cz + dz, rock());
                }
                if (h <= 2 && d > 5.5) {
                    b(cx + dx, top, cz + dz, GRASS_BLOCK);
                    b(cx + dx, top - 1, cz + dz, DIRT);
                }
            }
        }
        // tower
        cylBricks(cx, cz, 4.6, base + 2, g + 2);
        disc(cx, cz, 3.6, g + 2, SPRUCE_PLANKS);
        for (int k = 0; k < 30; k++) {
            double r = 4.6 - 1.6 * k / 29.0;
            cyl(cx, cz, r, g + 3 + k, g + 3 + k, (k / 5) % 2 == 0 ? WHITE_CONCRETE : RED_CONCRETE);
        }
        for (int k : new int[] {8, 16, 24}) {
            double r = 4.6 - 1.6 * k / 29.0;
            disc(cx, cz, r - 1.0, g + 3 + k, SPRUCE_PLANKS);
            b(cx, g + 3 + k, cz + 1, AIR);
        }
        fill(cx, g + 3, cz, cx, g + 33, cz, POLISHED_ANDESITE);
        // gallery and lantern room
        disc(cx, cz, 5.5, g + 33, POLISHED_ANDESITE);
        b(cx, g + 33, cz + 1, AIR);
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 4.6 && d <= 5.5) {
                    b(cx + dx, g + 34, cz + dz, IRON_BARS);
                }
            }
        }
        cyl(cx, cz, 2.8, g + 34, g + 37, GLASS);
        for (int yy : new int[] {g + 34, g + 35}) {
            fill(cx, yy, cz + 2, cx, yy, cz + 3, AIR);
        }
        fill(cx, g + 34, cz, cx, g + 36, cz, SEA_LANTERN);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            lantern(cx + (int) Math.round(5 * Math.cos(a)), g + 32, cz + (int) Math.round(5 * Math.sin(a)), true);
        }
        int y = g + 38;
        for (double r = 4.0; r >= 0.5; r -= 0.7, y++) {
            disc(cx, cz, r, y, DEEPSLATE_TILES);
        }
        b(cx, y, cz, LIGHTNING_ROD);
        for (int yy = g + 3; yy <= g + 34; yy++) {
            face(cx, yy, cz + 1, LADDER, SOUTH);
        }
        for (int yy : new int[] {g + 8, g + 16, g + 24}) {
            punch(cx, cz, yy, 1, 0, 2, GLASS_PANE);
            punch(cx, cz, yy, -1, 0, 2, GLASS_PANE);
            punch(cx, cz, yy, 0, 1, 2, GLASS_PANE);
            punch(cx, cz, yy + 1, 1, 0, 2, GLASS_PANE);
            punch(cx, cz, yy + 1, -1, 0, 2, GLASS_PANE);
            punch(cx, cz, yy + 1, 0, 1, 2, GLASS_PANE);
        }
        // door and path to the pier
        door(cx, g + 3, cz - 4, DARK_OAK_DOOR, NORTH);
        fill(cx, g + 3, cz - 5, cx, g + 4, cz - 5, AIR);
        stair(cx, g + 2, cz - 6, STONE_BRICK_STAIRS, SOUTH, false);
        torch(cx - 1, g + 4, cz - 6, NORTH);
        torch(cx + 1, g + 4, cz - 6, NORTH);
        for (int z = cz - 7; z >= cz - 8; z--) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                int top = g - 1 + (int) Math.round(3.2 * (1 - Math.pow(Math.hypot(x - cx, z - cz) / 10.0, 2)));
                b(x, top, z, COBBLESTONE);
            }
        }
        // pier
        for (int z = cz - 9; z >= cz - 22; z--) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                b(x, g, z, SPRUCE_PLANKS);
            }
            b(cx - 2, g + 1, z, SPRUCE_FENCE);
            b(cx + 2, g + 1, z, SPRUCE_FENCE);
            if ((cz - z) % 3 == 0) {
                for (int s = -1; s <= 1; s += 2) {
                    for (int yy = base + 2; yy < g; yy++) {
                        if (at(cx + 2 * s, yy, z) == WATER) {
                            log(cx + 2 * s, yy, z, SPRUCE_LOG, Axis.Y);
                        }
                    }
                }
            }
            if ((cz - z) % 6 == 0) {
                for (int s = -1; s <= 1; s += 2) {
                    fill(cx + 2 * s, g + 2, z, cx + 2 * s, g + 3, z, SPRUCE_FENCE);
                    lantern(cx + 2 * s, g + 4, z, false);
                }
            }
        }
    }

    // ---- 7. market

    private void market(int cx, int cz) {
        for (int dx = -22; dx <= 22; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                boolean edge = Math.abs(dx) == 22 || Math.abs(dz) == 14;
                Material m;
                if (edge) {
                    m = STONE_BRICKS;
                } else if (Math.abs(dz) <= 5) {
                    m = dz == 0 ? POLISHED_ANDESITE : rnd.nextInt(6) == 0 ? ANDESITE : COBBLESTONE;
                } else {
                    m = rnd.nextInt(3) == 0 ? GRAVEL : rnd.nextInt(2) == 0 ? COARSE_DIRT : DIRT_PATH;
                }
                b(cx + dx, g, cz + dz, m);
            }
        }
        for (int i = 0; i < 5; i++) {
            stall(cx - 19 + 8 * i, cz - 10, 1, WOOLS[i], i);
            stall(cx - 19 + 8 * i, cz + 10, -1, WOOLS[5 + i], i + 2);
        }
        // well in the middle of the lane
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    b(cx, g, cz, WATER);
                    b(cx, g - 1, cz, STONE_BRICKS);
                } else {
                    b(cx + dx, g + 1, cz + dz, COBBLESTONE);
                }
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                fill(cx + sx, g + 2, cz + sz, cx + sx, g + 3, cz + sz, DARK_OAK_FENCE);
            }
        }
        pyramid(cx - 1, cz - 1, cx + 1, cz + 1, g + 4, SPRUCE_STAIRS, SPRUCE_PLANKS);
        for (int lx : new int[] {-15, -5, 5, 15}) {
            lamp(cx + lx, g, cz, 3);
        }
        for (int lx : new int[] {-12, 11}) {
            b(cx + lx, g + 1, cz + 3, BARREL);
            b(cx + lx, g + 1, cz - 3, BARREL);
            b(cx + lx + 1, g + 1, cz - 3, HAY_BLOCK);
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            lamp(cx + 21 * sx, g, cz - 13, 3);
            lamp(cx + 21 * sx, g, cz + 13, 3);
        }
    }

    private void stall(int x0, int zb, int dir, Material cloth, int kind) {
        int zf = zb + 4 * dir;
        int zmin = Math.min(zb, zf), zmax = Math.max(zb, zf);
        fill(x0, g, zmin, x0 + 6, g, zmax, SPRUCE_PLANKS);
        for (int px : new int[] {x0, x0 + 6}) {
            for (int pz : new int[] {zb, zf}) {
                fill(px, g + 1, pz, px, g + 3, pz, STRIPPED_SPRUCE_LOG);
            }
        }
        fill(x0 + 1, g + 1, zb, x0 + 5, g + 3, zb, SPRUCE_PLANKS);
        for (int z = zmin + 1; z < zmax; z++) {
            b(x0, g + 1, z, SPRUCE_FENCE);
            b(x0 + 6, g + 1, z, SPRUCE_FENCE);
        }
        for (int x = x0 + 1; x <= x0 + 5; x++) {
            b(x, g + 1, zf, SPRUCE_PLANKS);
        }
        Material[] goods = switch (kind % 5) {
            case 0 -> new Material[] {MELON, PUMPKIN, MELON, AIR, PUMPKIN};
            case 1 -> new Material[] {POTTED_POPPY, POTTED_BLUE_ORCHID, POTTED_ALLIUM, POTTED_CORNFLOWER, POTTED_OXEYE_DAISY};
            case 2 -> new Material[] {CAKE, AIR, CAKE, CAKE, AIR};
            case 3 -> new Material[] {ANVIL, AIR, GRINDSTONE, AIR, ANVIL};
            default -> new Material[] {BARREL, AIR, BARREL, BARREL, AIR};
        };
        for (int k = 0; k < 5; k++) {
            if (goods[k] == GRINDSTONE) {
                face(x0 + 1 + k, g + 2, zf, GRINDSTONE, dir > 0 ? SOUTH : NORTH);
            } else if (goods[k] != AIR) {
                b(x0 + 1 + k, g + 2, zf, goods[k]);
            }
        }
        b(x0 + 1, g + 1, zb + dir, BARREL);
        b(x0 + 5, g + 1, zb + dir, BARREL);
        b(x0 + 3, g + 1, zb + dir, CHEST);
        int zEnd = zf + dir;
        for (int x = x0 - 1; x <= x0 + 7; x++) {
            Material m = Math.floorMod(x - x0, 2) == 0 ? cloth : WHITE_WOOL;
            for (int z = Math.min(zb, zEnd); z <= Math.max(zb, zEnd); z++) {
                b(x, g + 4, z, m);
            }
            b(x, g + 3, zEnd, m);
        }
        lantern(x0 + 3, g + 3, zb + 2 * dir, true);
    }

    // ---- 8. monument

    private void monument(int cx, int cz) {
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                int c = Math.max(Math.abs(dx), Math.abs(dz));
                Material m;
                if (c == 18) {
                    m = DEEPSLATE_BRICKS;
                } else if (Math.abs(dx) == Math.abs(dz)) {
                    m = SMOOTH_STONE;
                } else {
                    m = c % 4 == 0 ? POLISHED_ANDESITE : STONE_BRICKS;
                }
                b(cx + dx, g, cz + dz, m);
            }
        }
        // stepped base
        square(cx, cz, 8, g + 1, g + 1, STONE_BRICKS);
        stairRing(cx, cz, 9, g + 1, STONE_BRICK_STAIRS, STONE_BRICKS);
        square(cx, cz, 6, g + 2, g + 2, STONE_BRICKS);
        stairRing(cx, cz, 7, g + 2, STONE_BRICK_STAIRS, STONE_BRICKS);
        square(cx, cz, 4, g + 3, g + 3, POLISHED_ANDESITE);
        stairRing(cx, cz, 5, g + 3, STONE_BRICK_STAIRS, STONE_BRICKS);
        // pedestal
        square(cx, cz, 3, g + 4, g + 5, STONE_BRICKS);
        stairRing(cx, cz, 4, g + 4, STONE_BRICK_STAIRS, STONE_BRICKS);
        square(cx, cz, 2, g + 6, g + 8, POLISHED_ANDESITE);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                    b(cx + dx, g + 7, cz + dz, GOLD_BLOCK);
                }
            }
        }
        stairRing(cx, cz, 3, g + 6, STONE_BRICK_STAIRS, STONE_BRICKS);
        // obelisk
        square(cx, cz, 1, g + 9, g + 22, SMOOTH_QUARTZ);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                for (int y = g + 9; y <= g + 22; y++) {
                    log(cx + sx, y, cz + sz, QUARTZ_PILLAR, Axis.Y);
                }
            }
        }
        for (int y : new int[] {g + 13, g + 19}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        b(cx + dx, y, cz + dz, GOLD_BLOCK);
                    }
                }
            }
        }
        stairRing(cx, cz, 2, g + 9, QUARTZ_STAIRS, QUARTZ_BLOCK);
        square(cx, cz, 1, g + 23, g + 23, GOLD_BLOCK);
        b(cx, g + 24, cz, GOLD_BLOCK);
        b(cx + 1, g + 24, cz, GOLD_BLOCK);
        b(cx - 1, g + 24, cz, GOLD_BLOCK);
        b(cx, g + 24, cz + 1, GOLD_BLOCK);
        b(cx, g + 24, cz - 1, GOLD_BLOCK);
        b(cx, g + 25, cz, GOLD_BLOCK);
        fill(cx, g + 26, cz, cx, g + 29, cz, END_ROD);
        for (int s = -1; s <= 1; s += 2) {
            face(cx + 3 * s, g + 7, cz, RED_WALL_BANNER, s > 0 ? EAST : WEST);
            face(cx, g + 7, cz + 3 * s, RED_WALL_BANNER, s > 0 ? SOUTH : NORTH);
            face(cx + 3 * s, g + 7, cz + 1, RED_WALL_BANNER, s > 0 ? EAST : WEST);
            face(cx + 1, g + 7, cz + 3 * s, RED_WALL_BANNER, s > 0 ? SOUTH : NORTH);
        }
        // braziers on the second step
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                fill(cx + 6 * sx, g + 3, cz + 6 * sz, cx + 6 * sx, g + 4, cz + 6 * sz, STONE_BRICK_WALL);
                b(cx + 6 * sx, g + 5, cz + 6 * sz, CAMPFIRE);
                lamp(cx + 15 * sx, g, cz + 15 * sz, 3);
                planter(cx + 12 * sx, cz + 12 * sz);
            }
        }
        // benches facing the monument
        for (int t : new int[] {-6, -5, -4, 4, 5, 6}) {
            chair(cx + t, g + 1, cz - 13, STONE_BRICK_STAIRS, NORTH);
            chair(cx + t, g + 1, cz + 13, STONE_BRICK_STAIRS, SOUTH);
            chair(cx - 13, g + 1, cz + t, STONE_BRICK_STAIRS, WEST);
            chair(cx + 13, g + 1, cz + t, STONE_BRICK_STAIRS, EAST);
        }
        for (int s = -1; s <= 1; s += 2) {
            lamp(cx + 16 * s, g, cz, 3);
        }
    }

    // ---- planting

    private void scatter() {
        Material[] flowers = {POPPY, DANDELION, CORNFLOWER, OXEYE_DAISY, ALLIUM, RED_TULIP, AZURE_BLUET, LILY_OF_THE_VALLEY, PINK_TULIP};
        rnd.setSeed(4242);
        for (int x = X0 - RAMP_X; x <= X1 + RAMP_X; x++) {
            for (int z = Z0 - RAMP_N; z <= Z1 + RAMP_S; z++) {
                int y = height(x, z);
                if (at(x, y, z) != GRASS_BLOCK || !air(x, y + 1, z)) {
                    continue;
                }
                double r = rnd.nextDouble();
                if (r < 0.12) {
                    b(x, y + 1, z, SHORT_GRASS);
                } else if (r < 0.16) {
                    b(x, y + 1, z, flowers[rnd.nextInt(flowers.length)]);
                } else if (r < 0.175) {
                    b(x, y + 1, z, FERN);
                } else if (r < 0.18 && z > Z0 + 6) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dy = 1; dy <= 2; dy++) {
                                if (Math.abs(dx) + Math.abs(dz) + dy <= 3 && air(x + dx, y + dy, z + dz)
                                        && at(x + dx, y, z + dz) == GRASS_BLOCK) {
                                    Leaves l = (Leaves) OAK_LEAVES.createBlockData();
                                    l.setPersistent(true);
                                    data(x + dx, y + dy, z + dz, l);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void trees() {
        TreeType[] kinds = {TreeType.TREE, TreeType.BIG_TREE, TreeType.BIRCH, TreeType.CHERRY, TreeType.REDWOOD};
        rnd.setSeed(1717);
        for (int x = X0 + 14; x <= X1 - 8; x += 11) {
            tree(x + rnd.nextInt(5), Z0 + 1 + rnd.nextInt(2), kinds);
            tree(x + rnd.nextInt(5), Z1 - 2 + rnd.nextInt(2), kinds);
        }
        for (int z = Z0 + 6; z <= Z1 - 4; z += 12) {
            tree(X0 - 6 + rnd.nextInt(3), z, kinds);
            tree(X1 + 4 + rnd.nextInt(3), z, kinds);
        }
    }

    @SuppressWarnings("deprecation")
    private void tree(int x, int z, TreeType[] kinds) {
        if (x >= 2 && x <= 12) {
            return;
        }
        int y = height(x, z);
        if (at(x, y, z) != GRASS_BLOCK || !air(x, y + 1, z)) {
            return;
        }
        w.generateTree(new Location(w, x, y + 1, z), kinds[rnd.nextInt(kinds.length)]);
    }
}
