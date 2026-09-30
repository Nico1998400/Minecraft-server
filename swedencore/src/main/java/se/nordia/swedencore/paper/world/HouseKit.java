package se.nordia.swedencore.paper.world;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

import java.util.Random;

/** Block-placing primitives for hand-built houses: shells, roofs, doors, beds, furniture and pane connection. */
class HouseKit {

    protected final World w;
    protected final Random rnd = new Random();

    HouseKit(World w) {
        this.w = w;
    }

    // ---- raw placement

    protected void b(int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    protected void data(int x, int y, int z, BlockData d) {
        w.getBlockAt(x, y, z).setBlockData(d, false);
    }

    protected Material at(int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType();
    }

    protected boolean air(int x, int y, int z) {
        return at(x, y, z).isAir();
    }

    protected void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    b(x, y, z, m);
                }
            }
        }
    }

    /** Perimeter of a rectangle over a height range. */
    protected void ring(int x1, int z1, int x2, int z2, int y1, int y2, Material m) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                b(x, y, z1, m);
                b(x, y, z2, m);
            }
            for (int z = z1; z <= z2; z++) {
                b(x1, y, z, m);
                b(x2, y, z, m);
            }
        }
    }

    protected void posts(int x1, int z1, int x2, int z2, int y1, int y2, Material m) {
        fill(x1, y1, z1, x1, y2, z1, m);
        fill(x2, y1, z1, x2, y2, z1, m);
        fill(x1, y1, z2, x1, y2, z2, m);
        fill(x2, y1, z2, x2, y2, z2, m);
    }

    // ---- shaped blocks

    protected void stair(int x, int y, int z, Material m, BlockFace ascends, boolean top) {
        Stairs s = (Stairs) m.createBlockData();
        s.setFacing(ascends);
        s.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        data(x, y, z, s);
    }

    protected void slab(int x, int y, int z, Material m, boolean top) {
        Slab s = (Slab) m.createBlockData();
        s.setType(top ? Slab.Type.TOP : Slab.Type.BOTTOM);
        data(x, y, z, s);
    }

    protected void face(int x, int y, int z, Material m, BlockFace f) {
        BlockData d = m.createBlockData();
        if (d instanceof Directional dd) {
            dd.setFacing(f);
        }
        if (d instanceof FaceAttachable fa) {
            fa.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        }
        data(x, y, z, d);
    }

    protected void log(int x, int y, int z, Material m, Axis axis) {
        BlockData d = m.createBlockData();
        if (d instanceof Orientable o) {
            o.setAxis(axis);
        }
        data(x, y, z, d);
    }

    protected void door(int x, int y, int z, Material m, BlockFace f) {
        Door lower = (Door) m.createBlockData();
        lower.setFacing(f);
        lower.setHalf(Bisected.Half.BOTTOM);
        data(x, y, z, lower);
        Door upper = (Door) m.createBlockData();
        upper.setFacing(f);
        upper.setHalf(Bisected.Half.TOP);
        data(x, y + 1, z, upper);
    }

    /** Foot at (x,y,z), head one block further in {@code f}. */
    protected void bed(int x, int y, int z, Material m, BlockFace f) {
        Bed foot = (Bed) m.createBlockData();
        foot.setFacing(f);
        foot.setPart(Bed.Part.FOOT);
        data(x, y, z, foot);
        Bed head = (Bed) m.createBlockData();
        head.setFacing(f);
        head.setPart(Bed.Part.HEAD);
        data(x + f.getModX(), y, z + f.getModZ(), head);
    }

    protected void trap(int x, int y, int z, Material m, BlockFace f, boolean open, boolean top) {
        TrapDoor t = (TrapDoor) m.createBlockData();
        t.setFacing(f);
        t.setOpen(open);
        t.setHalf(top ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        data(x, y, z, t);
    }

    protected void lantern(int x, int y, int z, boolean hanging) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(hanging);
        data(x, y, z, l);
    }

    /** A wall torch at (x,y,z) pointing away from the wall in direction {@code out}. */
    protected void torch(int x, int y, int z, BlockFace out) {
        face(x, y, z, Material.WALL_TORCH, out);
    }

    // ---- composites

    /** Window: two panes, optional open shutters on the outside, optional flower sill. */
    protected void window(int x, int y, int z, BlockFace out, boolean shutters, boolean sill, Material shutterMat, Material sillSlab) {
        b(x, y, z, Material.GLASS_PANE);
        b(x, y + 1, z, Material.GLASS_PANE);
        int ox = out.getModX(), oz = out.getModZ();
        if (shutters) {
            int lx = oz != 0 ? 1 : 0, lz = ox != 0 ? 1 : 0;
            for (int s = -1; s <= 1; s += 2) {
                for (int dy = 0; dy <= 1; dy++) {
                    trap(x + s * lx + ox, y + dy, z + s * lz + oz, shutterMat, out.getOppositeFace(), true, false);
                }
            }
        }
        if (sill) {
            slab(x + ox, y - 1, z + oz, sillSlab, false);
            Material[] pots = {Material.POTTED_POPPY, Material.POTTED_DANDELION, Material.POTTED_OXEYE_DAISY, Material.POTTED_FERN, Material.POTTED_RED_TULIP};
            b(x + ox, y, z + oz, pots[rnd.nextInt(pots.length)]);
        }
    }

    /** One-block slit window. */
    protected void slit(int x, int y, int z) {
        b(x, y, z, Material.GLASS_PANE);
    }

    protected void table(int x, int y, int z, Material slabMat) {
        slab(x, y, z, slabMat, true);
    }

    /** A chair: a stair whose back faces away from the table. */
    protected void chair(int x, int y, int z, Material stairMat, BlockFace backs) {
        stair(x, y, z, stairMat, backs, false);
    }

    /** Solid roof-row block helper for gable roofs, in (u,v) space. */
    private void put(boolean alongX, int u, int y, int v, Material m) {
        if (alongX) {
            b(u, y, v, m);
        } else {
            b(v, y, u, m);
        }
    }

    private void putStair(boolean alongX, int u, int y, int v, Material m, BlockFace f) {
        if (alongX) {
            stair(u, y, v, m, f, false);
        } else {
            stair(v, y, u, m, f, false);
        }
    }

    /**
     * A gable roof with one-block overhang. {@code yTop} is the first roof row (one above the wall top). The footprint
     * must be an odd number of blocks across the slope so a ridge block exists. Returns the ridge height.
     */
    protected int gable(int x0, int z0, int x1, int z1, int yTop, boolean alongX, Material stairMat, Material plate,
                        Material endFill, Material ridge) {
        int u0 = alongX ? x0 : z0, u1 = alongX ? x1 : z1, v0 = alongX ? z0 : x0, v1 = alongX ? z1 : x1;
        BlockFace low = alongX ? BlockFace.SOUTH : BlockFace.EAST;
        BlockFace high = alongX ? BlockFace.NORTH : BlockFace.WEST;
        for (int u = u0; u <= u1; u++) {
            put(alongX, u, yTop, v0, plate);
            put(alongX, u, yTop, v1, plate);
        }
        int ridgeY = yTop;
        for (int k = 0; ; k++) {
            int vn = v0 - 1 + k, vs = v1 + 1 - k, y = yTop + k;
            if (vn > vs) {
                break;
            }
            for (int u = u0 - 1; u <= u1 + 1; u++) {
                if (vn < vs) {
                    putStair(alongX, u, y, vn, stairMat, low);
                    putStair(alongX, u, y, vs, stairMat, high);
                } else {
                    put(alongX, u, y, vn, ridge);
                    ridgeY = y;
                }
            }
            for (int v = vn + 1; v <= vs - 1; v++) {
                put(alongX, u0, y, v, endFill);
                put(alongX, u1, y, v, endFill);
            }
        }
        return ridgeY;
    }

    /** A stepped pyramid roof over a rectangle (stairs on the edges, blocks on the corners). */
    protected int pyramid(int x0, int z0, int x1, int z1, int y, Material stairMat, Material block) {
        int a = x0 - 1, c = z0 - 1, d = x1 + 1, e = z1 + 1;
        while (a <= d && c <= e) {
            for (int x = a; x <= d; x++) {
                for (int z = c; z <= e; z++) {
                    boolean edgeX = x == a || x == d, edgeZ = z == c || z == e;
                    if (!edgeX && !edgeZ) {
                        continue;
                    }
                    if (edgeX && edgeZ || d - a < 2 || e - c < 2) {
                        b(x, y, z, block);
                    } else if (z == c) {
                        stair(x, y, z, stairMat, BlockFace.SOUTH, false);
                    } else if (z == e) {
                        stair(x, y, z, stairMat, BlockFace.NORTH, false);
                    } else if (x == a) {
                        stair(x, y, z, stairMat, BlockFace.EAST, false);
                    } else {
                        stair(x, y, z, stairMat, BlockFace.WEST, false);
                    }
                }
            }
            a++;
            c++;
            d--;
            e--;
            y++;
        }
        if (a == d && c == e) {
            b(a, y, c, block);
            y++;
        }
        return y;
    }

    /** A filled disc (for round towers and cones). */
    protected void disc(double cx, double cz, double r, int y, Material m) {
        int reach = (int) Math.ceil(r) + 1;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (Math.hypot(dx, dz) <= r) {
                    b((int) cx + dx, y, (int) cz + dz, m);
                }
            }
        }
    }

    /** Re-shapes panes, bars and fences so they join their neighbours. */
    protected void connect(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Block block = w.getBlockAt(x, y, z);
                    Material type = block.getType();
                    boolean pane = type == Material.GLASS_PANE || type == Material.IRON_BARS;
                    if (!pane && !Tag.WOODEN_FENCES.isTagged(type)) {
                        continue;
                    }
                    BlockData d = block.getBlockData();
                    if (!(d instanceof MultipleFacing mf)) {
                        continue;
                    }
                    for (BlockFace f : mf.getAllowedFaces()) {
                        Block n = block.getRelative(f);
                        Material nt = n.getType();
                        boolean join = nt == type
                                || pane && (nt == Material.GLASS_PANE || nt == Material.IRON_BARS)
                                || !pane && Tag.FENCE_GATES.isTagged(nt)
                                || n.getBlockData().isFaceSturdy(f.getOppositeFace(), BlockSupport.FULL);
                        mf.setFace(f, join);
                    }
                    block.setBlockData(mf, false);
                }
            }
        }
    }
}
