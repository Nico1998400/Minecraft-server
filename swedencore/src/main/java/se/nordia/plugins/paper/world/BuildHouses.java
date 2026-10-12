package se.nordia.plugins.paper.world;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;

import static org.bukkit.Material.AIR;
import static org.bukkit.Material.BARREL;
import static org.bukkit.Material.BIRCH_PLANKS;
import static org.bukkit.Material.BLAST_FURNACE;
import static org.bukkit.Material.BLUE_BED;
import static org.bukkit.Material.BOOKSHELF;
import static org.bukkit.Material.BRICKS;
import static org.bukkit.Material.BRICK_STAIRS;
import static org.bukkit.Material.CAMPFIRE;
import static org.bukkit.Material.CAULDRON;
import static org.bukkit.Material.CHEST;
import static org.bukkit.Material.COBBLESTONE;
import static org.bukkit.Material.COBBLESTONE_SLAB;
import static org.bukkit.Material.COBBLESTONE_STAIRS;
import static org.bukkit.Material.COMPOSTER;
import static org.bukkit.Material.CRAFTING_TABLE;
import static org.bukkit.Material.DARK_OAK_DOOR;
import static org.bukkit.Material.DARK_OAK_FENCE;
import static org.bukkit.Material.DARK_OAK_LOG;
import static org.bukkit.Material.DARK_OAK_PLANKS;
import static org.bukkit.Material.DARK_OAK_SLAB;
import static org.bukkit.Material.DARK_OAK_STAIRS;
import static org.bukkit.Material.DEEPSLATE_TILES;
import static org.bukkit.Material.DEEPSLATE_TILE_STAIRS;
import static org.bukkit.Material.FURNACE;
import static org.bukkit.Material.GREEN_BED;
import static org.bukkit.Material.GRINDSTONE;
import static org.bukkit.Material.LADDER;
import static org.bukkit.Material.LECTERN;
import static org.bukkit.Material.LOOM;
import static org.bukkit.Material.MOSSY_COBBLESTONE;
import static org.bukkit.Material.MOSS_BLOCK;
import static org.bukkit.Material.POLISHED_BLACKSTONE_BRICKS;
import static org.bukkit.Material.POLISHED_BLACKSTONE_BRICK_STAIRS;
import static org.bukkit.Material.RED_BED;
import static org.bukkit.Material.RED_CONCRETE;
import static org.bukkit.Material.RED_TERRACOTTA;
import static org.bukkit.Material.RED_WOOL;
import static org.bukkit.Material.SMITHING_TABLE;
import static org.bukkit.Material.SMOKER;
import static org.bukkit.Material.SPRUCE_DOOR;
import static org.bukkit.Material.SPRUCE_FENCE;
import static org.bukkit.Material.SPRUCE_LOG;
import static org.bukkit.Material.SPRUCE_PLANKS;
import static org.bukkit.Material.SPRUCE_SLAB;
import static org.bukkit.Material.SPRUCE_STAIRS;
import static org.bukkit.Material.SPRUCE_TRAPDOOR;
import static org.bukkit.Material.STONE_BRICKS;
import static org.bukkit.Material.STONE_BRICK_SLAB;
import static org.bukkit.Material.STONE_BRICK_STAIRS;
import static org.bukkit.Material.STRIPPED_SPRUCE_LOG;
import static org.bukkit.Material.WHITE_BED;
import static org.bukkit.Material.WHITE_CONCRETE;
import static org.bukkit.Material.WHITE_WOOL;
import static org.bukkit.block.BlockFace.EAST;
import static org.bukkit.block.BlockFace.NORTH;
import static org.bukkit.block.BlockFace.SOUTH;
import static org.bukkit.block.BlockFace.WEST;

/**
 * Ten livable houses in ten styles, in a row beside the tree showcase. Each has a door, windows, a roof, light, beds,
 * a table and storage, and sits in its own fenced lot on a shared street. Style follows the spawn: stone-brick bases,
 * spruce timber frames, plaster, dark oak roofs and round towers with red cone roofs.
 */
final class BuildHouses extends HouseKit {

    static final int COUNT = 10;
    static final int FIRST_X = 16;
    static final int STRIDE = 36;
    static final int LOT_W = 32;
    static final int LOT_D = 26;
    static final int LOT_Z = 62;
    static final int STREET_Z = 54;

    /** The last x the row reaches, for messages. */
    static int lastX() {
        return FIRST_X + (COUNT - 1) * STRIDE + LOT_W;
    }

    private int g;

    BuildHouses(World world) {
        super(world);
    }

    /** Builds all ten lots. With {@code clear}, every lot is emptied first (re-building). */
    void build(boolean clear) {
        g = w.getHighestBlockYAt(FIRST_X - 12, LOT_Z);
        for (int i = 0; i < COUNT; i++) {
            int ox = FIRST_X + i * STRIDE, oz = LOT_Z;
            rnd.setSeed(9100L + i * 17L);
            clearLot(ox, oz);
            street(ox);
            int[] door = switch (i) {
                case 0 -> longhouse(ox, oz);
                case 1 -> timberHouse(ox, oz);
                case 2 -> stoneCottage(ox, oz);
                case 3 -> redCottage(ox, oz);
                case 4 -> manor(ox, oz);
                case 5 -> inn(ox, oz);
                case 6 -> stiltHouse(ox, oz);
                case 7 -> roundTower(ox, oz);
                case 8 -> smithy(ox, oz);
                default -> bakery(ox, oz);
            };
            path(door[0], door[1]);
            lotFence(ox, oz, door[0]);
            garden(ox, oz);
            connect(ox - 2, g, oz - 8, ox + LOT_W + 2, g + 12, oz + LOT_D + 2);
        }
    }

    // ---- lot, street, garden

    private void clearLot(int ox, int oz) {
        for (int x = ox - 2; x <= ox + STRIDE - 4; x++) {
            for (int z = oz - 12; z <= oz + LOT_D + 2; z++) {
                for (int y = g + 1; y <= g + 34; y++) {
                    if (!air(x, y, z)) {
                        b(x, y, z, AIR);
                    }
                }
                b(x, g, z, Material.GRASS_BLOCK);
                b(x, g - 1, z, Material.DIRT);
            }
        }
    }

    private void street(int ox) {
        for (int x = ox - 2; x <= ox + STRIDE - 4; x++) {
            for (int z = STREET_Z - 2; z <= STREET_Z + 2; z++) {
                b(x, g, z, rnd.nextInt(6) == 0 ? Material.COARSE_DIRT : Material.DIRT_PATH);
            }
            b(x, g, STREET_Z - 3, COBBLESTONE);
            b(x, g, STREET_Z + 3, COBBLESTONE);
        }
        for (int lx : new int[] {ox + 2, ox + 18}) {
            for (int z : new int[] {STREET_Z - 4, STREET_Z + 4}) {
                fill(lx, g + 1, z, lx, g + 3, z, DARK_OAK_FENCE);
                lantern(lx, g + 4, z, false);
            }
        }
    }

    /** A 2-wide path from the street to the door. */
    private void path(int doorX, int endZ) {
        for (int z = STREET_Z + 3; z <= endZ; z++) {
            for (int x = doorX; x <= doorX + 1; x++) {
                if (at(x, g, z) == Material.GRASS_BLOCK || at(x, g, z) == Material.DIRT) {
                    b(x, g, z, rnd.nextInt(4) == 0 ? COBBLESTONE : Material.DIRT_PATH);
                }
            }
        }
    }

    private void lotFence(int ox, int oz, int doorX) {
        int zBack = oz + LOT_D - 1;
        for (int z = oz + 1; z <= zBack; z++) {
            b(ox, g + 1, z, SPRUCE_FENCE);
            b(ox + LOT_W - 1, g + 1, z, SPRUCE_FENCE);
        }
        for (int x = ox; x < ox + LOT_W; x++) {
            b(x, g + 1, zBack, SPRUCE_FENCE);
            if (Math.abs(x - doorX) > 2 && Math.abs(x - doorX - 1) > 2) {
                b(x, g + 1, oz, SPRUCE_FENCE);
            }
        }
        for (int x : new int[] {ox, ox + LOT_W - 1}) {
            fill(x, g + 2, oz, x, g + 2, oz, SPRUCE_FENCE);
            lantern(x, g + 3, oz, false);
        }
    }

    private void garden(int ox, int oz) {
        Material[] flowers = {Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.ALLIUM,
                Material.RED_TULIP, Material.AZURE_BLUET, Material.LILY_OF_THE_VALLEY};
        for (int x = ox + 1; x < ox + LOT_W - 1; x++) {
            for (int z = oz + 1; z < oz + LOT_D - 1; z++) {
                if (at(x, g, z) != Material.GRASS_BLOCK || !air(x, g + 1, z)) {
                    continue;
                }
                double r = rnd.nextDouble();
                if (r < 0.10) {
                    b(x, g + 1, z, Material.SHORT_GRASS);
                } else if (r < 0.15) {
                    b(x, g + 1, z, flowers[rnd.nextInt(flowers.length)]);
                } else if (r < 0.165) {
                    b(x, g + 1, z, Material.FERN);
                } else if (r < 0.175) {
                    b(x, g + 1, z, Material.SWEET_BERRY_BUSH);
                }
            }
        }
        for (int k = 0; k < 7; k++) {
            int bx = ox + 2 + rnd.nextInt(LOT_W - 4), bz = oz + LOT_D - 4 + rnd.nextInt(3);
            if (at(bx, g, bz) == Material.GRASS_BLOCK && air(bx, g + 1, bz)) {
                bush(bx, bz);
            }
        }
    }

    private void bush(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 1; dy <= 2; dy++) {
                    if (Math.abs(dx) + Math.abs(dz) + dy <= 3 && air(x + dx, g + dy, z + dz)) {
                        Leaves l = (Leaves) Material.OAK_LEAVES.createBlockData();
                        l.setPersistent(true);
                        data(x + dx, g + dy, z + dz, l);
                    }
                }
            }
        }
    }

    // ---- shared pieces

    private void beamRing(int x0, int z0, int x1, int z1, int y, Material m) {
        for (int x = x0; x <= x1; x++) {
            log(x, y, z0, m, Axis.X);
            log(x, y, z1, m, Axis.X);
        }
        for (int z = z0; z <= z1; z++) {
            log(x0, y, z, m, Axis.Z);
            log(x1, y, z, m, Axis.Z);
        }
    }

    /** Straight stairs from floor level to a floor one storey (4 blocks) up, climbing in {@code dir}. */
    private void stairsUp(int x, int z, BlockFace dir, Material m) {
        for (int i = 0; i < 4; i++) {
            stair(x + dir.getModX() * i, g + 1 + i, z + dir.getModZ() * i, m, dir, false);
        }
        for (int i = 1; i <= 2; i++) {
            b(x + dir.getModX() * i, g + 4, z + dir.getModZ() * i, AIR);
        }
    }

    private void weather(Material m, int x, int y, int z, int n) {
        // scatter of the same block for mossy/cracked looks
        if (n > 0 && rnd.nextInt(n) == 0) {
            b(x, y, z, m);
        }
    }

    private void mossy(int x0, int z0, int x1, int z1, int y1, int y2, Material from, Material to, int oneIn) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (at(x, y, z) == from) {
                        weather(to, x, y, z, oneIn);
                    }
                }
            }
        }
    }

    private void bedAlong(int x, int z, Material bed, BlockFace head) {
        bed(x, g + 1, z, bed, head);
    }

    /** Hanging lanterns at the given x positions under a ceiling one block above {@code y}. */
    private void hang(int y, int z, int... xs) {
        for (int x : xs) {
            lantern(x, y, z, true);
        }
    }

    // ---- 1. Longhouse: Viking hall with a hearth in the middle and beds along the back

    private int[] longhouse(int ox, int oz) {
        int x0 = ox + 5, x1 = x0 + 20, z0 = oz + 8, z1 = z0 + 8;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, COBBLESTONE);
        ring(x0, z0, x1, z1, g + 1, g + 4, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g + 1, g + 1, STONE_BRICKS);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 4, z1 - 1, AIR);
        for (int x = x0; x <= x1; x += 4) {
            fill(x, g + 1, z0, x, g + 4, z0, STRIPPED_SPRUCE_LOG);
            fill(x, g + 1, z1, x, g + 4, z1, STRIPPED_SPRUCE_LOG);
        }
        for (int z : new int[] {z0, z1}) {
            for (int x = x0; x <= x1; x++) {
                if ((x - x0) % 4 != 0) {
                    log(x, g + 4, z, SPRUCE_LOG, Axis.X);
                }
            }
        }
        for (int z = z0; z <= z1; z++) {
            log(x0, g + 4, z, SPRUCE_LOG, Axis.Z);
            log(x1, g + 4, z, SPRUCE_LOG, Axis.Z);
        }
        mossy(x0, z0, x1, z1, g + 1, g + 1, STONE_BRICKS, MOSSY_COBBLESTONE, 4);
        int ridge = gable(x0, z0, x1, z1, g + 5, true, SPRUCE_STAIRS, DARK_OAK_PLANKS, SPRUCE_PLANKS, DARK_OAK_PLANKS);
        for (int x = x0 + 1; x < x1; x += 3) {
            if (air(x, ridge + 1, zm)) {
                b(x, ridge + 1, zm, MOSS_BLOCK);
            }
        }
        b(xm, ridge, zm, AIR);
        fill(x0 - 1, ridge + 1, zm, x0 - 1, ridge + 2, zm, DARK_OAK_FENCE);
        fill(x1 + 1, ridge + 1, zm, x1 + 1, ridge + 2, zm, DARK_OAK_FENCE);
        b(x0, g + 6, zm, Material.GLASS_PANE);
        b(x0, g + 7, zm, Material.GLASS_PANE);
        b(x1, g + 6, zm, Material.GLASS_PANE);
        b(x1, g + 7, zm, Material.GLASS_PANE);
        for (int x = x0 + 2; x < x1; x += 4) {
            slit(x, g + 3, z0);
            slit(x, g + 3, z1);
        }
        door(x0 + 3, g + 1, z0, SPRUCE_DOOR, NORTH);
        torch(x0 + 2, g + 3, z0 - 1, NORTH);
        torch(x0 + 4, g + 3, z0 - 1, NORTH);
        // hearth, benches, smoke hole above
        fill(xm - 2, g, zm - 1, xm + 2, g, zm + 1, STONE_BRICKS);
        face(xm, g + 1, zm, CAMPFIRE, NORTH);
        for (int dx : new int[] {-2, 2}) {
            slab(xm + dx, g + 1, zm, COBBLESTONE_SLAB, false);
        }
        b(xm, g + 1, zm - 1, CAULDRON);
        for (int x = xm - 5; x <= xm + 5; x++) {
            slab(x, g + 1, z0 + 2, SPRUCE_SLAB, false);
        }
        // beams with lanterns
        for (int x = x0 + 4; x < x1; x += 4) {
            for (int z = z0 + 1; z < z1; z++) {
                log(x, g + 4, z, SPRUCE_LOG, Axis.Z);
            }
            if (x != xm) {
                lantern(x, g + 3, zm, true);
            }
        }
        // beds along the back wall
        int n = 0;
        for (int x = x0 + 2; x <= x1 - 2; x += 3, n++) {
            bedAlong(x, z1 - 2, n % 2 == 0 ? RED_BED : BLUE_BED, SOUTH);
            if (n % 2 == 1) {
                face(x + 1, g + 1, z1 - 1, CHEST, NORTH);
            }
        }
        // kitchen at the east end, storage at the west end
        b(x1 - 1, g + 1, z0 + 1, BARREL);
        b(x1 - 2, g + 1, z0 + 1, BARREL);
        face(x1 - 3, g + 1, z0 + 1, FURNACE, SOUTH);
        face(x1 - 4, g + 1, z0 + 1, SMOKER, SOUTH);
        b(x1 - 5, g + 1, z0 + 1, CRAFTING_TABLE);
        face(x0 + 1, g + 1, z0 + 1, CHEST, SOUTH);
        face(x0 + 1, g + 1, z0 + 2, CHEST, EAST);
        b(x0 + 1, g + 1, z0 + 3, BARREL);
        return new int[] {x0 + 3, z0 - 1};
    }

    // ---- 2. Timber-frame town house: jettied upper floor, plaster and dark oak

    private int[] timberHouse(int ox, int oz) {
        int x0 = ox + 11, x1 = x0 + 8, z0 = oz + 9, z1 = z0 + 6;
        int X0 = x0 - 1, X1 = x1 + 1, Z0 = z0 - 1, Z1 = z1 + 1;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, STONE_BRICKS);
        ring(x0, z0, x1, z1, g + 1, g + 3, WHITE_CONCRETE);
        ring(x0, z0, x1, z1, g + 1, g + 1, STONE_BRICKS);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 3, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 1, g + 3, DARK_OAK_LOG);
        fill(xm, g + 1, z0, xm, g + 3, z0, DARK_OAK_LOG);
        fill(xm, g + 1, z1, xm, g + 3, z1, DARK_OAK_LOG);
        fill(x0, g + 1, zm, x0, g + 3, zm, DARK_OAK_LOG);
        fill(x1, g + 1, zm, x1, g + 3, zm, DARK_OAK_LOG);
        // jettied upper floor
        fill(X0, g + 4, Z0, X1, g + 4, Z1, SPRUCE_PLANKS);
        beamRing(X0, Z0, X1, Z1, g + 4, DARK_OAK_LOG);
        ring(X0, Z0, X1, Z1, g + 5, g + 7, WHITE_CONCRETE);
        fill(X0 + 1, g + 5, Z0 + 1, X1 - 1, g + 7, Z1 - 1, AIR);
        posts(X0, Z0, X1, Z1, g + 5, g + 7, DARK_OAK_LOG);
        fill(xm, g + 5, Z0, xm, g + 7, Z0, DARK_OAK_LOG);
        fill(xm, g + 5, Z1, xm, g + 7, Z1, DARK_OAK_LOG);
        fill(X0, g + 5, zm, X0, g + 7, zm, DARK_OAK_LOG);
        beamRing(X0, Z0, X1, Z1, g + 7, DARK_OAK_LOG);
        for (int x = X0 + 1; x < X1; x += 2) {
            stair(x, g + 3, Z0, DARK_OAK_STAIRS, SOUTH, true);
            stair(x, g + 3, Z1, DARK_OAK_STAIRS, NORTH, true);
        }
        int ridge = gable(X0, Z0, X1, Z1, g + 8, true, DARK_OAK_STAIRS, DARK_OAK_PLANKS, WHITE_CONCRETE, DARK_OAK_PLANKS);
        for (int y = g + 8; y < ridge; y++) {
            b(X0, y, zm, DARK_OAK_LOG);
            b(X1, y, zm, DARK_OAK_LOG);
        }
        // chimney on the east side
        fill(x1 + 1, g + 1, zm - 1, x1 + 1, g + 3, zm + 1, BRICKS);
        fill(X1 + 1, g + 1, zm - 1, X1 + 1, ridge + 2, zm + 1, BRICKS);
        fill(X1 + 1, ridge + 3, zm, X1 + 1, ridge + 3, zm, STONE_BRICK_SLAB);
        // fireplace inside
        fill(x1 - 1, g + 1, zm - 1, x1 - 1, g + 3, zm - 1, BRICKS);
        fill(x1 - 1, g + 1, zm + 1, x1 - 1, g + 3, zm + 1, BRICKS);
        fill(x1, g + 1, zm, x1, g + 3, zm, BRICKS);
        b(x1 - 1, g + 3, zm, BRICKS);
        face(x1 - 1, g + 1, zm, CAMPFIRE, WEST);
        // openings
        door(xm, g + 1, z0, DARK_OAK_DOOR, NORTH);
        torch(xm - 1, g + 3, z0 - 1, NORTH);
        torch(xm + 1, g + 3, z0 - 1, NORTH);
        window(x0 + 2, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 6, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 2, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 6, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 2, zm - 2, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 2, zm + 2, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        for (int x : new int[] {X0 + 2, X0 + 5, X0 + 8}) {
            window(x, g + 6, Z0, NORTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 6, Z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        window(X0, g + 6, zm - 2, WEST, false, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(X0, g + 6, zm + 2, WEST, false, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        b(X0, g + 9, zm - 1, Material.GLASS_PANE);
        // ground floor: kitchen counter, dining table, stairs up
        b(x0 + 5, g + 1, z0 + 1, BARREL);
        face(x0 + 6, g + 1, z0 + 1, FURNACE, SOUTH);
        b(x0 + 7, g + 1, z0 + 1, CRAFTING_TABLE);
        for (int x = x0 + 3; x <= x0 + 5; x++) {
            table(x, g + 1, zm, SPRUCE_SLAB);
            chair(x, g + 1, zm - 1, SPRUCE_STAIRS, NORTH);
            chair(x, g + 1, zm + 1, SPRUCE_STAIRS, SOUTH);
        }
        lantern(x0 + 4, g + 2, zm, false);
        stairsUp(x0 + 1, z0 + 1, SOUTH, SPRUCE_STAIRS);
        face(x0 + 1, g + 1, z1 - 1, CHEST, NORTH);
        b(x0 + 2, g + 1, z1 - 1, BARREL);
        hang(g + 3, zm + 1, x0 + 3, x0 + 5);
        // upper floor: bedroom, study
        bedAlong(X0 + 3, Z1 - 2, RED_BED, SOUTH);
        bedAlong(X0 + 6, Z1 - 2, WHITE_BED, SOUTH);
        face(X0 + 4, g + 5, Z1 - 1, CHEST, NORTH);
        fill(X0 + 5, g + 5, Z0 + 1, X0 + 9, g + 6, Z0 + 1, BOOKSHELF);
        b(X0 + 9, g + 5, Z0 + 2, LECTERN);
        for (int v = Z0 + 1; v < Z1; v++) {
            log(xm, g + 8, v, DARK_OAK_LOG, Axis.Z);
        }
        hang(g + 7, zm - 2, xm);
        hang(g + 7, zm + 2, xm);
        return new int[] {xm, z0 - 1};
    }

    // ---- 3. Stone cottage: gable to the street, porch, slate roof

    private int[] stoneCottage(int ox, int oz) {
        int x0 = ox + 10, x1 = x0 + 8, z0 = oz + 9, z1 = z0 + 10;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, COBBLESTONE);
        ring(x0, z0, x1, z1, g + 1, g + 4, COBBLESTONE);
        mossy(x0, z0, x1, z1, g + 1, g + 4, COBBLESTONE, MOSSY_COBBLESTONE, 5);
        posts(x0, z0, x1, z1, g + 1, g + 4, STONE_BRICKS);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 4, z1 - 1, AIR);
        int ridge = gable(x0, z0, x1, z1, g + 5, false, DEEPSLATE_TILE_STAIRS, DEEPSLATE_TILES, COBBLESTONE, DEEPSLATE_TILES);
        b(xm, g + 6, z0, Material.GLASS_PANE);
        b(xm, g + 7, z0, Material.GLASS_PANE);
        b(xm, g + 6, z1, Material.GLASS_PANE);
        // chimney with a flue
        fill(x1 + 1, g + 1, zm, x1 + 1, g + 9, zm + 1, STONE_BRICKS);
        fill(x1, g + 1, zm, x1, g + 3, zm + 1, STONE_BRICKS);
        b(x1 + 1, g + 10, zm, STONE_BRICK_SLAB);
        b(x1 + 1, g + 10, zm + 1, STONE_BRICK_SLAB);
        b(x1, g + 4, zm, STONE_BRICKS);
        b(x1, g + 4, zm + 1, STONE_BRICKS);
        face(x1 - 1, g + 1, zm, CAMPFIRE, WEST);
        b(x1 - 1, g + 1, zm + 1, CAULDRON);
        // porch
        fill(xm - 2, g + 1, z0 - 2, xm - 2, g + 3, z0 - 2, STRIPPED_SPRUCE_LOG);
        fill(xm + 2, g + 1, z0 - 2, xm + 2, g + 3, z0 - 2, STRIPPED_SPRUCE_LOG);
        for (int x = xm - 3; x <= xm + 3; x++) {
            for (int z = z0 - 3; z <= z0 - 1; z++) {
                slab(x, g + 4, z, SPRUCE_SLAB, false);
            }
        }
        lantern(xm, g + 3, z0 - 1, true);
        door(xm, g + 1, z0, SPRUCE_DOOR, NORTH);
        stair(xm, g + 1, z0 - 2, COBBLESTONE_STAIRS, SOUTH, false);
        for (int s : new int[] {-1, 1}) {
            window(xm + s * 3, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, COBBLESTONE_SLAB);
        }
        for (int z : new int[] {z0 + 3, z0 + 7}) {
            window(x0, g + 2, z, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x1, g + 2, z, EAST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        window(xm, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        // interior
        bedAlong(x0 + 2, z1 - 2, RED_BED, SOUTH);
        bedAlong(x0 + 4, z1 - 2, BLUE_BED, SOUTH);
        face(x0 + 3, g + 1, z1 - 1, CHEST, NORTH);
        fill(x0 + 1, g + 1, z0 + 1, x0 + 1, g + 2, z0 + 3, BOOKSHELF);
        b(x0 + 1, g + 1, z0 + 4, BARREL);
        face(x0 + 1, g + 1, z0 + 5, FURNACE, EAST);
        b(x0 + 1, g + 1, z0 + 6, CRAFTING_TABLE);
        for (int z = zm - 1; z <= zm; z++) {
            table(xm, g + 1, z, SPRUCE_SLAB);
            chair(xm - 1, g + 1, z, SPRUCE_STAIRS, WEST);
            chair(xm + 1, g + 1, z, SPRUCE_STAIRS, EAST);
        }
        lantern(xm, g + 2, zm, false);
        for (int x = x0 + 1; x < x1; x++) {
            log(x, g + 4, z0 + 3, SPRUCE_LOG, Axis.X);
            log(x, g + 4, z0 + 8, SPRUCE_LOG, Axis.X);
        }
        lantern(xm, g + 3, z0 + 3, true);
        lantern(xm, g + 3, z0 + 8, true);
        // herb bed by the porch
        for (int x : new int[] {xm - 4, xm + 4}) {
            b(x, g + 1, z0 - 1, COMPOSTER);
        }
        return new int[] {xm, z0 - 3};
    }

    // ---- 4. Falu-red cottage with a well and a woodshed

    private int[] redCottage(int ox, int oz) {
        int x0 = ox + 11, x1 = x0 + 8, z0 = oz + 10, z1 = z0 + 6;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, COBBLESTONE);
        ring(x0, z0, x1, z1, g + 1, g + 4, RED_TERRACOTTA);
        ring(x0, z0, x1, z1, g + 1, g + 1, COBBLESTONE);
        posts(x0, z0, x1, z1, g + 1, g + 4, WHITE_CONCRETE);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 4, z1 - 1, AIR);
        ring(x0, z0, x1, z1, g + 4, g + 4, WHITE_CONCRETE);
        int ridge = gable(x0, z0, x1, z1, g + 5, true, POLISHED_BLACKSTONE_BRICK_STAIRS, POLISHED_BLACKSTONE_BRICKS,
                RED_TERRACOTTA, POLISHED_BLACKSTONE_BRICKS);
        b(x0, g + 6, zm, Material.GLASS_PANE);
        b(x1, g + 6, zm, Material.GLASS_PANE);
        fill(x0, g + 5, zm - 0, x0, g + 5, zm, WHITE_CONCRETE);
        // chimney
        fill(x1 - 1, g + 1, zm, x1 - 1, ridge + 2, zm, BRICKS);
        b(x1 - 1, ridge + 3, zm, STONE_BRICK_SLAB);
        face(x1 - 2, g + 1, zm, CAMPFIRE, WEST);
        fill(x1 - 2, g + 1, zm - 1, x1 - 2, g + 2, zm - 1, BRICKS);
        fill(x1 - 2, g + 1, zm + 1, x1 - 2, g + 2, zm + 1, BRICKS);
        b(x1 - 2, g + 3, zm, BRICKS);
        // doors and windows with white frames
        door(x0 + 3, g + 1, z0, SPRUCE_DOOR, NORTH);
        torch(x0 + 2, g + 3, z0 - 1, NORTH);
        window(x0 + 6, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 2, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 6, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 2, zm, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        // interior
        bedAlong(x0 + 2, z1 - 2, RED_BED, SOUTH);
        face(x0 + 3, g + 1, z1 - 1, CHEST, NORTH);
        b(x0 + 1, g + 1, z0 + 1, BARREL);
        b(x0 + 1, g + 1, z0 + 2, LOOM);
        face(x0 + 1, g + 1, z0 + 3, FURNACE, EAST);
        b(x0 + 1, g + 1, z0 + 4, CRAFTING_TABLE);
        for (int x = x0 + 4; x <= x0 + 5; x++) {
            table(x, g + 1, zm - 1, SPRUCE_SLAB);
            chair(x, g + 1, zm - 2, SPRUCE_STAIRS, NORTH);
            chair(x, g + 1, zm, SPRUCE_STAIRS, SOUTH);
        }
        lantern(x0 + 4, g + 2, zm - 1, false);
        for (int z = z0 + 1; z < z1; z++) {
            log(x0 + 4, g + 4, z, SPRUCE_LOG, Axis.Z);
        }
        lantern(x0 + 4, g + 3, zm + 1, true);
        // woodshed on the west side
        int sx0 = x0 - 6, sx1 = x0 - 1;
        for (int x = sx0; x <= sx1; x++) {
            for (int z = z0; z <= z0 + 3; z++) {
                slab(x, g + 4, z, SPRUCE_SLAB, false);
            }
        }
        for (int z : new int[] {z0, z0 + 3}) {
            fill(sx0, g + 1, z, sx0, g + 3, z, SPRUCE_LOG);
        }
        for (int x = sx0 + 1; x <= sx1 - 1; x++) {
            for (int y = g + 1; y <= g + 3; y++) {
                log(x, y, z0 + 2, y % 2 == 0 ? STRIPPED_SPRUCE_LOG : SPRUCE_LOG, Axis.X);
                log(x, y, z0 + 1, y % 2 == 0 ? SPRUCE_LOG : STRIPPED_SPRUCE_LOG, Axis.X);
            }
        }
        // well
        int wx = x1 + 5, wz = z0 + 2;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    b(wx + dx, g, wz + dz, COBBLESTONE);
                    b(wx + dx, g + 1, wz + dz, COBBLESTONE);
                }
            }
        }
        b(wx, g, wz, Material.WATER);
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                fill(wx + dx, g + 2, wz + dz, wx + dx, g + 3, wz + dz, SPRUCE_FENCE);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    slab(wx + dx, g + 4, wz + dz, SPRUCE_SLAB, false);
                }
            }
        }
        lantern(wx, g + 3, wz, true);
        return new int[] {x0 + 3, z0 - 1};
    }

    // ---- 5. Manor: stone ground floor, timber upper floor, and a square tower

    private int[] manor(int ox, int oz) {
        int x0 = ox + 10, x1 = x0 + 12, z0 = oz + 8, z1 = z0 + 8;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        // main house
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, STONE_BRICKS);
        ring(x0, z0, x1, z1, g + 1, g + 3, STONE_BRICKS);
        mossy(x0, z0, x1, z1, g + 1, g + 3, STONE_BRICKS, Material.MOSSY_STONE_BRICKS, 6);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 3, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 1, g + 3, STRIPPED_SPRUCE_LOG);
        fill(x0, g + 4, z0, x1, g + 4, z1, SPRUCE_PLANKS);
        beamRing(x0, z0, x1, z1, g + 4, SPRUCE_LOG);
        ring(x0, z0, x1, z1, g + 5, g + 7, WHITE_CONCRETE);
        fill(x0 + 1, g + 5, z0 + 1, x1 - 1, g + 7, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 5, g + 7, SPRUCE_LOG);
        for (int x = x0 + 4; x < x1; x += 4) {
            fill(x, g + 5, z0, x, g + 7, z0, SPRUCE_LOG);
            fill(x, g + 5, z1, x, g + 7, z1, SPRUCE_LOG);
        }
        beamRing(x0, z0, x1, z1, g + 7, SPRUCE_LOG);
        int ridge = gable(x0, z0, x1, z1, g + 8, true, DARK_OAK_STAIRS, DARK_OAK_PLANKS, WHITE_CONCRETE, DARK_OAK_PLANKS);
        for (int y = g + 8; y < ridge; y++) {
            b(x1, y, zm, SPRUCE_LOG);
        }
        b(x1, g + 9, zm - 1, Material.GLASS_PANE);
        // tower on the west end
        int tx0 = x0 - 4, tx1 = x0, tz0 = z0, tz1 = z0 + 4;
        fill(tx0, g, tz0, tx1, g, tz1, SPRUCE_PLANKS);
        ring(tx0, tz0, tx1, tz1, g + 1, g + 11, STONE_BRICKS);
        mossy(tx0, tz0, tx1, tz1, g + 1, g + 11, STONE_BRICKS, Material.MOSSY_STONE_BRICKS, 6);
        fill(tx0 + 1, g + 1, tz0 + 1, tx1 - 1, g + 11, tz1 - 1, AIR);
        posts(tx0, tz0, tx1, tz1, g + 1, g + 11, POLISHED_BLACKSTONE_BRICKS);
        for (int fy : new int[] {g + 4, g + 8}) {
            fill(tx0 + 1, fy, tz0 + 1, tx1 - 1, fy, tz1 - 1, SPRUCE_PLANKS);
            ring(tx0, tz0, tx1, tz1, fy, fy, POLISHED_BLACKSTONE_BRICKS);
            b(tx0 + 1, fy, tz0 + 1, AIR);
        }
        fill(tx0 + 1, g + 1, tz0 + 1, tx0 + 1, g + 11, tz0 + 1, AIR);
        for (int y = g + 1; y <= g + 11; y++) {
            face(tx0 + 1, y, tz0 + 1, LADDER, EAST);
        }
        for (int fy : new int[] {g + 2, g + 6, g + 10}) {
            window(tx0, fy, tz0 + 2, WEST, false, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(tx0 + 2, fy, tz1, SOUTH, false, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        int top = pyramid(tx0, tz0, tx1, tz1, g + 12, BRICK_STAIRS, BRICKS);
        fill(tx0 + 2, top, tz0 + 2, tx0 + 2, top + 2, tz0 + 2, DARK_OAK_FENCE);
        // the tower opens into the main floor
        b(x0, g + 1, z0 + 2, AIR);
        b(x0, g + 2, z0 + 2, AIR);
        // porch with columns
        for (int s : new int[] {-3, 3}) {
            fill(xm + s, g + 1, z0 - 3, xm + s, g + 3, z0 - 3, STRIPPED_SPRUCE_LOG);
        }
        for (int x = xm - 4; x <= xm + 4; x++) {
            for (int z = z0 - 4; z <= z0 - 1; z++) {
                slab(x, g + 4, z, DARK_OAK_SLAB, false);
            }
        }
        fill(xm - 4, g, z0 - 4, xm + 4, g, z0 - 1, STONE_BRICKS);
        lantern(xm - 2, g + 3, z0 - 2, true);
        lantern(xm + 2, g + 3, z0 - 2, true);
        door(xm, g + 1, z0, DARK_OAK_DOOR, NORTH);
        torch(xm - 1, g + 3, z0 - 1, NORTH);
        torch(xm + 1, g + 3, z0 - 1, NORTH);
        window(xm - 4, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, STONE_BRICK_SLAB);
        window(xm + 4, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, STONE_BRICK_SLAB);
        for (int x : new int[] {x0 + 2, x0 + 6, x0 + 10}) {
            window(x, g + 6, z0, NORTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 6, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        // great hall: long table, chairs, hearth, stairs
        for (int x = x0 + 3; x <= x0 + 9; x++) {
            table(x, g + 1, zm, SPRUCE_SLAB);
            chair(x, g + 1, zm - 1, SPRUCE_STAIRS, NORTH);
            chair(x, g + 1, zm + 1, SPRUCE_STAIRS, SOUTH);
        }
        chair(x0 + 2, g + 1, zm, DARK_OAK_STAIRS, WEST);
        chair(x0 + 10, g + 1, zm, DARK_OAK_STAIRS, EAST);
        lantern(x0 + 4, g + 2, zm, false);
        lantern(x0 + 8, g + 2, zm, false);
        hang(g + 3, zm - 2, x0 + 3, x0 + 9);
        hang(g + 3, zm + 2, x0 + 3, x0 + 9);
        fill(x1 - 1, g + 1, zm - 1, x1 - 1, g + 3, zm - 1, STONE_BRICKS);
        fill(x1 - 1, g + 1, zm + 1, x1 - 1, g + 3, zm + 1, STONE_BRICKS);
        b(x1 - 1, g + 3, zm, STONE_BRICKS);
        face(x1 - 1, g + 1, zm, CAMPFIRE, WEST);
        stairsUp(x0 + 7, z0 + 1, EAST, SPRUCE_STAIRS);
        // kitchen nook
        b(x0 + 2, g + 1, z1 - 1, BARREL);
        face(x0 + 3, g + 1, z1 - 1, FURNACE, NORTH);
        face(x0 + 4, g + 1, z1 - 1, SMOKER, NORTH);
        b(x0 + 5, g + 1, z1 - 1, CRAFTING_TABLE);
        face(x1 - 3, g + 1, z1 - 1, CHEST, NORTH);
        // upper floor: bedrooms and a library
        bedAlong(x0 + 2, z1 - 2, RED_BED, SOUTH);
        bedAlong(x0 + 5, z1 - 2, WHITE_BED, SOUTH);
        face(x0 + 3, g + 5, z1 - 1, CHEST, NORTH);
        fill(x1 - 6, g + 5, z1 - 1, x1 - 1, g + 6, z1 - 1, BOOKSHELF);
        b(x1 - 3, g + 5, z1 - 3, LECTERN);
        table(x1 - 4, g + 5, z1 - 3, SPRUCE_SLAB);
        for (int x = x0 + 4; x < x1; x += 4) {
            for (int z = z0 + 1; z < z1; z++) {
                log(x, g + 8, z, SPRUCE_LOG, Axis.Z);
            }
            hang(g + 7, zm, x);
        }
        // tower rooms: a desk on the first floor, a bed on the second
        b(tx0 + 2, g + 1, tz1 - 1, CRAFTING_TABLE);
        bed(tx0 + 2, g + 5, tz1 - 2, BLUE_BED, NORTH);
        hang(g + 3, tz0 + 2, tx0 + 2);
        hang(g + 7, tz0 + 2, tx0 + 2);
        return new int[] {xm, z0 - 5};
    }

    // ---- 6. Inn: taproom downstairs, three rooms above, balcony over the door

    private int[] inn(int ox, int oz) {
        int x0 = ox + 9, x1 = x0 + 10, z0 = oz + 9, z1 = z0 + 8;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, STONE_BRICKS);
        ring(x0, z0, x1, z1, g + 1, g + 7, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g + 1, g + 1, STONE_BRICKS);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 7, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 1, g + 7, SPRUCE_LOG);
        for (int x = x0 + 5; x < x1; x += 5) {
            fill(x, g + 1, z0, x, g + 7, z0, SPRUCE_LOG);
            fill(x, g + 1, z1, x, g + 7, z1, SPRUCE_LOG);
        }
        fill(x0 + 1, g + 4, z0 + 1, x1 - 1, g + 4, z1 - 1, SPRUCE_PLANKS);
        beamRing(x0, z0, x1, z1, g + 4, SPRUCE_LOG);
        beamRing(x0, z0, x1, z1, g + 7, SPRUCE_LOG);
        int ridge = gable(x0, z0, x1, z1, g + 8, true, SPRUCE_STAIRS, SPRUCE_PLANKS, SPRUCE_PLANKS, SPRUCE_LOG);
        fill(x0, g + 9, zm, x0, ridge - 1, zm, SPRUCE_LOG);
        fill(x1, g + 9, zm, x1, ridge - 1, zm, SPRUCE_LOG);
        b(x0, g + 9, zm - 1, Material.GLASS_PANE);
        b(x1, g + 9, zm - 1, Material.GLASS_PANE);
        // chimney breast on the west wall, running through both floors
        fill(x0 + 1, g + 1, zm, x0 + 1, ridge + 2, zm, STONE_BRICKS);
        b(x0 + 1, ridge + 3, zm, STONE_BRICK_SLAB);
        // balcony over the door
        for (int x = xm - 3; x <= xm + 3; x++) {
            for (int z = z0 - 2; z <= z0 - 1; z++) {
                b(x, g + 4, z, SPRUCE_PLANKS);
            }
            b(x, g + 5, z0 - 2, SPRUCE_FENCE);
        }
        for (int z = z0 - 2; z <= z0 - 1; z++) {
            b(xm - 3, g + 5, z, SPRUCE_FENCE);
            b(xm + 3, g + 5, z, SPRUCE_FENCE);
        }
        fill(xm - 3, g + 1, z0 - 2, xm - 3, g + 3, z0 - 2, SPRUCE_LOG);
        fill(xm + 3, g + 1, z0 - 2, xm + 3, g + 3, z0 - 2, SPRUCE_LOG);
        door(xm, g + 1, z0, DARK_OAK_DOOR, NORTH);
        door(xm, g + 5, z0, SPRUCE_DOOR, NORTH);
        lantern(xm - 2, g + 3, z0 - 1, true);
        lantern(xm + 2, g + 3, z0 - 1, true);
        // hanging inn sign (board on a bracket)
        fill(x0 - 2, g + 4, z0 - 1, x0 - 2, g + 6, z0 - 1, SPRUCE_FENCE);
        b(x0 - 1, g + 6, z0 - 1, SPRUCE_FENCE);
        b(x0 - 1, g + 5, z0 - 1, DARK_OAK_PLANKS);
        b(x0 - 2, g + 5, z0 - 1, DARK_OAK_PLANKS);
        lantern(x0 - 2, g + 3, z0 - 1, true);
        // windows
        for (int x : new int[] {xm - 4, xm + 4}) {
            window(x, g + 2, z0, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 6, z0, NORTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        for (int x : new int[] {x0 + 2, xm, x1 - 2}) {
            window(x, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 6, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        window(x0, g + 6, zm + 2, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        // taproom: bar with barrels behind, tables, hearth
        for (int x = x0 + 3; x <= x0 + 8; x++) {
            slab(x, g + 1, z1 - 3, SPRUCE_SLAB, true);
            b(x, g + 1, z1 - 1, x % 2 == 0 ? BARREL : CHEST);
            if (x % 2 == 0) {
                face(x, g + 1, z1 - 1, BARREL, NORTH);
            } else {
                face(x, g + 1, z1 - 1, CHEST, NORTH);
            }
            chair(x, g + 1, z1 - 4, SPRUCE_STAIRS, NORTH);
        }
        for (int[] t : new int[][] {{x0 + 7, z0 + 2}}) {
            table(t[0], g + 1, t[1], SPRUCE_SLAB);
            table(t[0] + 1, g + 1, t[1], SPRUCE_SLAB);
            chair(t[0], g + 1, t[1] - 1, SPRUCE_STAIRS, NORTH);
            chair(t[0] + 1, g + 1, t[1] - 1, SPRUCE_STAIRS, NORTH);
            chair(t[0], g + 1, t[1] + 1, SPRUCE_STAIRS, SOUTH);
            chair(t[0] + 1, g + 1, t[1] + 1, SPRUCE_STAIRS, SOUTH);
            lantern(t[0], g + 2, t[1], false);
        }
        fill(x0 + 2, g + 1, zm - 1, x0 + 2, g + 2, zm - 1, STONE_BRICKS);
        fill(x0 + 2, g + 1, zm + 1, x0 + 2, g + 2, zm + 1, STONE_BRICKS);
        b(x0 + 2, g + 3, zm, STONE_BRICKS);
        face(x0 + 2, g + 1, zm, CAMPFIRE, EAST);
        for (int z = zm - 2; z <= zm + 2; z += 4) {
            chair(x0 + 4, g + 1, z, SPRUCE_STAIRS, EAST);
        }
        chair(x0 + 4, g + 1, zm, DARK_OAK_STAIRS, EAST);
        for (int x = x0 + 3; x <= x0 + 9; x += 3) {
            hang(g + 3, zm, x);
        }
        // stairs up along the east wall
        for (int i = 0; i < 4; i++) {
            stair(x1 - 1, g + 1 + i, z1 - 4 - i, SPRUCE_STAIRS, NORTH, false);
        }
        b(x1 - 1, g + 4, z1 - 5, AIR);
        b(x1 - 1, g + 4, z1 - 6, AIR);
        // upper floor: three rooms
        for (int x : new int[] {x0 + 3, x0 + 6}) {
            fill(x, g + 5, z0 + 1, x, g + 7, z1 - 1, SPRUCE_PLANKS);
            door(x, g + 5, zm, SPRUCE_DOOR, EAST);
        }
        bedAlong(x0 + 1, z1 - 2, RED_BED, SOUTH);
        face(x0 + 2, g + 5, z1 - 1, CHEST, NORTH);
        bedAlong(x0 + 4, z1 - 2, BLUE_BED, SOUTH);
        face(x0 + 5, g + 5, z1 - 1, CHEST, NORTH);
        bedAlong(x0 + 7, z1 - 2, GREEN_BED, SOUTH);
        bedAlong(x0 + 7, z0 + 2, WHITE_BED, NORTH);
        for (int x : new int[] {x0 + 2, x0 + 5, x0 + 8}) {
            lantern(x, g + 5, z0 + 1, false);
            table(x, g + 5, zm - 1, SPRUCE_SLAB);
        }
        hang(g + 7, zm, x0 + 2, x0 + 5, x0 + 8);
        return new int[] {xm, z0 - 3};
    }

    // ---- 7. Stilt house: a cabin on log piles with a deck and stairs

    private int[] stiltHouse(int ox, int oz) {
        int x0 = ox + 11, x1 = x0 + 6, z0 = oz + 10, z1 = z0 + 6;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        for (int x : new int[] {x0, xm, x1}) {
            for (int z : new int[] {z0, zm, z1}) {
                fill(x, g, z, x, g + 3, z, SPRUCE_LOG);
            }
        }
        for (int x : new int[] {x0, x1}) {
            fill(x, g, z0 - 2, x, g + 3, z0 - 2, SPRUCE_LOG);
        }
        fill(x0, g + 4, z0 - 2, x1, g + 4, z1, SPRUCE_PLANKS);
        beamRing(x0, z0 - 2, x1, z1, g + 4, DARK_OAK_LOG);
        ring(x0, z0, x1, z1, g + 5, g + 7, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g + 5, g + 5, STRIPPED_SPRUCE_LOG);
        fill(x0 + 1, g + 5, z0 + 1, x1 - 1, g + 7, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 5, g + 7, SPRUCE_LOG);
        beamRing(x0, z0, x1, z1, g + 7, SPRUCE_LOG);
        int ridge = gable(x0, z0, x1, z1, g + 8, true, SPRUCE_STAIRS, SPRUCE_PLANKS, SPRUCE_PLANKS, SPRUCE_LOG);
        b(x0, g + 9, zm, Material.GLASS_PANE);
        b(x1, g + 9, zm, Material.GLASS_PANE);
        b(x1 - 1, g + 5, z1 - 1, BARREL);
        // deck rail and stairs
        for (int x = x0; x <= x1; x++) {
            if (x != xm) {
                b(x, g + 5, z0 - 2, SPRUCE_FENCE);
            }
        }
        for (int z = z0 - 2; z < z0; z++) {
            b(x0, g + 5, z, SPRUCE_FENCE);
            b(x1, g + 5, z, SPRUCE_FENCE);
        }
        fill(x0 - 0, g + 5, z0 - 2, x0, g + 6, z0 - 2, SPRUCE_FENCE);
        lantern(x0, g + 7, z0 - 2, false);
        lantern(x1, g + 7, z0 - 2, false);
        fill(x0, g + 6, z0 - 2, x0, g + 6, z0 - 2, SPRUCE_FENCE);
        fill(x1, g + 6, z0 - 2, x1, g + 6, z0 - 2, SPRUCE_FENCE);
        for (int i = 0; i < 3; i++) {
            for (int dx = -1; dx <= 1; dx += 2) {
                b(xm + dx, g + 1 + i, z0 - 5 + i, SPRUCE_FENCE);
            }
            stair(xm, g + 1 + i, z0 - 5 + i, SPRUCE_STAIRS, SOUTH, false);
        }
        door(xm, g + 5, z0, SPRUCE_DOOR, NORTH);
        torch(xm - 1, g + 7, z0 - 1, NORTH);
        torch(xm + 1, g + 7, z0 - 1, NORTH);
        window(x0 + 1 + 0, g + 6, z0, NORTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x1 - 1, g + 6, z0, NORTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 6, zm, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x1, g + 6, zm, EAST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(xm, g + 6, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        // interior
        bedAlong(x0 + 1, z1 - 2, RED_BED, SOUTH);
        face(x0 + 2, g + 5, z1 - 1, CHEST, NORTH);
        face(x1 - 1, g + 5, z0 + 1, FURNACE, SOUTH);
        b(x1 - 2, g + 5, z0 + 1, CRAFTING_TABLE);
        table(xm, g + 5, zm, SPRUCE_SLAB);
        chair(xm - 1, g + 5, zm, SPRUCE_STAIRS, WEST);
        chair(xm + 1, g + 5, zm, SPRUCE_STAIRS, EAST);
        lantern(xm, g + 6, zm, false);
        hang(g + 7, zm + 1, xm - 2, xm + 2);
        for (int z = z0 + 1; z < z1; z++) {
            log(xm, g + 8, z, SPRUCE_LOG, Axis.Z);
        }
        return new int[] {xm, z0 - 6};
    }

    // ---- 8. Round tower house with a red cone roof

    private int[] roundTower(int ox, int oz) {
        int cx = ox + 16, cz = oz + 13;
        double outer = 4.6, inner = 3.7;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > outer) {
                    continue;
                }
                boolean wall = d >= inner;
                b(cx + dx, g, cz + dz, wall ? STONE_BRICKS : SPRUCE_PLANKS);
                for (int y = g + 1; y <= g + 12; y++) {
                    if (wall) {
                        Material m = STONE_BRICKS;
                        double r = rnd.nextDouble();
                        if (r < 0.12) {
                            m = Material.MOSSY_STONE_BRICKS;
                        } else if (r < 0.2) {
                            m = Material.CRACKED_STONE_BRICKS;
                        }
                        if ((y - g) % 4 == 0) {
                            m = POLISHED_BLACKSTONE_BRICKS;
                        }
                        b(cx + dx, y, cz + dz, m);
                    } else {
                        b(cx + dx, y, cz + dz, AIR);
                    }
                }
            }
        }
        for (int fy : new int[] {g + 4, g + 8}) {
            disc(cx, cz, inner - 0.1, fy, SPRUCE_PLANKS);
            b(cx - 3, fy, cz, AIR);
        }
        for (int y = g + 1; y <= g + 11; y++) {
            face(cx - 3, y, cz, LADDER, EAST);
        }
        // windows and door
        for (int fy : new int[] {g + 2, g + 6, g + 10}) {
            for (int[] d : new int[][] {{0, -4}, {0, 4}, {4, 0}, {-4, 0}}) {
                if (fy == g + 2 && d[0] == 0 && d[1] == -4) {
                    continue;
                }
                b(cx + d[0], fy, cz + d[1], Material.GLASS_PANE);
                b(cx + d[0], fy + 1, cz + d[1], Material.GLASS_PANE);
            }
        }
        door(cx, g + 1, cz - 4, SPRUCE_DOOR, NORTH);
        torch(cx - 1, g + 3, cz - 5, NORTH);
        torch(cx + 1, g + 3, cz - 5, NORTH);
        stair(cx, g, cz - 5, STONE_BRICK_STAIRS, SOUTH, false);
        // red cone roof with blackstone ribs
        double r = 5.6;
        int y = g + 13;
        for (int layer = 0; r > 0.6; layer++, y++, r -= 0.72) {
            disc(cx, cz, r, y, layer % 2 == 0 ? RED_CONCRETE : RED_WOOL);
            int rr = (int) Math.round(r);
            for (int[] d : new int[][] {{rr, 0}, {-rr, 0}, {0, rr}, {0, -rr}}) {
                b(cx + d[0], y, cz + d[1], POLISHED_BLACKSTONE_BRICKS);
            }
        }
        fill(cx, y, cz, cx, y + 2, cz, DARK_OAK_FENCE);
        // inside: kitchen, bedroom, study
        face(cx + 1, g + 1, cz + 2, CAMPFIRE, NORTH);
        b(cx - 2, g + 1, cz + 2, BARREL);
        face(cx + 2, g + 1, cz - 1, FURNACE, WEST);
        b(cx + 2, g + 1, cz, CRAFTING_TABLE);
        table(cx, g + 1, cz, SPRUCE_SLAB);
        chair(cx - 1, g + 1, cz, SPRUCE_STAIRS, WEST);
        chair(cx + 1, g + 1, cz - 1, SPRUCE_STAIRS, EAST);
        hang(g + 3, cz, cx, cx + 2);
        bed(cx + 1, g + 5, cz + 1, RED_BED, SOUTH);
        bed(cx - 1, g + 5, cz + 1, BLUE_BED, SOUTH);
        face(cx + 2, g + 5, cz - 1, CHEST, WEST);
        table(cx + 1, g + 5, cz - 2, SPRUCE_SLAB);
        lantern(cx + 1, g + 6, cz - 2, false);
        hang(g + 7, cz, cx);
        fill(cx + 2, g + 9, cz + 1, cx + 2, g + 10, cz + 2, BOOKSHELF);
        fill(cx - 1, g + 9, cz + 3, cx + 1, g + 10, cz + 3, BOOKSHELF);
        b(cx, g + 9, cz - 2, LECTERN);
        table(cx + 1, g + 9, cz - 1, SPRUCE_SLAB);
        lantern(cx + 1, g + 10, cz - 1, false);
        hang(g + 11, cz, cx);
        return new int[] {cx, cz - 6};
    }

    // ---- 9. Smithy: open forge bay, big chimney

    private int[] smithy(int ox, int oz) {
        int x0 = ox + 10, x1 = x0 + 10, z0 = oz + 10, z1 = z0 + 6;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, STONE_BRICKS);
        ring(x0, z0, x1, z1, g, g, COBBLESTONE);
        ring(x0, z0, x1, z1, g + 1, g + 2, STONE_BRICKS);
        ring(x0, z0, x1, z1, g + 3, g + 4, SPRUCE_PLANKS);
        mossy(x0, z0, x1, z1, g + 1, g + 2, STONE_BRICKS, MOSSY_COBBLESTONE, 6);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 4, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 1, g + 4, SPRUCE_LOG);
        // open forge bay on the front
        for (int x = x0 + 1; x <= x0 + 4; x++) {
            fill(x, g + 1, z0, x, g + 3, z0, AIR);
        }
        fill(x0 + 5, g + 1, z0, x0 + 5, g + 4, z0, SPRUCE_LOG);
        for (int x = x0; x <= x0 + 5; x++) {
            log(x, g + 4, z0, SPRUCE_LOG, Axis.X);
        }
        int ridge = gable(x0, z0, x1, z1, g + 5, true, STONE_BRICK_STAIRS, STONE_BRICKS, SPRUCE_PLANKS, STONE_BRICKS);
        // big chimney through the ridge
        fill(x1 - 2, g + 1, zm, x1 - 2, ridge + 2, zm, STONE_BRICKS);
        fill(x1 - 2, g + 1, zm - 1, x1 - 2, g + 3, zm - 1, STONE_BRICKS);
        fill(x1 - 2, g + 1, zm + 1, x1 - 2, g + 3, zm + 1, STONE_BRICKS);
        fill(x1 - 3, g + 1, zm - 1, x1 - 3, g + 1, zm + 1, STONE_BRICKS);
        b(x1 - 3, g + 2, zm - 1, STONE_BRICKS);
        b(x1 - 3, g + 2, zm + 1, STONE_BRICKS);
        b(x1 - 2, g + 3, zm, STONE_BRICKS);
        face(x1 - 3, g + 1, zm, CAMPFIRE, WEST);
        b(x1 - 2, ridge + 3, zm, STONE_BRICK_SLAB);
        // door and windows
        door(x0 + 8, g + 1, z0, DARK_OAK_DOOR, NORTH);
        torch(x0 + 7, g + 3, z0 - 1, NORTH);
        torch(x0 + 9, g + 3, z0 - 1, NORTH);
        window(x0 + 3, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0 + 7, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 2, zm, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        b(x0, g + 6, zm, Material.GLASS_PANE);
        b(x1, g + 6, zm, Material.GLASS_PANE);
        // workshop
        face(x0 + 2, g + 1, z0 + 2, Material.ANVIL, EAST);
        b(x0 + 4, g + 1, z0 + 1, SMITHING_TABLE);
        face(x0 + 1, g + 1, z0 + 1, GRINDSTONE, SOUTH);
        b(x0 + 1, g + 1, z0 + 3, Material.LAVA_CAULDRON);
        b(x0 + 1, g + 1, z0 + 4, Material.WATER_CAULDRON);
        face(x0 + 5, g + 1, z0 + 2, BLAST_FURNACE, WEST);
        face(x0 + 5, g + 1, z0 + 3, FURNACE, WEST);
        b(x0 + 4, g + 1, z1 - 1, BARREL);
        b(x0 + 5, g + 1, z1 - 1, BARREL);
        face(x0 + 6, g + 1, z1 - 1, CHEST, NORTH);
        face(x0 + 7, g + 1, z1 - 1, CHEST, NORTH);
        table(x0 + 7, g + 1, z0 + 2, SPRUCE_SLAB);
        table(x0 + 7, g + 1, z0 + 3, SPRUCE_SLAB);
        lantern(x0 + 7, g + 2, z0 + 2, false);
        bedAlong(x0 + 1, z1 - 2, RED_BED, SOUTH);
        for (int x = x0 + 2; x < x1; x += 3) {
            for (int z = z0 + 1; z < z1; z++) {
                log(x, g + 5, z, SPRUCE_LOG, Axis.Z);
            }
            hang(g + 4, zm, x);
        }
        // yard: stacked ingots as coal and iron blocks, cart
        b(x0 - 2, g + 1, z0 + 1, Material.COAL_BLOCK);
        b(x0 - 2, g + 2, z0 + 1, Material.COAL_BLOCK);
        b(x0 - 2, g + 1, z0 + 2, Material.IRON_BLOCK);
        b(x0 - 3, g + 1, z0 + 1, Material.COAL_BLOCK);
        face(x0 - 2, g + 1, z0 + 4, BARREL, EAST);
        return new int[] {x0 + 8, z0 - 1};
    }

    // ---- 10. Bakery with a striped awning and living rooms above

    private int[] bakery(int ox, int oz) {
        int x0 = ox + 11, x1 = x0 + 8, z0 = oz + 8, z1 = z0 + 8;
        int xm = (x0 + x1) / 2, zm = (z0 + z1) / 2;
        fill(x0, g, z0, x1, g, z1, SPRUCE_PLANKS);
        ring(x0, z0, x1, z1, g, g, STONE_BRICKS);
        ring(x0, z0, x1, z1, g + 1, g + 3, BIRCH_PLANKS);
        ring(x0, z0, x1, z1, g + 1, g + 1, STONE_BRICKS);
        fill(x0 + 1, g + 1, z0 + 1, x1 - 1, g + 3, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 1, g + 3, SPRUCE_LOG);
        fill(x0, g + 4, z0, x1, g + 4, z1, SPRUCE_PLANKS);
        beamRing(x0, z0, x1, z1, g + 4, SPRUCE_LOG);
        ring(x0, z0, x1, z1, g + 5, g + 7, WHITE_CONCRETE);
        fill(x0 + 1, g + 5, z0 + 1, x1 - 1, g + 7, z1 - 1, AIR);
        posts(x0, z0, x1, z1, g + 5, g + 7, SPRUCE_LOG);
        fill(xm, g + 5, z0, xm, g + 7, z0, SPRUCE_LOG);
        fill(xm, g + 5, z1, xm, g + 7, z1, SPRUCE_LOG);
        beamRing(x0, z0, x1, z1, g + 7, SPRUCE_LOG);
        int ridge = gable(x0, z0, x1, z1, g + 8, true, BRICK_STAIRS, BRICKS, WHITE_CONCRETE, BRICKS);
        for (int y = g + 8; y < ridge; y++) {
            b(x0, y, zm, SPRUCE_LOG);
            b(x1, y, zm, SPRUCE_LOG);
        }
        // chimney and oven
        fill(x1 - 1, g + 1, zm, x1 - 1, ridge + 2, zm, BRICKS);
        fill(x1 - 1, g + 1, zm - 1, x1 - 1, g + 2, zm - 1, BRICKS);
        fill(x1 - 1, g + 1, zm + 1, x1 - 1, g + 2, zm + 1, BRICKS);
        b(x1 - 1, g + 3, zm, BRICKS);
        face(x1 - 2, g + 1, zm, CAMPFIRE, WEST);
        b(x1 - 1, ridge + 3, zm, STONE_BRICK_SLAB);
        face(x1 - 2, g + 1, zm - 1, SMOKER, WEST);
        face(x1 - 2, g + 1, zm + 1, FURNACE, WEST);
        // shop front with awning
        door(xm, g + 1, z0, SPRUCE_DOOR, NORTH);
        for (int x = x0 + 1; x <= x1 - 1; x++) {
            b(x, g + 4, z0 - 1, (x - x0) % 2 == 0 ? RED_WOOL : WHITE_WOOL);
            if ((x - x0) % 4 == 2) {
                lantern(x, g + 3, z0 - 1, true);
            }
        }
        for (int x : new int[] {x0 + 1, x0 + 2, x1 - 2, x1 - 1}) {
            window(x, g + 2, z0, NORTH, false, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            slab(x, g + 1, z0 - 1, SPRUCE_SLAB, false);
            b(x, g + 2, z0 - 1, Material.POTTED_POPPY);
        }
        for (int x : new int[] {x0 + 2, x0 + 6}) {
            window(x, g + 6, z0, NORTH, true, true, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 6, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
            window(x, g + 2, z1, SOUTH, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        }
        window(x0, g + 2, zm, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        window(x0, g + 6, zm, WEST, true, false, SPRUCE_TRAPDOOR, SPRUCE_SLAB);
        // shop counter, shelves, flour barrels
        for (int x = x0 + 5; x <= x0 + 7; x++) {
            slab(x, g + 1, z0 + 3, SPRUCE_SLAB, true);
        }
        for (int z = z0 + 4; z <= z0 + 6; z++) {
            slab(x0 + 5, g + 1, z, SPRUCE_SLAB, true);
        }
        b(x0 + 6, g + 2, z0 + 3, Material.POTTED_DANDELION);
        b(x0 + 5, g + 2, z0 + 5, Material.POTTED_POPPY);
        for (int x = x0 + 2; x <= x0 + 4; x++) {
            b(x, g + 1, z1 - 1, x % 2 == 0 ? BARREL : Material.HAY_BLOCK);
        }
        face(x0 + 2, g + 1, z1 - 2, CHEST, EAST);
        b(x1 - 1, g + 1, z0 + 1, CRAFTING_TABLE);
        b(x1 - 1, g + 1, z0 + 2, COMPOSTER);
        for (int x = x0 + 2; x <= x1 - 2; x += 2) {
            hang(g + 3, z0 + 2, x);
        }
        hang(g + 3, zm + 2, x0 + 3);
        stairsUp(x0 + 1, z0 + 1, SOUTH, SPRUCE_STAIRS);
        // living rooms above
        bedAlong(x0 + 2, z1 - 2, RED_BED, SOUTH);
        bedAlong(x0 + 4, z1 - 2, WHITE_BED, SOUTH);
        face(x0 + 3, g + 5, z1 - 1, CHEST, NORTH);
        for (int x = x0 + 2; x <= x0 + 3; x++) {
            table(x, g + 5, z0 + 2, SPRUCE_SLAB);
            chair(x, g + 5, z0 + 1, SPRUCE_STAIRS, NORTH);
            chair(x, g + 5, z0 + 3, SPRUCE_STAIRS, SOUTH);
        }
        lantern(x0 + 2, g + 6, z0 + 2, false);
        fill(x1 - 4, g + 5, z1 - 1, x1 - 1, g + 6, z1 - 1, BOOKSHELF);
        for (int v = z0 + 1; v < z1; v++) {
            log(xm, g + 8, v, SPRUCE_LOG, Axis.Z);
        }
        hang(g + 7, zm - 2, xm);
        hang(g + 7, zm + 2, xm);
        return new int[] {xm, z0 - 2};
    }
}
