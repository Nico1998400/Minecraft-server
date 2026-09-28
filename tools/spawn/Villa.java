import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Scandinavian villas in ten architectural families. Each family has its own massing, roof, windows and details, and
 * varies in size, colour and features, so no two houses on the street come from the same template. Local frame: front
 * = +z (towards the street), footprint x 0..w-1, z 0..d-1, y 0 = ground-floor floor. Storeys are 4 high.
 */
final class Villa {

    enum Family { COTTAGE, TURN_OF_CENTURY, FUNKIS, MANSARD, SPLIT_LEVEL, TWO_STOREY, MODERN, LUXURY, CORNER, LONGHOUSE_60S }

    /** What the garden needs to know: occupied footprint, where the front door opens, where cars go. */
    static final class Info {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        int doorX, doorZ, garageX = -1, garageW, floors = 1, bedrooms = 1, area;
        Family family;
        String style;

        void cover(int ax0, int az0, int ax1, int az1) {
            x0 = Math.min(x0, Math.min(ax0, ax1));
            z0 = Math.min(z0, Math.min(az0, az1));
            x1 = Math.max(x1, Math.max(ax0, ax1));
            z1 = Math.max(z1, Math.max(az0, az1));
        }
    }

    record Look(String wall, String wall2, String trim, String roof, String roofBlock, String door, String base) {}

    static final String[] WOOD = {"red_terracotta", "mangrove_planks", "white_concrete", "pale_oak_planks", "yellow_terracotta",
            "smooth_sandstone", "light_blue_terracotta", "cyan_terracotta", "green_terracotta", "white_terracotta"};
    static final String[] ROOF = {"deepslate_tile", "brick", "resin_brick", "polished_blackstone_brick", "dark_oak", "deepslate_tile"};

    static Random R;

    static String roofBlock(String roof) {
        return switch (roof) {
            case "deepslate_tile" -> "deepslate_tiles";
            case "brick" -> "bricks";
            case "resin_brick" -> "resin_bricks";
            case "polished_blackstone_brick" -> "polished_blackstone_bricks";
            case "waxed_oxidized_cut_copper" -> "waxed_oxidized_cut_copper";
            case "mud_brick" -> "mud_bricks";
            default -> roof + "_planks";
        };
    }

    static Look look(String wall, String roof) {
        return new Look(wall, wall, "pale_oak", roof, roofBlock(roof), R.nextBoolean() ? "spruce" : "dark_oak", "stone_bricks");
    }

    static String log(String m, char axis) {
        return m + "[axis=" + axis + "]";
    }

    // ------------------------------------------------------------------ elements

    /** Walls of a storey (hollow), floor slab at y0, corners in trim for timber houses. */
    static void storey(B.Frame f, int x0, int z0, int x1, int z1, int y0, String wall, String corner, String floor) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                f.set(x, y0, z, floor);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                boolean cornerCell = (x == x0 || x == x1) && (z == z0 || z == z1);
                for (int y = y0 + 1; y <= y0 + 3; y++) f.set(x, y, z, !edge ? "air" : cornerCell && corner != null ? corner : wall);
            }
    }

    enum Win { TRAD, RIBBON, GLASS }

    /** Windows on one side of a box, avoiding the columns in {@code skip}. side: 0 = front (+z), 1 = back, 2 = west, 3 = east. */
    static void windows(B.Frame f, int x0, int z0, int x1, int z1, int y0, int side, Win style, String trim, int spacing, int... skip) {
        boolean alongX = side < 2;
        int a0 = alongX ? x0 : z0, a1 = alongX ? x1 : z1;
        int fixed = switch (side) {
            case 0 -> z1;
            case 1 -> z0;
            case 2 -> x0;
            default -> x1;
        };
        B.Dir out = switch (side) {
            case 0 -> B.Dir.SOUTH;
            case 1 -> B.Dir.NORTH;
            case 2 -> B.Dir.WEST;
            default -> B.Dir.EAST;
        };
        switch (style) {
            case TRAD -> {
                for (int a = a0 + 2; a <= a1 - 2; a += spacing) {
                    if (skipped(a, skip)) continue;
                    set(f, alongX, a, fixed, y0 + 1, trim + "_planks");
                    set(f, alongX, a, fixed, y0 + 2, "glass_pane");
                    set(f, alongX, a, fixed, y0 + 3, "glass_pane");
                    outside(f, alongX, a, fixed, out, y0 + 1, B.trapdoor(trim, out, false, true));
                }
            }
            case RIBBON -> {
                int run = 0;
                for (int a = a0 + 1; a <= a1 - 1; a++) {
                    if (skipped(a, skip) || (run >= 5 && (run = 0) == 0)) continue;
                    set(f, alongX, a, fixed, y0 + 2, "glass_pane");
                    run++;
                }
            }
            case GLASS -> {
                for (int a = a0 + 1; a <= a1 - 1; a++) {
                    if (skipped(a, skip)) continue;
                    for (int y = y0 + 1; y <= y0 + 3; y++) set(f, alongX, a, fixed, y, "glass");
                }
            }
        }
    }

    static boolean skipped(int a, int[] skip) {
        for (int s : skip) if (Math.abs(a - s) <= 1) return true;
        return false;
    }

    static void set(B.Frame f, boolean alongX, int a, int fixed, int y, String s) {
        if (alongX) f.set(a, y, fixed, s);
        else f.set(fixed, y, a, s);
    }

    static void outside(B.Frame f, boolean alongX, int a, int fixed, B.Dir out, int y, String s) {
        if (alongX) f.setIfAir(a, y, fixed + out.dz, s);
        else f.setIfAir(fixed + out.dx, y, a, s);
    }

    static void door(B.Frame f, int x, int y, int z, String mat, Info info) {
        f.set(x, y + 1, z, mat + "_door[facing=south,half=lower,hinge=left]");
        f.set(x, y + 2, z, mat + "_door[facing=south,half=upper,hinge=left]");
        info.doorX = x;
        info.doorZ = z + 1;
    }

    /** Gable roof over the rectangle (overhang added by the caller), stairs rising one per row. Returns the ridge height. */
    static int gable(B.Frame f, int x0, int z0, int x1, int z1, int y, boolean ridgeX, String mat, String block, String gableFill) {
        int a0 = ridgeX ? z0 : x0, a1 = ridgeX ? z1 : x1, b0 = ridgeX ? x0 : z0, b1 = ridgeX ? x1 : z1;
        B.Dir up0 = ridgeX ? B.Dir.SOUTH : B.Dir.EAST, up1 = ridgeX ? B.Dir.NORTH : B.Dir.WEST;
        int lo = a0, hi = a1, yy = y, top = y;
        while (lo <= hi) {
            for (int b = b0; b <= b1; b++) {
                if (lo == hi) ab(f, ridgeX, lo, yy, b, block);
                else {
                    ab(f, ridgeX, lo, yy, b, B.stairs(mat, up0));
                    ab(f, ridgeX, hi, yy, b, B.stairs(mat, up1));
                }
            }
            if (gableFill != null)
                for (int a = lo + 1; a < hi; a++) {
                    ab(f, ridgeX, a, yy, b0 + 1, gableFill);
                    ab(f, ridgeX, a, yy, b1 - 1, gableFill);
                }
            top = yy;
            lo++;
            hi--;
            yy++;
        }
        return top;
    }

    static void ab(B.Frame f, boolean ridgeX, int a, int y, int b, String s) {
        if (ridgeX) f.set(b, y, a, s);
        else f.set(a, y, b, s);
    }

    /** Hip roof: every side slopes inwards; ends in a short ridge. */
    static int hip(B.Frame f, int x0, int z0, int x1, int z1, int y, String mat, String block) {
        int top = y;
        for (int k = 0; x0 + k <= x1 - k && z0 + k <= z1 - k; k++) {
            int ax0 = x0 + k, ax1 = x1 - k, az0 = z0 + k, az1 = z1 - k;
            int yy = y + k;
            top = yy;
            boolean line = ax0 == ax1 || az0 == az1;
            for (int x = ax0; x <= ax1; x++)
                for (int z = az0; z <= az1; z++) {
                    boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
                    if (!edge) {
                        f.set(x, yy, z, block);
                        continue;
                    }
                    if (line) {
                        f.set(x, yy, z, B.slab(mat.equals("dark_oak") ? "dark_oak" : mat));
                        continue;
                    }
                    B.Dir up = z == az0 ? B.Dir.SOUTH : z == az1 ? B.Dir.NORTH : x == ax0 ? B.Dir.EAST : B.Dir.WEST;
                    f.set(x, yy, z, B.stairs(mat, up));
                }
            if (line) break;
        }
        return top;
    }

    /** Mansard: a steep lower slope (two rows per step) under a shallow slab top. */
    static int mansard(B.Frame f, int x0, int z0, int x1, int z1, int y, String mat, String block) {
        int k = 0, yy = y;
        for (int row = 0; row < 4; row++) {
            int ax0 = x0 + k, ax1 = x1 - k, az0 = z0 + k, az1 = z1 - k;
            for (int x = ax0; x <= ax1; x++)
                for (int z = az0; z <= az1; z++) {
                    boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
                    if (!edge) continue;
                    B.Dir up = z == az0 ? B.Dir.SOUTH : z == az1 ? B.Dir.NORTH : x == ax0 ? B.Dir.EAST : B.Dir.WEST;
                    f.set(x, yy, z, row % 2 == 1 ? B.stairs(mat, up) : block);
                }
            if (row % 2 == 1) k++;
            yy++;
        }
        for (int x = x0 + k; x <= x1 - k; x++) for (int z = z0 + k; z <= z1 - k; z++) f.set(x, yy - 1, z, block);
        for (int x = x0 + k + 1; x <= x1 - k - 1; x++) for (int z = z0 + k + 1; z <= z1 - k - 1; z++) f.set(x, yy, z, B.slab(mat));
        return yy;
    }

    /** Flat roof with a parapet. */
    static int flat(B.Frame f, int x0, int z0, int x1, int z1, int y, String deck, String parapet) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                f.set(x, y, z, deck);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (edge && parapet != null) f.set(x, y + 1, z, parapet);
            }
        return y + 1;
    }

    /** Low-pitched roof built from slabs: half a block per step (1970s look). ridgeX: ridge along x. */
    static int lowGable(B.Frame f, int x0, int z0, int x1, int z1, int y, boolean ridgeX, String mat) {
        int a0 = ridgeX ? z0 : x0, a1 = ridgeX ? z1 : x1, b0 = ridgeX ? x0 : z0, b1 = ridgeX ? x1 : z1;
        int mid2 = a0 + a1, top = y;
        for (int a = a0; a <= a1; a++) {
            int steps = Math.min(a - a0, a1 - a);
            int yy = y + steps / 2;
            String s = steps % 2 == 0 ? B.slab(mat) : B.slabTop(mat);
            for (int b = b0; b <= b1; b++) ab(f, ridgeX, a, yy, b, s);
            top = Math.max(top, yy);
        }
        return top;
    }

    /** Mono-pitch roof rising towards the back (-z), half a block per row. */
    static int mono(B.Frame f, int x0, int z0, int x1, int z1, int y, String mat) {
        int top = y;
        for (int z = z1; z >= z0; z--) {
            int steps = z1 - z;
            int yy = y + steps / 2;
            for (int x = x0; x <= x1; x++) f.set(x, yy, z, steps % 2 == 0 ? B.slab(mat) : B.slabTop(mat));
            top = Math.max(top, yy);
        }
        return top;
    }

    static void chimney(B.Frame f, int x, int z, int y0, int y1, String mat) {
        for (int y = y0; y <= y1; y++) f.set(x, y, z, mat);
        f.set(x, y1 + 1, z, "campfire[lit=true,signal_fire=false]");
    }

    /** Glazed front veranda with posts, railing and a lean-to roof. */
    static void veranda(B.Frame f, int x0, int x1, int zFront, int y, String trim, String roof, boolean glazed) {
        for (int x = x0; x <= x1; x++)
            for (int z = zFront + 1; z <= zFront + 3; z++) f.set(x, y, z, trim + "_planks");
        for (int x = x0; x <= x1; x++) {
            boolean post = x == x0 || x == x1 || (x - x0) % 3 == 0;
            for (int y2 = y + 1; y2 <= y + 3; y2++) {
                if (post) f.set(x, y2, zFront + 3, log("stripped_birch_log", 'y').replace("stripped_birch_log", trim.equals("pale_oak") ? "stripped_birch_log" : "stripped_spruce_log"));
                else if (glazed) f.set(x, y2, zFront + 3, y2 == y + 1 ? trim + "_planks" : "glass_pane");
                else if (y2 == y + 1) f.set(x, y2, zFront + 3, trim + "_fence");
            }
            f.set(x, y + 4, zFront + 1, B.stairs(roof, B.Dir.NORTH));
            f.set(x, y + 4, zFront + 2, B.slab(roof));
            f.set(x, y + 4, zFront + 3, B.slab(roof));
        }
        for (int z = zFront + 1; z <= zFront + 2; z++) {
            if (glazed) {
                for (int y2 = y + 2; y2 <= y + 3; y2++) {
                    f.set(x0, y2, z, "glass_pane");
                    f.set(x1, y2, z, "glass_pane");
                }
                f.set(x0, y + 1, z, trim + "_planks");
                f.set(x1, y + 1, z, trim + "_planks");
            }
        }
        int mid = (x0 + x1) / 2;
        f.set(mid, y + 3, zFront + 2, "lantern[hanging=true]");
        f.set(mid, y + 1, zFront + 3, "air");
        f.set(mid, y + 2, zFront + 3, "air");
    }

    /** Small entrance canopy over the door on two brackets. */
    static void canopy(B.Frame f, int x, int y, int z, String trim) {
        for (int xx = x - 1; xx <= x + 1; xx++) f.set(xx, y + 4, z + 1, B.slab(trim));
        f.set(x - 1, y + 3, z + 1, B.stairsTop(trim, B.Dir.EAST));
        f.set(x + 1, y + 3, z + 1, B.stairsTop(trim, B.Dir.WEST));
        f.set(x + 1, y + 2, z + 1, "lantern[hanging=true]");
    }

    static void balcony(B.Frame f, int x0, int x1, int z, int y, String deck, String rail) {
        for (int x = x0; x <= x1; x++) {
            f.set(x, y, z + 1, deck);
            f.set(x, y + 1, z + 2 - 1 + 1, "air");
            f.set(x, y, z + 2, deck);
            f.set(x, y + 1, z + 2, rail);
            f.set(x, y - 1, z + 1, x == x0 || x == x1 ? B.stairsTop(deck.replace("_planks", "").replace("smooth_quartz", "smooth_quartz"), B.Dir.NORTH) : "air");
        }
        for (int zz = z + 1; zz <= z + 2; zz++) {
            f.set(x0, y + 1, zz, rail);
            f.set(x1, y + 1, zz, rail);
        }
    }

    static void bay(B.Frame f, int x0, int x1, int z, int y, String wall, String roof) {
        for (int x = x0; x <= x1; x++) {
            f.set(x, y, z + 1, wall);
            f.set(x, y + 1, z + 1, x == x0 || x == x1 ? wall : "glass_pane");
            f.set(x, y + 2, z + 1, x == x0 || x == x1 ? wall : "glass_pane");
            f.set(x, y + 3, z + 1, B.slab(roof));
        }
        for (int x = x0 + 1; x < x1; x++) f.set(x, y + 1, z, "air");
    }

    static void dormer(B.Frame f, int c, int zWall, int y, String wall, String roof, String trim) {
        for (int z = zWall - 3; z <= zWall; z++) {
            f.set(c - 1, y, z, wall);
            f.set(c + 1, y, z, wall);
            f.set(c - 1, y + 1, z, wall);
            f.set(c + 1, y + 1, z, wall);
            f.set(c, y, z, z == zWall ? "glass_pane" : "air");
            f.set(c, y + 1, z, z == zWall ? "glass_pane" : "air");
        }
        for (int z = zWall - 3; z <= zWall + 1; z++) {
            f.set(c - 2, y + 2, z, B.stairs(roof, B.Dir.EAST));
            f.set(c + 2, y + 2, z, B.stairs(roof, B.Dir.WEST));
            f.set(c - 1, y + 3, z, B.stairs(roof, B.Dir.EAST));
            f.set(c + 1, y + 3, z, B.stairs(roof, B.Dir.WEST));
            f.set(c, y + 3, z, roofBlock(roof));
            f.set(c, y + 2, z, z == zWall + 1 ? B.slabTop(trim) : "air");
        }
    }

    /** Attached garage at the given side: box with a metal door facing the street and a flat or pitched roof. */
    static void garage(B.Frame f, int x0, int z0, int x1, int z1, int y, String wall, String roof, boolean pitched, Info info) {
        storey(f, x0, z0, x1, z1, y, wall, null, "light_gray_concrete");
        int mid = (x0 + x1) / 2;
        for (int x = mid - 1; x <= mid + 1; x++)
            for (int yy = y + 1; yy <= y + 2; yy++) f.set(x, yy, z1, "iron_trapdoor[facing=south,open=true,half=bottom]");
        if (pitched) gable(f, x0 - 1, z0, x1 + 1, z1 + 1, y + 4, false, roof, roofBlock(roof), wall);
        else flat(f, x0, z0, x1, z1, y + 4, "smooth_stone", "smooth_stone_slab[type=bottom]");
        info.garageX = mid;
        info.garageW = 3;
        info.cover(x0, z0, x1, z1);
    }

    static void carport(B.Frame f, int x0, int z0, int x1, int z1, int y) {
        for (int x : new int[] {x0, x1}) for (int z : new int[] {z0, z1}) f.fill(x, y + 1, z, x, y + 3, z, "dark_oak_fence");
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            f.set(x, y + 4, z, "dark_oak_slab[type=bottom]");
            f.set(x, y, z, "light_gray_concrete");
        }
    }

    static void deck(B.Frame f, int x0, int z0, int x1, int z1, int y, boolean rail) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                f.set(x, y, z, ((x + z) & 1) == 0 ? "spruce_planks" : "stripped_spruce_wood");
                boolean edge = x == x0 || x == x1 || z == z0;
                if (rail && edge) f.setIfAir(x, y + 1, z, "spruce_fence");
            }
    }

    static void pool(B.Frame f, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0 - 1; x <= x1 + 1; x++)
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean rim = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1;
                f.set(x, y - 3, z, "prismarine_bricks");
                if (rim) {
                    for (int yy = y - 2; yy < y; yy++) f.set(x, yy, z, "prismarine_bricks");
                    f.set(x, y, z, "smooth_quartz");
                } else {
                    f.set(x, y - 2, z, (x + z) % 4 == 0 ? "sea_lantern" : "prismarine_bricks");
                    f.set(x, y - 1, z, "water");
                    f.set(x, y, z, "water");
                }
            }
    }

    static void interior(B.Frame f, int x0, int z0, int x1, int z1, int y) {
        f.set(x0 + 1, y + 1, z0 + 1, "lantern");
        f.set(x1 - 1, y + 1, z1 - 1, "lantern");
        f.set(x1 - 1, y + 1, z0 + 1, R.nextBoolean() ? "bookshelf" : "barrel[facing=up]");
        f.set(x0 + 1, y + 1, z1 - 1, R.nextBoolean() ? "crafting_table" : "spruce_shelf[facing=south]");
        if (x1 - x0 > 5) f.set((x0 + x1) / 2, y + 3, (z0 + z1) / 2, "lantern[hanging=true]");
    }

    // ------------------------------------------------------------------ families

    static Info build(Family fam, B.Frame f, int w, int d, Random r) {
        R = r;
        Info info = new Info();
        info.family = fam;
        switch (fam) {
            case COTTAGE -> cottage(f, w, d, info);
            case TURN_OF_CENTURY -> turnOfCentury(f, w, d, info);
            case FUNKIS -> funkis(f, w, d, info);
            case MANSARD -> mansardHouse(f, w, d, info);
            case SPLIT_LEVEL -> splitLevel(f, w, d, info);
            case TWO_STOREY -> twoStorey(f, w, d, info);
            case MODERN -> modern(f, w, d, info, false);
            case LUXURY -> modern(f, w, d, info, true);
            case CORNER -> corner(f, w, d, info);
            case LONGHOUSE_60S -> sixties(f, w, d, info);
        }
        info.cover(0, 0, w - 1, d - 1);
        info.area = (info.x1 - info.x0 + 1) * (info.z1 - info.z0 + 1) * info.floors;
        info.bedrooms = Math.max(1, Math.min(6, info.area / 45));
        return info;
    }

    /** Torp: a small one-and-a-half storey cottage, steep gable, white trim, a porch or a glazed veranda. */
    static void cottage(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"red_terracotta", "mangrove_planks", "yellow_terracotta", "light_blue_terracotta", "green_terracotta"});
        Look k = look(wall, r(new String[] {"deepslate_tile", "brick", "resin_brick"}));
        info.style = "torp";
        storey(f, 0, 0, w - 1, d - 1, 0, wall, "pale_oak_planks", "spruce_planks");
        int dx = w / 2;
        windows(f, 0, 0, w - 1, d - 1, 0, 0, Win.TRAD, "pale_oak", 3, dx);
        windows(f, 0, 0, w - 1, d - 1, 0, 1, Win.TRAD, "pale_oak", 3);
        windows(f, 0, 0, w - 1, d - 1, 0, 2, Win.TRAD, "pale_oak", 3);
        windows(f, 0, 0, w - 1, d - 1, 0, 3, Win.TRAD, "pale_oak", 3);
        door(f, dx, 0, d - 1, k.door(), info);
        int top = gable(f, -1, -1, w, d, 4, true, k.roof(), k.roofBlock(), wall);
        if (R.nextBoolean()) veranda(f, dx - 2, dx + 2, d - 1, 0, "pale_oak", k.roof(), R.nextBoolean());
        else canopy(f, dx, 0, d - 1, "pale_oak");
        chimney(f, 1, d / 2, 1, top + 1, "bricks");
        interior(f, 0, 0, w - 1, d - 1, 0);
    }

    /** Sekelskiftesvilla: two storeys, veranda, a corner tower with a pointed roof, decorative trim. */
    static void turnOfCentury(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"yellow_terracotta", "white_concrete", "smooth_sandstone", "light_blue_terracotta", "pale_oak_planks"});
        Look k = look(wall, r(new String[] {"deepslate_tile", "waxed_oxidized_cut_copper", "polished_blackstone_brick"}));
        info.style = "sekelskiftesvilla";
        info.floors = 2;
        for (int s = 0; s < 2; s++) {
            storey(f, 0, 0, w - 1, d - 1, s * 4, wall, "pale_oak_planks", "spruce_planks");
            for (int side = 0; side < 4; side++) windows(f, 0, 0, w - 1, d - 1, s * 4, side, Win.TRAD, "pale_oak", 3, s == 0 && side == 0 ? w / 2 : -99);
        }
        for (int x = 0; x < w; x++) {
            f.set(x, 4, -1, B.stairsTop("pale_oak", B.Dir.SOUTH));
            f.set(x, 4, d, B.stairsTop("pale_oak", B.Dir.NORTH));
        }
        door(f, w / 2, 0, d - 1, k.door(), info);
        veranda(f, 1, w - 2, d - 1, 0, "pale_oak", k.roof(), false);
        int top = hip(f, -1, -1, w, d, 8, k.roof(), k.roofBlock());
        // corner tower
        int tx = w - 3, tz = -1;
        for (int y = 0; y <= 12; y++)
            for (int x = tx; x <= tx + 3; x++)
                for (int z = tz; z <= tz + 3; z++) {
                    boolean edge = x == tx || x == tx + 3 || z == tz || z == tz + 3;
                    if (!edge) continue;
                    f.set(x, y, z, y % 4 == 2 && (x == tx + 1 || x == tx + 2 || z == tz + 1 || z == tz + 2) && y > 0 ? "glass_pane" : wall);
                }
        for (int k2 = 0; k2 < 4; k2++) {
            int hs = 2 - k2 / 2;
            int y = 13 + k2;
            for (int x = tx + 1 - hs + 1; x <= tx + 2 + hs - 1; x++)
                for (int z = tz + 1 - hs + 1; z <= tz + 2 + hs - 1; z++) f.set(x, y, z, B.slab(k.roof()).replace("_slab[type=bottom]", "_slab[type=bottom]"));
        }
        hip(f, tx - 1, tz - 1, tx + 4, tz + 4, 13, k.roof(), k.roofBlock());
        f.set(tx + 1, 17, tz + 1, "lightning_rod");
        chimney(f, 2, d / 2, 1, top + 1, "bricks");
        balcony(f, 2, 5, d - 1, 4, "pale_oak_planks", "pale_oak_fence");
        interior(f, 0, 0, w - 1, d - 1, 0);
        interior(f, 0, 0, w - 1, d - 1, 4);
        info.cover(-1, -1, w, d + 3);
    }

    /** Funkis: a white 1930s box with a flat roof, ribbon windows, a corner balcony with steel railing. */
    static void funkis(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"white_concrete", "white_concrete", "calcite", "white_terracotta", "light_gray_concrete"});
        info.style = "funkisvilla";
        info.floors = 2;
        for (int s = 0; s < 2; s++) {
            storey(f, 0, 0, w - 1, d - 1, s * 4, wall, null, "spruce_planks");
            for (int side = 0; side < 4; side++) windows(f, 0, 0, w - 1, d - 1, s * 4, side, Win.RIBBON, "pale_oak", 3, s == 0 && side == 0 ? 2 : -99);
        }
        door(f, 2, 0, d - 1, "dark_oak", info);
        for (int x = 1; x <= 3; x++) f.set(x, 4, d, "smooth_quartz_slab[type=top]");
        flat(f, -1, -1, w, d, 8, "smooth_stone", wall);
        // corner balcony on the upper floor with an iron railing
        for (int x = w - 5; x <= w; x++)
            for (int z = d - 1; z <= d + 1; z++)
                if (x >= w - 1 || z >= d) {
                    f.set(x, 4, z, "smooth_quartz");
                    boolean edge = x == w || z == d + 1;
                    if (edge) f.set(x, 5, z, "iron_bars");
                }
        for (int z = d - 3; z <= d - 2; z++) f.set(w - 1, 6, z, "glass_pane");
        f.set(w - 3, 5, d - 1, "dark_oak_door[facing=south,half=lower,hinge=right]");
        f.set(w - 3, 6, d - 1, "dark_oak_door[facing=south,half=upper,hinge=right]");
        if (w >= 10) garage(f, w, 1, w + 5, d - 2, 0, wall, "smooth_stone", false, info);
        interior(f, 0, 0, w - 1, d - 1, 0);
        interior(f, 0, 0, w - 1, d - 1, 4);
        info.cover(-1, -1, w + 1, d + 1);
    }

    /** Older renovated villa under a mansard roof ("brutet tak"), dormers, stone plinth. */
    static void mansardHouse(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"red_terracotta", "yellow_terracotta", "pale_oak_planks", "white_terracotta", "green_terracotta"});
        Look k = look(wall, r(new String[] {"deepslate_tile", "brick", "resin_brick"}));
        info.style = "villa med mansardtak";
        info.floors = 2;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) f.set(x, -1, z, "stone_bricks");
        storey(f, 0, 0, w - 1, d - 1, 0, wall, "pale_oak_planks", "spruce_planks");
        for (int side = 0; side < 4; side++) windows(f, 0, 0, w - 1, d - 1, 0, side, Win.TRAD, "pale_oak", 3, side == 0 ? w / 2 : -99);
        door(f, w / 2, 0, d - 1, k.door(), info);
        canopy(f, w / 2, 0, d - 1, "pale_oak");
        int top = mansard(f, -1, -1, w, d, 4, k.roof(), k.roofBlock());
        for (int c = 2; c < w - 2; c += 4) {
            f.set(c, 5, d, "glass_pane");
            f.set(c, 5, -1, "glass_pane");
        }
        chimney(f, w - 3, d / 2, 1, top + 1, "bricks");
        interior(f, 0, 0, w - 1, d - 1, 0);
        if (R.nextBoolean()) bay(f, 1, 4, d - 1, 0, wall, k.roof());
    }

    /** 1970s split-level: brick lower floor with the garage into the slope, timber upper floor, low roof. */
    static void splitLevel(B.Frame f, int w, int d, Info info) {
        String upper = r(new String[] {"spruce_planks", "dark_oak_planks", "brown_terracotta", "white_concrete"});
        info.style = "suterränghus";
        info.floors = 2;
        storey(f, 0, 0, w - 1, d - 1, 0, "bricks", null, "light_gray_concrete");
        storey(f, 0, 0, w - 1, d - 1, 4, upper, null, "spruce_planks");
        for (int side = 0; side < 4; side++) {
            windows(f, 0, 0, w - 1, d - 1, 0, side, Win.RIBBON, "pale_oak", 3, side == 0 ? 3 : -99);
            windows(f, 0, 0, w - 1, d - 1, 4, side, Win.GLASS, "pale_oak", 3, 1, 5, 9);
        }
        for (int x = 2; x <= 4; x++) for (int y = 1; y <= 2; y++) f.set(x, y, d - 1, "iron_trapdoor[facing=south,open=true,half=bottom]");
        info.garageX = 3;
        info.garageW = 3;
        door(f, w - 3, 0, d - 1, "spruce", info);
        lowGable(f, -1, -1, w, d, 8, true, "dark_oak");
        balcony(f, w - 6, w - 2, d - 1, 4, "spruce_planks", "spruce_fence");
        interior(f, 0, 0, w - 1, d - 1, 4);
        info.cover(-1, -1, w, d + 2);
    }

    /** Two-storey family house of the 1990s: gable roof, attached garage, entrance canopy, maybe a balcony. */
    static void twoStorey(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"white_concrete", "light_blue_terracotta", "pale_oak_planks", "red_terracotta", "cyan_terracotta", "yellow_terracotta"});
        Look k = look(wall, r(ROOF));
        info.style = "tvåplanshus";
        info.floors = 2;
        for (int s = 0; s < 2; s++) {
            storey(f, 0, 0, w - 1, d - 1, s * 4, wall, "pale_oak_planks", "spruce_planks");
            for (int side = 0; side < 4; side++) windows(f, 0, 0, w - 1, d - 1, s * 4, side, Win.TRAD, "pale_oak", 3, s == 0 && side == 0 ? w / 2 : -99);
        }
        door(f, w / 2, 0, d - 1, k.door(), info);
        canopy(f, w / 2, 0, d - 1, "pale_oak");
        int top = gable(f, -1, -1, w, d, 8, true, k.roof(), k.roofBlock(), wall);
        boolean left = R.nextBoolean();
        if (left) garage(f, -7, 1, -1, d - 1, 0, wall, k.roof(), true, info);
        else garage(f, w, 1, w + 6, d - 1, 0, wall, k.roof(), true, info);
        if (R.nextBoolean()) balcony(f, 1, 4, d - 1, 4, "pale_oak_planks", "pale_oak_fence");
        chimney(f, w - 2, d / 2, 1, top + 1, "stone_bricks");
        interior(f, 0, 0, w - 1, d - 1, 0);
        interior(f, 0, 0, w - 1, d - 1, 4);
    }

    /** Modern villa: dark timber and white render volumes, flat roofs at two heights, floor-to-ceiling glass. */
    static void modern(B.Frame f, int w, int d, Info info, boolean luxury) {
        String dark = r(new String[] {"dark_oak_planks", "stripped_dark_oak_wood", "black_terracotta", "polished_deepslate"});
        String light = r(new String[] {"white_concrete", "smooth_quartz", "calcite"});
        info.style = luxury ? "lyxvilla" : "modern villa";
        info.floors = 2;
        // ground volume: full footprint, white; upper volume: shifted and cantilevered, dark
        storey(f, 0, 0, w - 1, d - 1, 0, light, null, "polished_andesite");
        windows(f, 0, 0, w - 1, d - 1, 0, 0, Win.GLASS, "pale_oak", 3, 2);
        windows(f, 0, 0, w - 1, d - 1, 0, 1, Win.GLASS, "pale_oak", 3);
        windows(f, 0, 0, w - 1, d - 1, 0, 3, Win.RIBBON, "pale_oak", 3);
        door(f, 2, 0, d - 1, "dark_oak", info);
        flat(f, -1, -1, w, d, 4, "smooth_stone", null);
        int ux0 = w / 3, ux1 = w + 2, uz0 = 1, uz1 = d - 2;
        storey(f, ux0, uz0, ux1, uz1, 4, dark, null, "spruce_planks");
        windows(f, ux0, uz0, ux1, uz1, 4, 0, Win.GLASS, "pale_oak", 3);
        windows(f, ux0, uz0, ux1, uz1, 4, 3, Win.GLASS, "pale_oak", 3);
        windows(f, ux0, uz0, ux1, uz1, 4, 1, Win.RIBBON, "pale_oak", 3);
        flat(f, ux0 - 1, uz0 - 1, ux1 + 1, uz1 + 1, 8, "smooth_stone", "smooth_stone_slab[type=bottom]");
        for (int z = uz0; z <= uz1; z += Math.max(1, uz1 - uz0)) f.fill(ux1, 0, z, ux1, 3, z, "polished_deepslate");
        // roof terrace on the low volume with glass railing
        for (int x = 0; x < ux0 - 1; x++) f.set(x, 5, d - 1, "glass_pane");
        carport(f, -6, 1, -2, 7, 0);
        deck(f, 1, -6, w - 2, -2, 0, false);
        if (luxury) {
            pool(f, 2, -12, w - 4, -8, 0);
            for (int x = 0; x < w; x += 3) f.set(x, 1, -14, "lantern");
            info.cover(-6, -14, ux1 + 1, d);
        } else info.cover(-6, -6, ux1 + 1, d);
        interior(f, 0, 0, w - 1, d - 1, 0);
        interior(f, ux0, uz0, ux1, uz1, 4);
    }

    /** Corner villa: square two-storey house under a hip roof with a bay window and a wrap-around porch. */
    static void corner(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"red_terracotta", "white_concrete", "light_blue_terracotta", "yellow_terracotta"});
        Look k = look(wall, r(new String[] {"deepslate_tile", "brick", "polished_blackstone_brick"}));
        info.style = "hörnvilla";
        info.floors = 2;
        for (int s = 0; s < 2; s++) {
            storey(f, 0, 0, w - 1, d - 1, s * 4, wall, "pale_oak_planks", "spruce_planks");
            for (int side = 0; side < 4; side++) windows(f, 0, 0, w - 1, d - 1, s * 4, side, Win.TRAD, "pale_oak", 3, s == 0 && side == 0 ? w / 2 : -99);
        }
        door(f, w / 2, 0, d - 1, k.door(), info);
        veranda(f, w / 2 - 2, w - 1, d - 1, 0, "pale_oak", k.roof(), false);
        bay(f, 1, 4, d - 1, 4, wall, k.roof());
        int top = hip(f, -1, -1, w, d, 8, k.roof(), k.roofBlock());
        chimney(f, 2, 2, 1, top + 1, "bricks");
        interior(f, 0, 0, w - 1, d - 1, 0);
        interior(f, 0, 0, w - 1, d - 1, 4);
        info.cover(-1, -1, w, d + 3);
    }

    /** 1960s single-storey brick villa: long and low, low-pitched roof, carport at the end. */
    static void sixties(B.Frame f, int w, int d, Info info) {
        String wall = r(new String[] {"bricks", "bricks", "resin_bricks", "mud_bricks", "white_terracotta"});
        info.style = "60-talsvilla";
        storey(f, 0, 0, w - 1, d - 1, 0, wall, null, "spruce_planks");
        windows(f, 0, 0, w - 1, d - 1, 0, 0, Win.RIBBON, "pale_oak", 3, w / 3);
        windows(f, 0, 0, w - 1, d - 1, 0, 1, Win.GLASS, "pale_oak", 3, 1, w - 2);
        windows(f, 0, 0, w - 1, d - 1, 0, 2, Win.RIBBON, "pale_oak", 3);
        windows(f, 0, 0, w - 1, d - 1, 0, 3, Win.RIBBON, "pale_oak", 3);
        door(f, w / 3, 0, d - 1, "spruce", info);
        for (int x = w / 3 - 1; x <= w / 3 + 1; x++) f.set(x, 4, d, "dark_oak_slab[type=bottom]");
        lowGable(f, -1, -1, w, d, 4, true, r(new String[] {"dark_oak", "deepslate_tile", "brick"}));
        carport(f, w + 1, 1, w + 5, d - 2, 0);
        interior(f, 0, 0, w - 1, d - 1, 0);
        info.cover(-1, -1, w + 5, d);
    }

    static String r(String[] a) {
        return a[R.nextInt(a.length)];
    }

    /** Footprint sizes per family (width, depth) including a little random growth. */
    static int[] size(Family fam, Random r) {
        int[] s = switch (fam) {
            case COTTAGE -> new int[] {8, 7};
            case TURN_OF_CENTURY -> new int[] {11, 10};
            case FUNKIS -> new int[] {10, 9};
            case MANSARD -> new int[] {11, 9};
            case SPLIT_LEVEL -> new int[] {13, 9};
            case TWO_STOREY -> new int[] {10, 8};
            case MODERN -> new int[] {12, 9};
            case LUXURY -> new int[] {15, 11};
            case CORNER -> new int[] {10, 10};
            case LONGHOUSE_60S -> new int[] {14, 8};
        };
        return new int[] {s[0] + r.nextInt(2), s[1] + r.nextInt(2)};
    }

    static List<Family> familiesFor(String tier) {
        List<Family> l = new ArrayList<>();
        switch (tier) {
            case "SMALL" -> l.addAll(List.of(Family.COTTAGE, Family.LONGHOUSE_60S, Family.MANSARD));
            case "MEDIUM" -> l.addAll(List.of(Family.TWO_STOREY, Family.MANSARD, Family.FUNKIS, Family.SPLIT_LEVEL, Family.LONGHOUSE_60S));
            case "LARGE" -> l.addAll(List.of(Family.TURN_OF_CENTURY, Family.MODERN, Family.CORNER, Family.FUNKIS, Family.TWO_STOREY));
            default -> l.addAll(List.of(Family.LUXURY, Family.TURN_OF_CENTURY, Family.MODERN));
        }
        return l;
    }
}
