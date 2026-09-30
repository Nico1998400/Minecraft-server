package se.nordia.swedencore.paper.world;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A showcase row of hand-shaped trees in the build world: copy the ones you like to spawn. Every tree is seeded, so
 * the row looks the same each time it is planted. Trunks taper and flare into roots, limbs carry sub-limbs, crowns are
 * clusters of dense, ragged blobs, and the ground around each tree is dressed.
 */
final class BuildTrees {

    static final int SPACING = 30;
    static final int FIRST_X = 16;
    static final int Z = 16;
    static final int COUNT = 11;
    private static final int CLEAR_RADIUS = SPACING / 2;
    private static final int CLEAR_HEIGHT = 48;

    /** The last x the row reaches, for messages. */
    static int lastX() {
        return FIRST_X + (COUNT - 1) * SPACING;
    }

    private final World world;
    private final Random rnd = new Random();

    BuildTrees(World world) {
        this.world = world;
    }

    /** Plants the row. With {@code clear}, the space around each slot is emptied first (re-planting). */
    void plant(boolean clear) {
        for (int i = 0; i < COUNT; i++) {
            int x = FIRST_X + i * SPACING;
            int g = world.getHighestBlockYAt(x, Z);
            if (clear) {
                clearSlot(x, g);
            }
            rnd.setSeed(7100L + i * 31L);
            switch (i) {
                case 0 -> spawnTree(x, g);
                case 1 -> worldTree(x, g);
                case 2 -> cherry(x, g);
                case 3 -> tallBirch(x, g);
                case 4 -> darkOak(x, g);
                case 5 -> spruce(x, g);
                case 6 -> acacia(x, g);
                case 7 -> willow(x, g);
                case 8 -> mangrove(x, g);
                case 9 -> jungleGiant(x, g);
                default -> paleOak(x, g);
            }
        }
    }

    private void clearSlot(int cx, int g) {
        for (int x = cx - CLEAR_RADIUS; x <= cx + CLEAR_RADIUS; x++) {
            for (int z = Z - CLEAR_RADIUS; z <= Z + CLEAR_RADIUS; z++) {
                for (int y = g - 1; y <= g + CLEAR_HEIGHT; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (y > g && !b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    } else if (y <= g && y >= g - 1 && b.getType() != Material.GRASS_BLOCK && b.getType() != Material.DIRT && !b.getType().isAir()) {
                        b.setType(y == g ? Material.GRASS_BLOCK : Material.DIRT, false);
                    }
                }
            }
        }
    }

    // ---- primitives

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private boolean air(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isAir();
    }

    private boolean solid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    private void leaf(int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        if (!b.getType().isAir()) {
            return;
        }
        BlockData data = m.createBlockData();
        if (data instanceof Leaves leaves) {
            leaves.setPersistent(true);
        }
        b.setBlockData(data, false);
    }

    /** Wood with the odd block of a second tone, so bark is never flat. */
    private void bark(int x, int y, int z, Material wood, Material wood2) {
        set(x, y, z, rnd.nextDouble() < 0.12 ? wood2 : wood);
    }

    /** A tapering, slightly leaning trunk; returns the centre of its top. */
    private int[] trunk(int x, int z, int g, int h, double r0, double r1, double leanX, double leanZ, Material wood, Material wood2) {
        int cx = x, cz = z;
        for (int i = 0; i <= h; i++) {
            double t = (double) i / h;
            double r = r0 + (r1 - r0) * t;
            cx = x + (int) Math.round(leanX * t * t);
            cz = z + (int) Math.round(leanZ * t * t);
            int reach = (int) Math.ceil(r + 1);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (Math.hypot(dx, dz) <= r + (rnd.nextDouble() - 0.5) * 0.5) {
                        bark(cx + dx, g + i, cz + dz, wood, wood2);
                    }
                }
            }
        }
        return new int[] {cx, g + h, cz};
    }

    /** Roots that arch out of the trunk foot and sink into the ground. */
    private void roots(int x, int z, int g, int n, int len, double r0, Material wood) {
        for (int k = 0; k < n; k++) {
            double a = k * Math.PI * 2 / n + rnd.nextDouble() * 0.5;
            int l = len - 1 + rnd.nextInt(3);
            for (int s = 0; s <= l; s++) {
                int rx = x + (int) Math.round(Math.cos(a) * (r0 + s));
                int rz = z + (int) Math.round(Math.sin(a) * (r0 + s));
                int top = g + (int) Math.round(3 - 3.0 * s / Math.max(1, l));
                for (int y = g; y <= top; y++) {
                    set(rx, y, rz, wood);
                }
                set(rx, g - 1, rz, wood);
                if (s < l / 2) {
                    int sx = rx + (int) Math.signum(Math.round(-Math.sin(a)));
                    int sz = rz + (int) Math.signum(Math.round(Math.cos(a)));
                    for (int y = g; y <= Math.max(g, top - 1); y++) {
                        set(sx, y, sz, wood);
                    }
                }
            }
        }
    }

    /** A straight limb of the given thickness between two points; returns the end point. */
    private int[] limb(int x0, int y0, int z0, int x1, int y1, int z1, double thick, Material wood) {
        int n = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            int px = (int) Math.round(x0 + (x1 - x0) * t), py = (int) Math.round(y0 + (y1 - y0) * t), pz = (int) Math.round(z0 + (z1 - z0) * t);
            double r = thick * (1 - 0.35 * t);
            int reach = (int) Math.ceil(r);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dy = -reach; dy <= reach; dy++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= r * r + 0.4) {
                            set(px + dx, py + dy, pz + dz, wood);
                        }
                    }
                }
            }
        }
        return new int[] {x1, y1, z1};
    }

    /** A limb out of (x,y,z) at angle, rising by rise, with short sub-limbs; returns the tip. */
    private int[] branch(int x, int y, int z, double angle, int len, int rise, double thick, Material wood, int subs) {
        int tx = x + (int) Math.round(Math.cos(angle) * len), tz = z + (int) Math.round(Math.sin(angle) * len);
        int[] tip = limb(x, y, z, tx, y + rise, tz, thick, wood);
        for (int k = 0; k < subs; k++) {
            double t = 0.4 + 0.4 * rnd.nextDouble();
            int sx = x + (int) Math.round((tx - x) * t), sy = y + (int) Math.round(rise * t), sz = z + (int) Math.round((tz - z) * t);
            double a2 = angle + (rnd.nextBoolean() ? 1 : -1) * (0.6 + rnd.nextDouble() * 0.6);
            int l2 = Math.max(2, len / 2);
            limb(sx, sy, sz, sx + (int) Math.round(Math.cos(a2) * l2), sy + 1 + rnd.nextInt(2), sz + (int) Math.round(Math.sin(a2) * l2), Math.max(0, thick - 1), wood);
        }
        return tip;
    }

    /** An irregular ellipsoid of leaves; {@code holes} is the ragged-edge chance, {@code accent} a scatter of a second leaf. */
    private void blob(int cx, int cy, int cz, double rx, double ry, double rz, Material m, Material accent, double holes) {
        int ix = (int) Math.ceil(rx) + 1, iy = (int) Math.ceil(ry) + 1, iz = (int) Math.ceil(rz) + 1;
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    double e = dx * dx / (rx * rx) + dy * dy / (ry * ry) + dz * dz / (rz * rz);
                    if (e <= 1 - holes * rnd.nextDouble()) {
                        leaf(cx + dx, cy + dy, cz + dz, accent != null && rnd.nextDouble() < 0.09 ? accent : m);
                    }
                }
            }
        }
    }

    /** A crown: one main mass plus satellite clusters, each a little lower on the outside. */
    private void crown(int cx, int cy, int cz, double r, double ry, int satellites, Material m, Material accent) {
        blob(cx, cy, cz, r, ry, r, m, accent, 0.18);
        for (int k = 0; k < satellites; k++) {
            double a = k * Math.PI * 2 / satellites + rnd.nextDouble() * 0.6;
            double d = r * (0.55 + rnd.nextDouble() * 0.25);
            blob(cx + (int) Math.round(Math.cos(a) * d), cy - 1 - rnd.nextInt((int) Math.max(1, ry)), cz + (int) Math.round(Math.sin(a) * d),
                    r * 0.55, ry * 0.85, r * 0.55, m, accent, 0.22);
        }
    }

    /** Strands of leaves hanging from the underside of a crown ring. */
    private void strands(int cx, int cy, int cz, double rMin, double rMax, double chance, int minLen, int maxLen, Material m) {
        int reach = (int) Math.ceil(rMax);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double d = Math.hypot(dx, dz);
                if (d < rMin || d > rMax || rnd.nextDouble() > chance) {
                    continue;
                }
                int top = cy + 6;
                int y = top;
                while (y > cy - 8 && !(world.getBlockAt(cx + dx, y, cz + dz).getBlockData() instanceof Leaves)) {
                    y--;
                }
                if (y <= cy - 8) {
                    continue;
                }
                int len = minLen + rnd.nextInt(maxLen - minLen + 1);
                for (int i = 1; i <= len; i++) {
                    leaf(cx + dx, y - i, cz + dz, m);
                }
            }
        }
    }

    /** Moss carpet on some of the topmost leaves of a crown. */
    private void mossTop(int cx, int cy, int cz, int r, int chancePct) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (rnd.nextInt(100) >= chancePct) {
                    continue;
                }
                for (int y = cy + 6; y > cy - 6; y--) {
                    if (world.getBlockAt(cx + dx, y, cz + dz).getBlockData() instanceof Leaves) {
                        if (air(cx + dx, y + 1, cz + dz)) {
                            set(cx + dx, y + 1, cz + dz, Material.MOSS_CARPET);
                        }
                        break;
                    }
                }
            }
        }
    }

    /** Dresses the ground in a disc around the trunk. */
    private void ground(int cx, int cz, int g, int r, Material floor, Material... plants) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz) + (rnd.nextDouble() - 0.5) * 2;
                if (d > r) {
                    continue;
                }
                int x = cx + dx, z = cz + dz;
                if (world.getBlockAt(x, g, z).getType() == Material.GRASS_BLOCK && d < r * 0.8) {
                    set(x, g, z, rnd.nextDouble() < 0.25 ? Material.COARSE_DIRT : floor);
                }
                if (plants.length > 0 && air(x, g + 1, z) && solid(x, g, z) && rnd.nextDouble() < 0.28) {
                    set(x, g + 1, z, plants[rnd.nextInt(plants.length)]);
                }
            }
        }
    }

    private void boulders(int cx, int cz, int g, int n, int r) {
        Material[] stones = {Material.MOSSY_COBBLESTONE, Material.COBBLESTONE, Material.ANDESITE, Material.MOSSY_COBBLESTONE, Material.TUFF};
        for (int k = 0; k < n; k++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int bx = cx + (int) Math.round(Math.cos(a) * r), bz = cz + (int) Math.round(Math.sin(a) * r);
            int br = 1 + rnd.nextInt(2);
            for (int dx = -br; dx <= br; dx++) {
                for (int dz = -br; dz <= br; dz++) {
                    for (int dy = 0; dy <= br; dy++) {
                        if (Math.sqrt(dx * dx + dz * dz + dy * dy * 1.6) <= br + 0.3) {
                            set(bx + dx, g + dy, bz + dz, stones[rnd.nextInt(stones.length)]);
                        }
                    }
                }
            }
            if (air(bx, g + br + 1, bz)) {
                set(bx, g + br + 1, bz, Material.MOSS_CARPET);
            }
        }
    }

    private void fallenLog(int cx, int cz, int g, int dist, int len, Material log) {
        for (int i = 0; i < len; i++) {
            int x = cx + dist + i;
            if (air(x, g + 1, cz + 4)) {
                BlockData d = log.createBlockData();
                if (d instanceof Orientable o) {
                    o.setAxis(Axis.X);
                }
                world.getBlockAt(x, g + 1, cz + 4).setBlockData(d, false);
            }
        }
        if (air(cx + dist + 1, g + 2, cz + 4)) {
            set(cx + dist + 1, g + 2, cz + 4, Material.RED_MUSHROOM);
        }
    }

    // ---- the trees

    /** The tree of the old spawn, bigger and fuller: spruce-wood trunk, root flares, limbs, oak crown with azalea. */
    private void spawnTree(int x, int g) {
        int h = 12;
        roots(x, Z, g, 6, 4, 2, Material.SPRUCE_WOOD);
        int[] top = trunk(x, Z, g, h, 2.1, 1.3, 0, 0, Material.SPRUCE_WOOD, Material.DARK_OAK_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            tips.add(branch(x, g + h - 4, Z, k * Math.PI / 2 + rnd.nextDouble() * 0.6, 6, 3, 1, Material.SPRUCE_WOOD, 1));
        }
        crown(top[0], top[1] + 3, top[2], 6, 3.4, 5, Material.OAK_LEAVES, Material.FLOWERING_AZALEA_LEAVES);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 4, 2.6, 3, Material.OAK_LEAVES, Material.FLOWERING_AZALEA_LEAVES);
        }
        mossTop(x, g + h + 3, Z, 7, 12);
        ground(x, Z, g, 8, Material.PODZOL, Material.FERN, Material.SHORT_GRASS, Material.SHORT_GRASS, Material.BROWN_MUSHROOM, Material.POPPY);
        boulders(x, Z, g, 2, 7);
    }

    /** A mighty world tree: fat trunk, buttress roots, eight limbs with sub-limbs and a vast crown. */
    private void worldTree(int x, int g) {
        int h = 30;
        roots(x, Z, g, 10, 8, 4, Material.SPRUCE_WOOD);
        int[] top = trunk(x, Z, g, h, 4.4, 2.6, 0, 0, Material.SPRUCE_WOOD, Material.DARK_OAK_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            int y0 = g + h - 13 + (k % 3) * 3;
            tips.add(branch(x, y0, Z, k * Math.PI / 4 + rnd.nextDouble() * 0.4, 11, 5, 2, Material.SPRUCE_WOOD, 2));
        }
        crown(top[0], top[1] + 3, top[2], 11, 5.5, 8, Material.OAK_LEAVES, Material.FLOWERING_AZALEA_LEAVES);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 5.5, 3.2, 3, Material.OAK_LEAVES, Material.FLOWERING_AZALEA_LEAVES);
        }
        strands(x, g + h - 3, Z, 6, 13, 0.25, 3, 8, Material.OAK_LEAVES);
        mossTop(x, g + h + 3, Z, 12, 14);
        ground(x, Z, g, 12, Material.MOSS_BLOCK, Material.FERN, Material.SHORT_GRASS, Material.AZALEA, Material.BROWN_MUSHROOM, Material.LILY_OF_THE_VALLEY, Material.ALLIUM);
        boulders(x, Z, g, 4, 11);
        fallenLog(x, Z, g, 6, 6, Material.SPRUCE_LOG);
    }

    private void cherry(int x, int g) {
        int h = 10;
        roots(x, Z, g, 5, 3, 1, Material.CHERRY_WOOD);
        int[] top = trunk(x, Z, g, h, 1.7, 1.0, 2, 1, Material.CHERRY_WOOD, Material.STRIPPED_CHERRY_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            tips.add(branch(top[0], g + h - 4 + (k % 2), top[2], k * Math.PI / 3 + rnd.nextDouble() * 0.5, 6, 2, 1, Material.CHERRY_WOOD, 1));
        }
        crown(top[0], top[1] + 2, top[2], 6, 2.8, 5, Material.CHERRY_LEAVES, null);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 4.2, 2.4, 3, Material.CHERRY_LEAVES, null);
        }
        strands(top[0], g + h, top[2], 3, 8, 0.2, 2, 4, Material.CHERRY_LEAVES);
        ground(x, Z, g, 9, Material.PODZOL, Material.PINK_PETALS, Material.PINK_PETALS, Material.SHORT_GRASS, Material.PEONY, Material.PINK_TULIP);
    }

    private void tallBirch(int x, int g) {
        int h = 17;
        roots(x, Z, g, 4, 3, 1, Material.BIRCH_WOOD);
        int[] top = trunk(x, Z, g, h, 1.5, 0.8, -1, 1, Material.BIRCH_WOOD, Material.STRIPPED_BIRCH_WOOD);
        for (int k = 0; k < 5; k++) {
            int y = g + h - 11 + k * 2;
            int[] tip = branch(top[0], y, top[2], k * 2.4, 4 + (k % 2), 1, 0, Material.BIRCH_WOOD, 0);
            crown(tip[0], tip[1], tip[2], 3.6, 1.8, 2, Material.BIRCH_LEAVES, null);
        }
        crown(top[0], top[1] + 2, top[2], 4.5, 3, 4, Material.BIRCH_LEAVES, null);
        ground(x, Z, g, 6, Material.GRASS_BLOCK, Material.SHORT_GRASS, Material.LILY_OF_THE_VALLEY, Material.OXEYE_DAISY, Material.WHITE_TULIP, Material.FERN);
        fallenLog(x, Z, g, 5, 5, Material.BIRCH_LOG);
    }

    private void darkOak(int x, int g) {
        int h = 10;
        roots(x, Z, g, 8, 5, 2, Material.DARK_OAK_WOOD);
        int[] top = trunk(x, Z, g, h, 2.6, 1.9, 0, 0, Material.DARK_OAK_WOOD, Material.SPRUCE_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            tips.add(branch(x, g + h - 3, Z, k * Math.PI / 3, 8, 2, 1, Material.DARK_OAK_WOOD, 1));
        }
        blob(top[0], top[1] + 3, top[2], 11, 3.6, 11, Material.DARK_OAK_LEAVES, null, 0.22);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 5, 2.4, 3, Material.DARK_OAK_LEAVES, null);
        }
        strands(x, g + h, Z, 5, 11, 0.12, 2, 4, Material.DARK_OAK_LEAVES);
        ground(x, Z, g, 10, Material.PODZOL, Material.RED_MUSHROOM, Material.BROWN_MUSHROOM, Material.FERN, Material.SHORT_GRASS);
        boulders(x, Z, g, 3, 9);
    }

    private void spruce(int x, int g) {
        int h = 26;
        roots(x, Z, g, 6, 4, 1, Material.SPRUCE_WOOD);
        trunk(x, Z, g, h, 1.6, 0.5, 0, 0, Material.SPRUCE_WOOD, Material.DARK_OAK_WOOD);
        for (int y = g + 4; y <= g + h + 2; y++) {
            int k = g + h - y;
            int tier = (y - g) % 4;
            double r = Math.min(8, k * 0.3 + (tier == 0 ? 2.4 : tier == 1 ? 1.2 : 0.3));
            int reach = (int) Math.ceil(r) + 1;
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d <= r + (rnd.nextDouble() - 0.5) * 0.8) {
                        leaf(x + dx, y, Z + dz, Material.SPRUCE_LEAVES);
                        if (tier == 0 && d > r - 1.2 && rnd.nextDouble() < 0.5) {
                            leaf(x + dx, y - 1, Z + dz, Material.SPRUCE_LEAVES);
                        }
                    }
                }
            }
        }
        leaf(x, g + h + 3, Z, Material.SPRUCE_LEAVES);
        ground(x, Z, g, 9, Material.PODZOL, Material.FERN, Material.FERN, Material.BROWN_MUSHROOM, Material.SWEET_BERRY_BUSH);
        boulders(x, Z, g, 2, 8);
    }

    private void acacia(int x, int g) {
        roots(x, Z, g, 5, 3, 1, Material.ACACIA_WOOD);
        int[] low = trunk(x, Z, g, 6, 1.7, 1.4, 2, 0, Material.ACACIA_WOOD, Material.STRIPPED_ACACIA_WOOD);
        int[] a = limb(low[0], low[1], low[2], low[0] + 6, low[1] + 6, low[2] + 2, 1.3, Material.ACACIA_WOOD);
        int[] b = limb(low[0], low[1], low[2], low[0] - 6, low[1] + 5, low[2] - 2, 1.2, Material.ACACIA_WOOD);
        for (int[] t : new int[][] {a, b}) {
            blob(t[0], t[1] + 1, t[2], 8, 1.8, 7, Material.ACACIA_LEAVES, null, 0.3);
            blob(t[0], t[1] + 3, t[2], 5, 1.4, 4.5, Material.ACACIA_LEAVES, null, 0.3);
        }
        ground(x, Z, g, 8, Material.COARSE_DIRT, Material.SHORT_GRASS, Material.DEAD_BUSH, Material.SHORT_GRASS);
        boulders(x, Z, g, 3, 8);
    }

    private void willow(int x, int g) {
        int h = 12;
        roots(x, Z, g, 7, 5, 2, Material.OAK_WOOD);
        int[] top = trunk(x, Z, g, h, 2.2, 1.4, 2, -1, Material.OAK_WOOD, Material.SPRUCE_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            tips.add(branch(top[0], g + h - 3, top[2], k * Math.PI * 2 / 5, 6, 2, 1, Material.OAK_WOOD, 1));
        }
        crown(top[0], top[1] + 2, top[2], 7, 3.2, 5, Material.OAK_LEAVES, null);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 4.4, 2.4, 3, Material.OAK_LEAVES, null);
        }
        strands(top[0], g + h, top[2], 3, 11, 0.55, 5, 11, Material.OAK_LEAVES);
        ground(x, Z, g, 10, Material.GRASS_BLOCK, Material.SHORT_GRASS, Material.SHORT_GRASS, Material.FERN, Material.BLUE_ORCHID, Material.LILY_OF_THE_VALLEY);
        boulders(x, Z, g, 2, 8);
    }

    private void mangrove(int x, int g) {
        int h = 11;
        int[] top = trunk(x, Z, g + 3, h - 3, 1.6, 1.2, 1, 0, Material.MANGROVE_WOOD, Material.MANGROVE_WOOD);
        for (int k = 0; k < 9; k++) {
            double a = k * Math.PI * 2 / 9 + rnd.nextDouble() * 0.3;
            int len = 5 + rnd.nextInt(3);
            int sx = x + (int) Math.round(Math.cos(a) * 1.5), sz = Z + (int) Math.round(Math.sin(a) * 1.5);
            int ex = x + (int) Math.round(Math.cos(a) * len), ez = Z + (int) Math.round(Math.sin(a) * len);
            limb(sx, g + 6, sz, ex, g, ez, 0.6, Material.MANGROVE_ROOTS);
            set(ex, g, ez, Material.MUDDY_MANGROVE_ROOTS);
        }
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            tips.add(branch(top[0], g + h - 3, top[2], k * Math.PI / 2 + 0.6, 5, 2, 1, Material.MANGROVE_WOOD, 1));
        }
        crown(top[0], top[1] + 2, top[2], 7, 3, 5, Material.MANGROVE_LEAVES, null);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 4, 2.2, 3, Material.MANGROVE_LEAVES, null);
        }
        ground(x, Z, g, 9, Material.MUD, Material.MANGROVE_PROPAGULE, Material.SHORT_GRASS, Material.BROWN_MUSHROOM);
    }

    private void jungleGiant(int x, int g) {
        int h = 26;
        roots(x, Z, g, 8, 7, 3, Material.JUNGLE_WOOD);
        int[] top = trunk(x, Z, g, h, 3.2, 1.6, 0, 0, Material.JUNGLE_WOOD, Material.SPRUCE_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            tips.add(branch(x, g + h - 9 + (k % 2) * 3, Z, k * Math.PI / 3 + rnd.nextDouble() * 0.4, 9, 4, 2, Material.JUNGLE_WOOD, 2));
        }
        for (int layer = 0; layer < 3; layer++) {
            blob(top[0], top[1] + 4 - layer * 4, top[2], 11 - layer * 2, 2.6, 11 - layer * 2, Material.JUNGLE_LEAVES, null, 0.25);
        }
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 6, 2.8, 4, Material.JUNGLE_LEAVES, null);
        }
        strands(x, g + h - 4, Z, 5, 12, 0.3, 4, 9, Material.JUNGLE_LEAVES);
        ground(x, Z, g, 11, Material.MOSS_BLOCK, Material.FERN, Material.FERN, Material.SHORT_GRASS, Material.BROWN_MUSHROOM, Material.AZALEA);
        boulders(x, Z, g, 3, 10);
    }

    private void paleOak(int x, int g) {
        int h = 14;
        roots(x, Z, g, 7, 5, 2, Material.PALE_OAK_WOOD);
        int[] top = trunk(x, Z, g, h, 2.3, 1.3, -2, 1, Material.PALE_OAK_WOOD, Material.STRIPPED_PALE_OAK_WOOD);
        List<int[]> tips = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            tips.add(branch(top[0], g + h - 5 + (k % 2) * 2, top[2], k * Math.PI / 3 + rnd.nextDouble() * 0.5, 7, 3, 1, Material.PALE_OAK_WOOD, 2));
        }
        crown(top[0], top[1] + 3, top[2], 8, 3.2, 6, Material.PALE_OAK_LEAVES, null);
        for (int[] t : tips) {
            crown(t[0], t[1] + 1, t[2], 4.6, 2.4, 3, Material.PALE_OAK_LEAVES, null);
        }
        strands(top[0], g + h, top[2], 4, 10, 0.3, 3, 7, Material.PALE_OAK_LEAVES);
        ground(x, Z, g, 10, Material.PODZOL, Material.PALE_MOSS_CARPET, Material.PALE_MOSS_CARPET, Material.CLOSED_EYEBLOSSOM, Material.BROWN_MUSHROOM, Material.FERN);
        boulders(x, Z, g, 3, 9);
    }
}
