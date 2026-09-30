package se.nordia.swedencore.paper.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

import java.util.Random;

/**
 * A showcase row of hand-shaped trees in the build world: copy the ones you like to spawn. Every tree is seeded, so
 * the row looks the same each time it is planted.
 */
final class BuildTrees {

    static final int SPACING = 24;
    static final int FIRST_X = 14;
    static final int Z = 16;
    private static final int CLEAR_RADIUS = SPACING / 2;
    private static final int CLEAR_HEIGHT = 34;
    static final int COUNT = 11;

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
            ground(x, g, i == 2 ? Material.PODZOL : Material.GRASS_BLOCK);
        }
    }

    private void clearSlot(int cx, int g) {
        for (int x = cx - CLEAR_RADIUS; x <= cx + CLEAR_RADIUS; x++) {
            for (int z = Z - CLEAR_RADIUS; z <= Z + CLEAR_RADIUS; z++) {
                for (int y = g + 1; y <= g + CLEAR_HEIGHT; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void ground(int cx, int g, Material top) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.hypot(dx, dz) < 3.2 && rnd.nextDouble() < 0.6) {
                    world.getBlockAt(cx + dx, g, Z + dz).setType(top, false);
                }
            }
        }
    }

    // ---- helpers

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
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

    /** An irregular ellipsoid of leaves; {@code holes} is the chance of a ragged edge. */
    private void blob(int cx, int cy, int cz, double rx, double ry, double rz, Material m, double holes) {
        int ix = (int) Math.ceil(rx) + 1, iy = (int) Math.ceil(ry) + 1, iz = (int) Math.ceil(rz) + 1;
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    double e = dx * dx / (rx * rx) + dy * dy / (ry * ry) + dz * dz / (rz * rz);
                    if (e <= 1 - holes * rnd.nextDouble()) {
                        leaf(cx + dx, cy + dy, cz + dz, m);
                    }
                }
            }
        }
    }

    private void blob(int cx, int cy, int cz, double r, double ry, Material m, double holes) {
        blob(cx, cy, cz, r, ry, r, m, holes);
    }

    private void trunk(int x, int g, int z, int h, Material wood) {
        for (int y = g + 1; y <= g + h; y++) {
            set(x, y, z, wood);
        }
    }

    private void trunk2(int x, int g, int z, int h, Material wood) {
        for (int y = g; y <= g + h; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    set(x + dx, y, z + dz, wood);
                }
            }
        }
    }

    /** A rising limb from (x,y,z) in direction angle; returns the tip. */
    private int[] limb(int x, int y, int z, double angle, int len, Material wood) {
        int tx = x, ty = y, tz = z;
        for (int s = 1; s <= len; s++) {
            tx = x + (int) Math.round(Math.cos(angle) * s);
            tz = z + (int) Math.round(Math.sin(angle) * s);
            ty = y + s / 2;
            set(tx, ty, tz, wood);
        }
        return new int[] {tx, ty + 1, tz};
    }

    // ---- the trees

    /** The tree of the old spawn: thick trunk, root flares, a few limbs, oak crown with a little azalea. */
    private void spawnTree(int x, int g) {
        int h = 8;
        trunk(x, g, Z, h, Material.SPRUCE_WOOD);
        int[][] flares = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] f : flares) {
            if (rnd.nextDouble() < 0.75) {
                set(x + f[0], g + 1, Z + f[1], Material.SPRUCE_WOOD);
            }
        }
        blob(x, g + h + 1, Z, 4, 2.6, Material.OAK_LEAVES, 0.2);
        for (int k = 0; k < 3; k++) {
            int[] tip = limb(x, g + h - 2, Z, rnd.nextDouble() * Math.PI * 2, 4, Material.SPRUCE_WOOD);
            blob(tip[0], tip[1], tip[2], 3.4, 2.3, Material.OAK_LEAVES, 0.2);
        }
        sprinkle(x, g + h + 1, Z, 5, Material.FLOWERING_AZALEA_LEAVES, 14);
    }

    /** A mighty world tree: 3x3 trunk, root spokes, six limbs each with its own crown. */
    private void worldTree(int x, int g) {
        int h = 22;
        for (int y = g + 1; y <= g + h; y++) {
            int r = y - g < 6 ? 1 : (y - g < 15 ? 1 : 0);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    set(x + dx, y, Z + dz, Material.SPRUCE_WOOD);
                }
            }
        }
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            for (int s = 2; s <= 5; s++) {
                int rx = x + (int) Math.round(Math.cos(a) * s), rz = Z + (int) Math.round(Math.sin(a) * s);
                set(rx, g + (s < 4 ? 2 : 1), rz, Material.SPRUCE_WOOD);
            }
        }
        for (int k = 0; k < 6; k++) {
            int[] tip = limb(x, g + h - 7 - rnd.nextInt(3), Z, k * Math.PI / 3 + rnd.nextDouble() * 0.4, 8, Material.SPRUCE_WOOD);
            blob(tip[0], tip[1] + 1, tip[2], 5, 3.2, Material.OAK_LEAVES, 0.25);
        }
        blob(x, g + h + 2, Z, 8, 4.5, Material.OAK_LEAVES, 0.25);
        sprinkle(x, g + h + 2, Z, 9, Material.FLOWERING_AZALEA_LEAVES, 10);
    }

    private void cherry(int x, int g) {
        int h = 7;
        trunk(x, g, Z, h, Material.CHERRY_WOOD);
        for (int k = 0; k < 4; k++) {
            int[] tip = limb(x, g + h - 3, Z, k * Math.PI / 2 + 0.5, 4, Material.CHERRY_WOOD);
            blob(tip[0], tip[1], tip[2], 3.6, 2.2, Material.CHERRY_LEAVES, 0.25);
        }
        blob(x, g + h + 1, Z, 4.2, 2.4, Material.CHERRY_LEAVES, 0.25);
        for (int i = 0; i < 40; i++) {
            int px = x + rnd.nextInt(11) - 5, pz = Z + rnd.nextInt(11) - 5;
            Block b = world.getBlockAt(px, g + 1, pz);
            if (b.getType().isAir() && world.getBlockAt(px, g, pz).getType().isSolid()) {
                b.setType(Material.PINK_PETALS, false);
            }
        }
    }

    private void tallBirch(int x, int g) {
        int h = 13;
        trunk(x, g, Z, h, Material.BIRCH_WOOD);
        for (int k = 0; k < 4; k++) {
            blob(x + rnd.nextInt(5) - 2, g + h - 1 - k * 2, Z + rnd.nextInt(5) - 2, 3.2 - k * 0.3, 1.4, Material.BIRCH_LEAVES, 0.4);
        }
        blob(x, g + h + 1, Z, 2.4, 2, Material.BIRCH_LEAVES, 0.3);
    }

    private void darkOak(int x, int g) {
        trunk2(x, g, Z, 8, Material.DARK_OAK_WOOD);
        blob(x, g + 10, Z, 7, 2.6, Material.DARK_OAK_LEAVES, 0.25);
        for (int k = 0; k < 4; k++) {
            int[] tip = limb(x, g + 6, Z, k * Math.PI / 2, 5, Material.DARK_OAK_WOOD);
            blob(tip[0], tip[1], tip[2], 4, 2, Material.DARK_OAK_LEAVES, 0.25);
        }
    }

    private void spruce(int x, int g) {
        int h = 17;
        trunk(x, g, Z, h, Material.SPRUCE_WOOD);
        for (int y = g + 3; y <= g + h + 1; y++) {
            int k = g + h - y;
            int r = Math.min(4, (int) Math.round(k * 0.35 + ((y & 1) == 0 ? 0.8 : 0)));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= r + 0.5) {
                        leaf(x + dx, y, Z + dz, Material.SPRUCE_LEAVES);
                    }
                }
            }
        }
        leaf(x, g + h + 2, Z, Material.SPRUCE_LEAVES);
    }

    private void acacia(int x, int g) {
        trunk(x, g, Z, 4, Material.ACACIA_WOOD);
        int cx = x, cy = g + 4;
        for (int s = 1; s <= 3; s++) {
            cx += s % 2;
            cy++;
            set(cx, cy, Z, Material.ACACIA_WOOD);
            set(cx, cy + 1, Z, Material.ACACIA_WOOD);
        }
        blob(cx, cy + 2, Z, 6, 1.3, Material.ACACIA_LEAVES, 0.3);
        int bx = x - 1, by = g + 5;
        for (int s = 0; s < 3; s++) {
            set(bx - s, by + s, Z, Material.ACACIA_WOOD);
        }
        blob(bx - 3, by + 3, Z, 4, 1.2, Material.ACACIA_LEAVES, 0.3);
    }

    private void willow(int x, int g) {
        int h = 8;
        trunk(x, g, Z, h, Material.OAK_WOOD);
        blob(x, g + h + 1, Z, 6, 3, Material.OAK_LEAVES, 0.2);
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double d = Math.hypot(dx, dz);
                if (d < 4 || d > 6.3 || rnd.nextDouble() > 0.6) {
                    continue;
                }
                int len = 3 + rnd.nextInt(5);
                for (int y = 0; y < len; y++) {
                    leaf(x + dx, g + h - 1 - y, Z + dz, Material.OAK_LEAVES);
                }
            }
        }
    }

    private void mangrove(int x, int g) {
        int h = 8;
        trunk(x, g + 1, Z, h, Material.MANGROVE_WOOD);
        for (int k = 0; k < 6; k++) {
            double a = k * Math.PI / 3 + 0.3;
            for (int s = 1; s <= 4; s++) {
                int rx = x + (int) Math.round(Math.cos(a) * s), rz = Z + (int) Math.round(Math.sin(a) * s);
                for (int y = g + 4 - s; y <= g + 5 - s + (s == 1 ? 1 : 0); y++) {
                    if (y > g) {
                        set(rx, y, rz, Material.MANGROVE_ROOTS);
                    }
                }
            }
        }
        blob(x, g + h + 2, Z, 5.5, 2.6, Material.MANGROVE_LEAVES, 0.25);
        for (int k = 0; k < 3; k++) {
            int[] tip = limb(x, g + h - 2, Z, rnd.nextDouble() * Math.PI * 2, 4, Material.MANGROVE_WOOD);
            blob(tip[0], tip[1], tip[2], 3.2, 1.8, Material.MANGROVE_LEAVES, 0.25);
        }
    }

    private void jungleGiant(int x, int g) {
        trunk2(x, g, Z, 20, Material.JUNGLE_WOOD);
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + Math.PI / 4;
            for (int s = 2; s <= 3; s++) {
                set(x + (int) Math.round(Math.cos(a) * s), g + (s == 2 ? 2 : 1), Z + (int) Math.round(Math.sin(a) * s), Material.JUNGLE_WOOD);
            }
        }
        blob(x, g + 22, Z, 8, 3, Material.JUNGLE_LEAVES, 0.25);
        for (int k = 0; k < 4; k++) {
            int[] tip = limb(x, g + 15, Z, k * Math.PI / 2, 6, Material.JUNGLE_WOOD);
            blob(tip[0], tip[1], tip[2], 5, 2.4, Material.JUNGLE_LEAVES, 0.25);
        }
    }

    private void paleOak(int x, int g) {
        int h = 10;
        trunk2(x, g, Z, h, Material.PALE_OAK_WOOD);
        for (int k = 0; k < 4; k++) {
            int[] tip = limb(x, g + h - 3, Z, k * Math.PI / 2 + 0.4, 5, Material.PALE_OAK_WOOD);
            blob(tip[0], tip[1], tip[2], 4, 1.8, Material.PALE_OAK_LEAVES, 0.3);
        }
        blob(x, g + h + 2, Z, 6, 2.2, Material.PALE_OAK_LEAVES, 0.3);
    }

    /** Replaces about one in {@code oneIn} existing leaves around a point with another leaf type. */
    private void sprinkle(int cx, int cy, int cz, int r, Material m, int oneIn) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() == Material.OAK_LEAVES && rnd.nextInt(oneIn) == 0) {
                        BlockData data = m.createBlockData();
                        if (data instanceof Leaves leaves) {
                            leaves.setPersistent(true);
                        }
                        b.setBlockData(data, false);
                    }
                }
            }
        }
    }
}
