import java.util.Random;

/**
 * Houses in the style of the old spawn: stone-brick bases, spruce timber frames, dark oak roofs, round towers with red
 * conical roofs ribbed in polished blackstone, red glass. Built on {@link Build#house} plus the signature towers.
 */
final class Medieval {

    static Random R;

    static final Build.Style PLASTER = new Build.Style("spruce_log", "stripped_spruce_log", "white_concrete", "white_concrete",
            "dark_oak", "dark_oak_planks", "spruce", "spruce", "dark_oak", "spruce_planks");
    static final Build.Style PLANK = new Build.Style("spruce_log", "stripped_spruce_log", "spruce_planks", "spruce_planks",
            "dark_oak", "dark_oak_planks", "spruce", "dark_oak", "dark_oak", "spruce_planks");
    static final Build.Style STONE = new Build.Style("stone_bricks", "polished_blackstone_bricks", "stone_bricks", "stone_bricks",
            "dark_oak", "dark_oak_planks", "spruce", "dark_oak", "dark_oak", "stone_bricks");
    static final Build.Style DARK = new Build.Style("dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "dark_oak_planks",
            "spruce", "spruce_planks", "dark_oak", "spruce", "spruce", "dark_oak_planks");

    enum Type { COTTAGE, TOWNHOUSE, TOWERHOUSE, MERCHANT, MANOR }

    /** A cone roof (the castle's towers): striped red wool and concrete with four blackstone ribs and a spire. */
    static void cone(B.Frame f, double cx, double cz, int y0, double r, int h) {
        for (int k = 0; k <= h; k++) {
            double rr = r * (1 - k / (double) h);
            int ir = (int) Math.ceil(rr) + 1;
            for (int dx = -ir; dx <= ir; dx++)
                for (int dz = -ir; dz <= ir; dz++) {
                    double x = Math.floor(cx) + dx + 0.5 - cx, z = Math.floor(cz) + dz + 0.5 - cz;
                    double d = Math.hypot(x, z);
                    if (d > rr + 0.45) continue;
                    int bx = (int) Math.floor(cx) + dx, bz = (int) Math.floor(cz) + dz;
                    boolean rib = Math.abs(x) < 0.6 || Math.abs(z) < 0.6;
                    boolean shell = d > rr - 1.1;
                    String s = !shell ? "red_wool" : rib ? "polished_blackstone_bricks" : ((k / 2) % 2 == 0 ? "red_wool" : "red_concrete");
                    f.set(bx, y0 + k, bz, s);
                }
        }
        // eaves: a ring of blackstone stairs just below the cone
        int ir = (int) Math.ceil(r) + 1;
        for (int dx = -ir; dx <= ir; dx++)
            for (int dz = -ir; dz <= ir; dz++) {
                double x = Math.floor(cx) + dx + 0.5 - cx, z = Math.floor(cz) + dz + 0.5 - cz;
                double d = Math.hypot(x, z);
                if (d > r + 0.95 || d <= r + 0.1) continue;
                B.Dir up = Math.abs(x) > Math.abs(z) ? (x > 0 ? B.Dir.WEST : B.Dir.EAST) : (z > 0 ? B.Dir.NORTH : B.Dir.SOUTH);
                f.setIfAir((int) Math.floor(cx) + dx, y0, (int) Math.floor(cz) + dz, B.stairs("polished_blackstone_brick", up));
            }
        int tx = (int) Math.floor(cx), tz = (int) Math.floor(cz);
        f.set(tx, y0 + h + 1, tz, "polished_blackstone_brick_wall");
        f.set(tx, y0 + h + 2, tz, "polished_blackstone_brick_wall");
        f.set(tx, y0 + h + 3, tz, "lightning_rod");
    }

    /** A round stone tower from the ground to {@code top}, windows of red glass, a cone roof. */
    static void roundTower(B.Frame f, double cx, double cz, double r, int top) {
        int ir = (int) Math.ceil(r) + 1;
        for (int dx = -ir; dx <= ir; dx++)
            for (int dz = -ir; dz <= ir; dz++) {
                int bx = (int) Math.floor(cx) + dx, bz = (int) Math.floor(cz) + dz;
                double d = Math.hypot(bx + 0.5 - cx, bz + 0.5 - cz);
                if (d > r + 0.45) continue;
                int g = Canvas.ground(f.wx(bx, bz), f.wz(bx, bz)) - f.oy();
                boolean wall = d > r - 1.1;
                for (int y = Math.min(g, 0) - 2; y <= top; y++) {
                    String s;
                    if (!wall) s = y <= 0 || y % 5 == 0 ? "spruce_planks" : "air";
                    else if (y == top) s = "stone_brick_slab[type=top]";
                    else if (y % 5 == 0) s = R.nextDouble() < 0.5 ? "stone_bricks" : "cobbled_deepslate";
                    else s = R.nextDouble() < 0.9 ? "stone_bricks" : "cracked_stone_bricks";
                    f.set(bx, y, bz, s);
                }
            }
        // narrow windows on four sides, every storey
        for (int y = 3; y < top - 1; y += 5)
            for (B.Dir d : B.Dir.values()) {
                int wx = (int) Math.floor(cx + d.dx * (r - 0.5)), wz = (int) Math.floor(cz + d.dz * (r - 0.5));
                f.set(wx, y, wz, "red_stained_glass_pane");
                f.set(wx, y + 1, wz, "red_stained_glass_pane");
            }
        f.set((int) Math.floor(cx), 1, (int) Math.floor(cz), "lantern");
        cone(f, cx, cz, top + 1, r + 1, (int) Math.round((r + 1) * 2.4));
    }

    static void well(B.Frame f, int x, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                f.set(x + dx, 0, z + dz, dx == 0 && dz == 0 ? "water" : "stone_bricks");
                if (dx != 0 || dz != 0) f.set(x + dx, 1, z + dz, "stone_brick_wall");
            }
        f.fill(x - 1, 2, z, x - 1, 3, z, "spruce_fence");
        f.fill(x + 1, 2, z, x + 1, 3, z, "spruce_fence");
        for (int dx = -2; dx <= 2; dx++) {
            f.set(dx + x, 4, z - 1, B.stairs("dark_oak", B.Dir.SOUTH));
            f.set(dx + x, 4, z + 1, B.stairs("dark_oak", B.Dir.NORTH));
            f.set(dx + x, 4, z, "dark_oak_planks");
            f.set(dx + x, 5, z, "dark_oak_slab[type=bottom]");
        }
        f.set(x, 3, z, "iron_chain[axis=y]");
    }

    static Build.Style style(Type t) {
        return switch (t) {
            case COTTAGE -> R.nextBoolean() ? PLANK : DARK;
            case TOWNHOUSE, TOWERHOUSE -> R.nextInt(3) == 0 ? PLANK : PLASTER;
            case MERCHANT -> R.nextBoolean() ? PLASTER : DARK;
            case MANOR -> STONE;
        };
    }

    static int[] size(Type t) {
        return switch (t) {
            case COTTAGE -> new int[] {7 + R.nextInt(2), 7};
            case TOWNHOUSE -> new int[] {8 + R.nextInt(2), 9};
            case TOWERHOUSE -> new int[] {9 + R.nextInt(2), 9};
            case MERCHANT -> new int[] {13 + R.nextInt(2), 9};
            case MANOR -> new int[] {15, 11};
        };
    }

    record Result(String style, int floors, int doorX, int doorZ) {}

    /** Builds the house with its front (door) at local +z; frame y 0 = ground-floor floor. */
    static Result build(Type t, B.Frame f, int w, int d) {
        Build.Style st = style(t);
        switch (t) {
            case COTTAGE -> {
                Build.house(f, w, d, 1, st, new Build.Opts(R.nextBoolean(), false, false, false, true, true, R.nextBoolean()));
                return new Result("stuga", 1, (w - 1) / 2, d);
            }
            case TOWNHOUSE -> {
                Build.house(f, w, d, 2, st, new Build.Opts(true, true, R.nextBoolean(), false, false, true, true));
                return new Result("borgarhus", 2, (w - 1) / 2, d);
            }
            case TOWERHOUSE -> {
                Build.house(f, w, d, 2, st, new Build.Opts(false, true, false, true, false, true, true));
                boolean left = R.nextBoolean();
                roundTower(f, left ? -0.5 : w - 0.5, d - 1.5, 2.6, 13);
                return new Result("tornhus", 2, (w - 1) / 2, d);
            }
            case MERCHANT -> {
                Build.house(f, w, d, 2, st, new Build.Opts(false, true, true, true, R.nextBoolean(), true, true));
                // a side wing with its own gable to the street
                B.Frame wing = new B.Frame(f.wx(w, 2), f.oy(), f.wz(w, 2), f.turns());
                Build.house(wing, 6, d - 3, 1, st, new Build.Opts(true, false, false, false, false, false, true));
                return new Result("köpmansgård", 2, (w - 1) / 2, d);
            }
            case MANOR -> {
                Build.house(f, w, d, 3, st, new Build.Opts(false, false, true, true, false, true, true));
                roundTower(f, -0.5, d - 0.5, 3.1, 18);
                roundTower(f, w - 0.5, d - 0.5, 3.1, 18);
                roundTower(f, w - 0.5, -0.5, 2.4, 14);
                return new Result("herrgård", 3, (w - 1) / 2, d);
            }
        }
        throw new IllegalStateException();
    }
}
