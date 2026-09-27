import java.util.List;

/**
 * Detailed Nordic buildings in a local frame (see {@link B.Frame}): front = local +z, footprint x 0..w-1, z 0..d-1,
 * local y 0 = ground-floor floor. Storeys are 5 high (floor row + 4 wall rows).
 */
final class Build {

    record Style(String post, String beam, String infill, String upper, String roof, String roofBlock, String trim,
                 String shutter, String door, String gable) {}

    static final Style TARRED = new Style("dark_oak_log", "stripped_dark_oak_log", "spruce_planks", "dark_oak_planks",
            "dark_oak", "dark_oak_planks", "spruce", "dark_oak", "spruce", "dark_oak_planks");
    static final Style FALU = new Style("dark_oak_log", "stripped_spruce_log", "mangrove_planks", "mangrove_planks",
            "dark_oak", "dark_oak_planks", "pale_oak", "pale_oak", "dark_oak", "mangrove_planks");
    static final Style WHITEWASH = new Style("dark_oak_log", "stripped_dark_oak_log", "calcite", "calcite",
            "deepslate_tile", "deepslate_tiles", "spruce", "spruce", "dark_oak", "dark_oak_planks");
    static final Style OCHRE = new Style("spruce_log", "stripped_spruce_log", "yellow_terracotta", "yellow_terracotta",
            "spruce", "spruce_planks", "dark_oak", "dark_oak", "spruce", "spruce_planks");
    static final Style CLAY = new Style("spruce_log", "stripped_spruce_log", "packed_mud", "spruce_planks",
            "mud_brick", "mud_bricks", "spruce", "spruce", "spruce", "spruce_planks");
    static final Style PALE = new Style("spruce_log", "stripped_spruce_log", "pale_oak_planks", "pale_oak_planks",
            "deepslate_tile", "deepslate_tiles", "dark_oak", "dark_oak", "dark_oak", "pale_oak_planks");
    static final List<Style> TOWN_STYLES = List.of(TARRED, FALU, FALU, WHITEWASH, OCHRE, CLAY, PALE, TARRED);

    static String stone() {
        double r = Canvas.rnd();
        if (r < 0.35) return "cobblestone";
        if (r < 0.55) return "stone_bricks";
        if (r < 0.70) return "mossy_cobblestone";
        if (r < 0.80) return "andesite";
        if (r < 0.90) return "mossy_stone_bricks";
        return "cracked_stone_bricks";
    }

    static String log(String mat, char axis) {
        return mat + "[axis=" + axis + "]";
    }

    // ------------------------------------------------------------------ house

    record Opts(boolean gableFront, boolean jetty, boolean balcony, boolean dormers, boolean lean, boolean chimney,
                boolean stoneGround) {
        static Opts random(int w, int d, int floors) {
            return new Opts(Canvas.rnd() < 0.4 || w <= 7, floors > 1 && Canvas.rnd() < 0.7, floors > 1 && Canvas.rnd() < 0.5,
                    w >= 9 && Canvas.rnd() < 0.65, Canvas.rnd() < 0.45, Canvas.rnd() < 0.85, Canvas.rnd() < 0.35);
        }
    }

    /** Where the house stands: local y 0 relative to the world. Street side = front. */
    static int floorLevel(B.Frame probe, int w, int d) {
        int front = probe.maxGround(0, d, w - 1, d + 1);
        int max = probe.maxGround(0, 0, w - 1, d - 1);
        return Math.max(front + 1, max - 1);
    }

    static void house(B.Frame f, int w, int d, int floors, Style st, Opts o) {
        int sh = 5, top = floors * sh;
        f.use(-1, -1, w, d);
        // ground floor base and foundation down to the terrain
        f.foundation(-1, -1, w, d, 0, "cobblestone");
        for (int x = -1; x <= w; x++)
            for (int z = -1; z <= d; z++) {
                boolean edge = x == -1 || x == w || z == -1 || z == d;
                if (edge) {
                    for (int y = -5; y <= -1; y++) if (!f.get(x, y, z).equals("air") || y == -1) f.set(x, y, z, stone());
                    if (f.isAir(x, 0, z)) f.set(x, 0, z, Canvas.rnd() < 0.3 ? "coarse_dirt" : "air");
                }
            }
        for (int k = 0; k < floors; k++) storey(f, w, d, k, floors, st, o);
        int jt = jetty(o, floors - 1);
        int[] ridge = roof(f, -1, -1 - jt, w, d + jt, top, !o.gableFront(), st);
        if (o.dormers() && !o.gableFront()) dormers(f, w, d + jt, top, st);
        if (o.balcony()) balcony(f, w, d, st, o);
        if (o.lean()) leanTo(f, w, d, st);
        if (o.chimney()) chimney(f, o.gableFront() ? w / 2 + 1 : 2, o.gableFront() ? 2 : d / 2, ridge[0] + 2);
        door(f, w, d, st);
    }

    static int jetty(Opts o, int k) {
        return o.jetty() && k >= 1 ? 1 : 0;
    }

    static void storey(B.Frame f, int w, int d, int k, int floors, Style st, Opts o) {
        int yb = k * 5, j = jetty(o, k), jPrev = k > 0 ? jetty(o, k - 1) : 0;
        int z0 = -j, z1 = d - 1 + j;
        boolean stoneWalls = k == 0 && o.stoneGround();
        String infill = k == 0 ? st.infill() : st.upper();
        // floor row
        for (int x = 0; x < w; x++)
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == 0 || x == w - 1 || z == z0 || z == z1;
                if (k == 0) f.set(x, 0, z, edge ? stone() : "spruce_planks");
                else if (edge) f.set(x, yb, z, (z == z0 || z == z1) ? log(st.beam(), 'x') : log(st.beam(), 'z'));
                else f.set(x, yb, z, "spruce_planks");
            }
        if (k == 0) for (int x = 1; x < w - 1; x++) for (int z = 1; z < d - 1; z++) f.set(x, 0, z, "spruce_planks");
        // jetty brackets under the overhang
        if (j > jPrev)
            for (int x = 0; x < w; x++)
                if (x % 3 == 0 || x == w - 1) {
                    f.set(x, yb - 1, z0, B.stairsTop(st.trim(), B.Dir.SOUTH));
                    f.set(x, yb - 1, z1, B.stairsTop(st.trim(), B.Dir.NORTH));
                }
        // walls
        for (int y = yb + 1; y <= yb + 4; y++)
            for (int x = 0; x < w; x++)
                for (int z = z0; z <= z1; z++) {
                    boolean xEdge = x == 0 || x == w - 1, zEdge = z == z0 || z == z1;
                    if (!xEdge && !zEdge) {
                        f.set(x, y, z, "air");
                        continue;
                    }
                    boolean post = (xEdge && zEdge) || (zEdge && x % 3 == 0) || (xEdge && Math.floorMod(z - z0, 3) == 0);
                    String s;
                    if (stoneWalls) s = post ? "stone_bricks" : stone();
                    else if (post) s = log(st.post(), 'y');
                    else if (y == yb + 4 && !stoneWalls) s = zEdge ? log(st.beam(), 'x') : log(st.beam(), 'z');
                    else s = infill;
                    f.set(x, y, z, s);
                }
        // windows: 2-wide in the bays of the long walls, 1-wide with shutters on the short walls
        for (int x = 1; x + 1 < w - 1; x += 3) {
            for (int z : new int[] {z0, z1}) {
                boolean front = z == z1;
                if (k == 0 && front && Math.abs(x + 0.5 - (w - 1) / 2.0) < 1.6) continue; // door bay
                B.Dir out = front ? B.Dir.SOUTH : B.Dir.NORTH;
                int oz = front ? z + 1 : z - 1;
                for (int xx = x; xx <= x + 1; xx++) {
                    f.set(xx, yb + 2, z, "glass_pane");
                    f.set(xx, yb + 3, z, "glass_pane");
                    f.setIfAir(xx, yb + 1, oz, B.trapdoor(st.trim(), out, false, true));
                    if (k == 0 && Canvas.rnd() < 0.3) f.setIfAir(xx, yb + 1, oz, "flowering_azalea_leaves[persistent=true]");
                }
            }
        }
        for (int x : new int[] {0, w - 1}) {
            B.Dir out = x == 0 ? B.Dir.WEST : B.Dir.EAST;
            int ox = x == 0 ? -1 : w;
            for (int z = z0 + 1; z < z1; z += 3) {
                if (Math.floorMod(z - z0, 3) == 0) continue;
                f.set(x, yb + 2, z, "glass_pane");
                f.set(x, yb + 3, z, "glass_pane");
                f.setIfAir(ox, yb + 2, z - 1, shutter(st.shutter(), out));
                f.setIfAir(ox, yb + 3, z - 1, shutter(st.shutter(), out));
                f.setIfAir(ox, yb + 2, z + 1, shutter(st.shutter(), out));
                f.setIfAir(ox, yb + 3, z + 1, shutter(st.shutter(), out));
                f.setIfAir(ox, yb + 1, z, B.trapdoor(st.trim(), out, false, true));
            }
        }
        // interior life: lanterns, furniture, so the windows glow at night
        f.set(1, yb + 1, z0 + 1, "lantern");
        f.set(w - 2, yb + 1, z1 - 1, "lantern");
        f.set(w - 2, yb + 1, z0 + 1, Canvas.pick("barrel[facing=up]", "bookshelf", "crafting_table", "chest[facing=south]", "spruce_shelf[facing=south]"));
        f.set(1, yb + 1, z1 - 1, Canvas.pick("barrel[facing=up]", "bookshelf", "loom", "composter", "smoker[facing=north]"));
        if (w > 6) f.set(w / 2, yb + 4, (z0 + z1) / 2, "lantern[hanging=true]");
    }

    static String shutter(String mat, B.Dir out) {
        return mat + "_trapdoor[facing=" + out.n() + ",open=true,half=bottom]";
    }

    static void door(B.Frame f, int w, int d, Style st) {
        int x = (w - 1) / 2;
        f.set(x, 1, d - 1, st.door() + "_door[facing=south,half=lower,hinge=left]");
        f.set(x, 2, d - 1, st.door() + "_door[facing=south,half=upper,hinge=left]");
        f.set(x, 3, d - 1, log(st.beam(), 'x'));
        // hood and lantern
        for (int xx = x - 1; xx <= x + 1; xx++) f.set(xx, 4, d, B.slab(st.trim()));
        f.set(x - 1, 3, d, B.stairsTop(st.trim(), B.Dir.EAST));
        f.set(x + 1, 3, d, B.stairsTop(st.trim(), B.Dir.WEST));
        f.set(x + 1, 2, d, "lantern[hanging=true]");
        // steps down to the street
        int g = Canvas.ground(f.wx(x, d), f.wz(x, d)) - f.oy();
        f.set(x, 0, d, B.stairs("stone_brick", B.Dir.NORTH));
        for (int k = 1; k <= 6 && g < -k; k++) {
            f.set(x, -k, d + k, B.stairs("stone_brick", B.Dir.NORTH));
            f.fill(x, -k - 3, d + k, x, -k - 1, d + k, "cobblestone");
            f.set(x - 1, -k + 1, d + k, "stone_brick_wall");
            f.set(x + 1, -k + 1, d + k, "stone_brick_wall");
        }
    }

    /**
     * Gable roof with a one-block overhang over the rectangle (x0..x1, z0..z1) starting at y. Returns {ridgeY, ridgeAcross}.
     * ridgeX: ridge runs along x (slopes face front/back); otherwise the gable faces the front.
     */
    static int[] roof(B.Frame f, int x0, int z0, int x1, int z1, int y, boolean ridgeX, Style st) {
        int a0 = ridgeX ? z0 : x0, a1 = ridgeX ? z1 : x1, b0 = ridgeX ? x0 : z0, b1 = ridgeX ? x1 : z1;
        B.Dir up0 = ridgeX ? B.Dir.SOUTH : B.Dir.EAST, up1 = ridgeX ? B.Dir.NORTH : B.Dir.WEST;
        int lo = a0, hi = a1, yy = y, ridgeY = y;
        while (lo <= hi) {
            for (int b = b0; b <= b1; b++) {
                if (lo == hi) setAB(f, ridgeX, lo, yy, b, st.roofBlock());
                else {
                    setAB(f, ridgeX, lo, yy, b, B.stairs(st.roof(), up0));
                    setAB(f, ridgeX, hi, yy, b, B.stairs(st.roof(), up1));
                    // underside, so the roof reads thick from below
                    if (lo > a0 && yy - 1 > y) {
                        if (isAirAB(f, ridgeX, lo, yy - 1, b) && (b == b0 || b == b1)) setAB(f, ridgeX, lo, yy - 1, b, B.stairsTop(st.roof(), up1));
                        if (isAirAB(f, ridgeX, hi, yy - 1, b) && (b == b0 || b == b1)) setAB(f, ridgeX, hi, yy - 1, b, B.stairsTop(st.roof(), up0));
                    }
                }
            }
            ridgeY = yy;
            lo++;
            hi--;
            yy++;
        }
        // gable walls with a beam and a small window, and horn finials at the ridge ends
        for (int a = a0 + 1; a <= a1 - 1; a++) {
            int roofY = y + Math.min(a - a0, a1 - a);
            for (int k = y; k < roofY; k++)
                for (int b : new int[] {b0 + 1, b1 - 1}) {
                    String s = k == y ? log(st.beam(), ridgeX ? 'z' : 'x') : st.gable();
                    setAB(f, ridgeX, a, k, b, s);
                }
        }
        int mid = (a0 + a1) / 2;
        if (ridgeY - y >= 4)
            for (int b : new int[] {b0 + 1, b1 - 1}) {
                setAB(f, ridgeX, mid, y + 2, b, "glass_pane");
                setAB(f, ridgeX, mid, y + 3, b, "glass_pane");
            }
        B.Dir outB0 = ridgeX ? B.Dir.WEST : B.Dir.NORTH, outB1 = ridgeX ? B.Dir.EAST : B.Dir.SOUTH;
        if ((a1 - a0) % 2 == 0) {
            setAB(f, ridgeX, mid, ridgeY + 1, b0, B.stairs(st.roof(), outB0));
            setAB(f, ridgeX, mid, ridgeY + 1, b1, B.stairs(st.roof(), outB1));
        }
        return new int[] {ridgeY, mid};
    }

    static void setAB(B.Frame f, boolean ridgeX, int a, int y, int b, String s) {
        if (ridgeX) f.set(b, y, a, s);
        else f.set(a, y, b, s);
    }

    static boolean isAirAB(B.Frame f, boolean ridgeX, int a, int y, int b) {
        return ridgeX ? f.isAir(b, y, a) : f.isAir(a, y, b);
    }

    /** Dormers on the front slope of an x-ridge roof. */
    static void dormers(B.Frame f, int w, int dFront, int top, Style st) {
        int n = w >= 13 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            int c = n == 1 ? w / 2 : (i == 0 ? w / 3 : w - 1 - w / 3);
            int zf = dFront - 1; // wall plane of the top storey front
            for (int z = zf - 4; z <= zf; z++) {
                for (int y = top + 1; y <= top + 3; y++) {
                    f.set(c - 1, y, z, z == zf ? log(st.post(), 'y') : st.gable());
                    f.set(c + 1, y, z, z == zf ? log(st.post(), 'y') : st.gable());
                    f.set(c, y, z, z == zf ? (y <= top + 2 ? "glass_pane" : log(st.beam(), 'x')) : "air");
                }
            }
            for (int z = zf - 4; z <= zf + 1; z++) {
                f.set(c - 2, top + 3, z, B.stairs(st.roof(), B.Dir.EAST));
                f.set(c + 2, top + 3, z, B.stairs(st.roof(), B.Dir.WEST));
                f.set(c - 1, top + 4, z, B.stairs(st.roof(), B.Dir.EAST));
                f.set(c + 1, top + 4, z, B.stairs(st.roof(), B.Dir.WEST));
                f.set(c, top + 5, z, B.slab(st.roof().equals("deepslate_tile") ? "deepslate_tile" : st.roof()));
                f.set(c, top + 4, z, st.roofBlock());
            }
            f.set(c, top + 1, zf + 1, B.trapdoor(st.trim(), B.Dir.SOUTH, false, true));
        }
    }

    static void balcony(B.Frame f, int w, int d, Style st, Opts o) {
        int j = jetty(o, 1), z = d - 1 + j, x = (w - 1) / 2;
        f.set(x, 6, z, st.door() + "_door[facing=south,half=lower,hinge=left]");
        f.set(x, 7, z, st.door() + "_door[facing=south,half=upper,hinge=left]");
        for (int xx = x - 2; xx <= x + 2; xx++) {
            f.set(xx, 5, z + 1, B.slab(st.trim()).replace("bottom", "top"));
            f.setIfAir(xx, 6, z + 2, st.trim() + "_fence");
            if (xx == x - 2 || xx == x + 2) {
                f.setIfAir(xx, 6, z + 1, st.trim() + "_fence");
                f.set(xx, 4, z + 1, B.stairsTop(st.trim(), B.Dir.NORTH));
            }
        }
        for (int xx = x - 2; xx <= x + 2; xx++) f.set(xx, 5, z + 2, B.slab(st.trim()).replace("bottom", "top"));
        for (int xx = x - 2; xx <= x + 2; xx++) f.set(xx, 6, z + 2, st.trim() + "_fence");
        f.set(x - 2, 7, z + 2, "lantern");
        if (Canvas.rnd() < 0.6) f.set(x + 1, 6, z + 1, "potted_" + Canvas.pick("red_tulip", "poppy", "cornflower", "fern", "azure_bluet"));
    }

    static boolean sideFree(B.Frame f, int xFrom, int xTo, int d) {
        for (int x = Math.min(xFrom, xTo); x <= Math.max(xFrom, xTo); x++)
            for (int z = 1; z <= d - 2; z++) if (Canvas.used(f.wx(x, z), f.wz(x, z))) return false;
        return true;
    }

    static void leanTo(B.Frame f, int w, int d, Style st) {
        int side = Canvas.rnd() < 0.5 ? -1 : 1;
        if (!sideFree(f, side < 0 ? -2 : w + 1, side < 0 ? -3 : w + 2, d)) side = -side;
        if (!sideFree(f, side < 0 ? -2 : w + 1, side < 0 ? -3 : w + 2, d)) return;
        int xw = side < 0 ? -1 : w, dir = side;
        f.use(Math.min(xw, xw + 2 * dir), 1, Math.max(xw, xw + 2 * dir), d - 2);
        for (int z = 1; z <= d - 2; z++) {
            for (int k = 0; k < 3; k++) {
                int x = xw + dir * k;
                f.set(x, 4 - k, z, B.stairs(st.roof(), side < 0 ? B.Dir.EAST : B.Dir.WEST));
                if (f.isAir(x, 0, z) || f.get(x, 0, z).equals("coarse_dirt")) f.set(x, 0, z, "coarse_dirt");
            }
            int xs = xw + dir * 2;
            if (z == 1 || z == d - 2) f.fill(xs, 1, z, xs, 1, z, st.trim() + "_fence");
            // woodpile against the wall
            if (z > 1 && z < d - 2) {
                f.set(xw, 1, z, log("stripped_spruce_log", 'z'));
                f.set(xw, 2, z, log(Canvas.rnd() < 0.5 ? "spruce_log" : "birch_log", 'z'));
                if (Canvas.rnd() < 0.5) f.set(xw + dir, 1, z, Canvas.pick("barrel[facing=up]", "hay_block", "composter"));
            }
        }
        f.foundation(Math.min(xw, xw + dir * 2), 1, Math.max(xw, xw + dir * 2), d - 2, 0, "cobblestone");
        for (int z = 1; z <= d - 2; z++)
            for (int k = 0; k < 3; k++) f.set(xw + dir * k, 0, z, Canvas.rnd() < 0.5 ? "coarse_dirt" : "spruce_planks");
    }

    static void chimney(B.Frame f, int x, int z, int yTop) {
        for (int y = 1; y <= yTop; y++) f.set(x, y, z, y > yTop - 2 ? "stone_bricks" : Canvas.pick("cobblestone", "stone_bricks", "mossy_cobblestone"));
        f.set(x, yTop + 1, z, "campfire[lit=true,signal_fire=false]");
    }

    // ------------------------------------------------------------------ Viking longhouse

    /** A long, low house with a curved (boat-shaped) turf or shingle roof. Length along x, door on the front long side. */
    static void longhouse(B.Frame f, int len, int wid, boolean turf) {
        f.use(-3, -3, len + 2, wid + 2);
        f.foundation(-1, -1, len, wid, 0, "cobblestone");
        for (int x = -1; x <= len; x++)
            for (int z = -1; z <= wid; z++) f.set(x, 0, z, (x == -1 || x == len || z == -1 || z == wid) ? stone() : "spruce_planks");
        int mid = (len - 1) / 2;
        for (int x = 0; x < len; x++) {
            double t = Math.abs(x - mid) / (len / 2.0);
            int sag = (int) Math.round(2.2 * t * t);
            int wallTop = 3 + sag;
            for (int z = 0; z < wid; z++) {
                boolean xe = x == 0 || x == len - 1, ze = z == 0 || z == wid - 1;
                for (int y = 1; y <= wallTop; y++) {
                    if (!xe && !ze) {
                        f.set(x, y, z, "air");
                        continue;
                    }
                    String s;
                    if (xe && ze) s = log("dark_oak_log", 'y');
                    else if (ze) s = log(x % 2 == 0 ? "spruce_log" : "stripped_spruce_log", 'y');
                    else s = "dark_oak_planks";
                    f.set(x, y, z, s);
                }
            }
            // roof cross-section: from the eaves up to the ridge, one step per row
            int lo = -1, hi = wid, y = wallTop;
            while (lo <= hi) {
                String sL, sR;
                if (lo == hi) sL = sR = turf ? "moss_block" : "dark_oak_planks";
                else if (turf && lo > -1) {
                    sL = Canvas.rnd() < 0.7 ? "moss_block" : "grass_block";
                    sR = Canvas.rnd() < 0.7 ? "moss_block" : "grass_block";
                } else {
                    sL = B.stairs(turf ? "spruce" : "dark_oak", B.Dir.SOUTH);
                    sR = B.stairs(turf ? "spruce" : "dark_oak", B.Dir.NORTH);
                }
                f.set(x, y, lo, sL);
                f.set(x, y, hi, sR);
                if (turf && lo > -1 && Canvas.rnd() < 0.35) f.setIfAir(x, y + 1, lo, Canvas.pick("short_grass", "short_grass", "fern", "poppy", "dandelion"));
                if (turf && hi < wid && Canvas.rnd() < 0.35) f.setIfAir(x, y + 1, hi, Canvas.pick("short_grass", "short_grass", "fern", "cornflower"));
                lo++;
                hi--;
                y++;
            }
        }
        // gable ends: planks up to the roof, crossed horn boards at the apex
        for (int x : new int[] {-1, len}) {
            int end = x < 0 ? 0 : len - 1;
            int wallTop = 3 + (int) Math.round(2.2 * Math.pow(Math.abs(end - mid) / (len / 2.0), 2));
            int apex = wallTop + (wid + 1) / 2;
            for (int z = 0; z < wid; z++) {
                int roofY = wallTop + Math.min(z + 1, wid - z);
                for (int y = wallTop + 1; y < roofY; y++) f.set(end, y, z, "dark_oak_planks");
            }
            B.Dir out = x < 0 ? B.Dir.WEST : B.Dir.EAST;
            int zc = (wid - 1) / 2;
            f.set(end, apex, zc, "dark_oak_planks");
            f.set(end + out.dx, apex + 1, zc - 1, B.stairs("dark_oak", out));
            f.set(end + out.dx, apex + 1, zc + 1, B.stairs("dark_oak", out));
            f.set(end + out.dx, apex + 2, zc - 1, "dark_oak_fence");
            f.set(end + out.dx, apex + 2, zc + 1, "dark_oak_fence");
            // gable-end door with a porch
            if (x < 0) {
                f.set(end, 1, zc, "spruce_door[facing=west,half=lower,hinge=left]");
                f.set(end, 2, zc, "spruce_door[facing=west,half=upper,hinge=left]");
                for (int z = zc - 1; z <= zc + 1; z++) {
                    f.set(end - 2, 0, z, "spruce_planks");
                    f.set(end - 1, 0, z, "spruce_planks");
                }
                f.set(end - 2, 1, zc - 1, "spruce_fence");
                f.set(end - 2, 2, zc - 1, "spruce_fence");
                f.set(end - 2, 1, zc + 1, "spruce_fence");
                f.set(end - 2, 2, zc + 1, "spruce_fence");
                for (int k = -1; k <= 0; k++) {
                    f.set(end - 1 + k, 3, zc - 1, B.stairs("spruce", B.Dir.SOUTH));
                    f.set(end - 1 + k, 3, zc + 1, B.stairs("spruce", B.Dir.NORTH));
                    f.set(end - 1 + k, 4, zc, B.slab("spruce"));
                }
                f.set(end - 1, 2, zc - 1, "lantern[hanging=true]".replace("hanging=true", "hanging=false"));
            }
        }
        // front door on the long side, banners, smoke hole with fire inside
        int dx = len / 3;
        f.set(dx, 1, wid - 1, "spruce_door[facing=south,half=lower,hinge=right]");
        f.set(dx, 2, wid - 1, "spruce_door[facing=south,half=upper,hinge=right]");
        f.set(dx, 0, wid, B.stairs("stone_brick", B.Dir.NORTH));
        f.set(dx - 1, 3, wid, "red_wall_banner[facing=south]");
        f.set(dx + 1, 3, wid, "red_wall_banner[facing=south]");
        for (int x = 2; x < len - 2; x += 5) f.set(x, 1, (wid - 1) / 2, "campfire[lit=true,signal_fire=false]");
        for (int x = 1; x < len - 1; x += 4) {
            f.set(x, 1, 1, Canvas.pick("barrel[facing=up]", "chest[facing=south]", "hay_block"));
            f.set(x, 1, wid - 2, "lantern");
        }
        for (int x = 3; x < len - 3; x += 6) {
            f.set(x, 2, 0, "glass_pane");
            f.set(x, 2, wid - 1, "glass_pane");
        }
    }

    // ------------------------------------------------------------------ small things

    static void lamp(int u, int v) {
        int g = Canvas.ground(u, v);
        if (!Canvas.isAir(u, g + 1, v) || !Canvas.isAir(u, g + 4, v)) return;
        Canvas.set(u, g + 1, v, "cobblestone_wall");
        Canvas.set(u, g + 2, v, "dark_oak_fence");
        Canvas.set(u, g + 3, v, "dark_oak_fence");
        Canvas.set(u, g + 4, v, "lantern");
    }

    /** A lamp with an arm: post plus a hanging lantern over the street. */
    static void hangingLamp(int u, int v, B.Dir arm) {
        int g = Canvas.ground(u, v);
        if (!Canvas.isAir(u, g + 1, v) || !Canvas.isAir(u + arm.dx, g + 4, v + arm.dz)) return;
        Canvas.set(u, g + 1, v, "stone_brick_wall");
        for (int y = g + 2; y <= g + 4; y++) Canvas.set(u, y, v, "spruce_fence");
        Canvas.set(u, g + 5, v, B.stairs("spruce", arm.opposite()));
        Canvas.set(u + arm.dx, g + 5, v + arm.dz, "spruce_fence");
        Canvas.set(u + arm.dx, g + 4, v + arm.dz, "lantern[hanging=true]");
    }

    static void brazier(int u, int v) {
        int g = Canvas.ground(u, v);
        Canvas.set(u, g + 1, v, "stone_brick_wall");
        Canvas.set(u, g + 2, v, "polished_andesite");
        Canvas.set(u, g + 3, v, "campfire[lit=true,signal_fire=false]");
    }

    static void crates(int u, int v) {
        int g = Canvas.ground(u, v);
        if (!Canvas.isAir(u, g + 1, v)) return;
        Canvas.set(u, g + 1, v, Canvas.pick("barrel[facing=up]", "spruce_planks", "hay_block", "chest[facing=north]", "barrel[facing=north]"));
        if (Canvas.rnd() < 0.45) Canvas.setIfAir(u, g + 2, v, Canvas.pick("barrel[facing=up]", "hay_block", "spruce_slab[type=bottom]"));
    }

    static void bench(int u, int v, B.Dir facing) {
        int g = Canvas.ground(u, v);
        B.Dir along = facing.cw(1);
        for (int k = 0; k < 2; k++) {
            int x = u + along.dx * k, z = v + along.dz * k;
            if (Canvas.isAir(x, g + 1, z)) Canvas.set(x, g + 1, z, B.stairs("spruce", facing.opposite()));
        }
    }
}
