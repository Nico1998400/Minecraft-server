/** Town layout: streets, stairways, parapets and the placement of every structure. */
final class Town {

    /** Spawn point (relative): on the deck of the arrival ship. */
    static int[] SPAWN = {-9, 1, -9};

    static void build() {
        streets();
        stairways();
        parapets();
        harbour();
        landmarks();
        market();
        districts();
        houses();
        defences();
        lamps();
        Nature.grow();
    }

    // ------------------------------------------------------------------ streets

    static String cobble() {
        double r = Canvas.rnd();
        if (r < 0.30) return "stone_bricks";
        if (r < 0.45) return "cobblestone";
        if (r < 0.58) return "andesite";
        if (r < 0.68) return "polished_andesite";
        if (r < 0.78) return "cracked_stone_bricks";
        if (r < 0.86) return "mossy_stone_bricks";
        if (r < 0.93) return "tuff";
        return "gravel";
    }

    static boolean river(int u, int v) {
        return Canvas.inXZ(u, v) && Terrain.RIVER[u - Canvas.MINX][v - Canvas.MINZ];
    }

    static void pave(int u, int v, String state) {
        int g = Terrain.h(u, v);
        if (g < Canvas.SEA || Terrain.inBasin(u, v)) return;
        if (river(u, v)) {
            // bridge deck at street level over the channel, with an arched underside
            int i = u - Canvas.MINX, j = v - Canvas.MINZ;
            int deck = Math.max(River.BEFORE[i][j], River.LEVEL[i][j] + 2);
            Canvas.set(u, deck, v, "stone_bricks");
            Canvas.set(u, deck - 1, v, "stone_brick_slab[type=top]");
            Canvas.setGround(u, v, deck);
        } else Canvas.set(u, g, v, state);
        Canvas.STREET[u - Canvas.MINX][v - Canvas.MINZ] = true;
        Canvas.use(u, v, u, v);
    }

    static void streets() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                boolean paved = Terrain.isStreet(u, v) && Terrain.w(u, v) > 0.95;
                if (Terrain.arm(u, v) && Terrain.h(u, v) >= 1) paved = true;
                if (Math.abs(u) <= Terrain.QUAY_HALF && v >= Terrain.QUAY_V && v <= Terrain.QUAY_V + 13 && Terrain.h(u, v) <= 1) paved = true;
                if (paved) pave(u, v, cobble());
            }
    }

    static void stairways() {
        for (Terrain.Corridor c : Terrain.CORRIDORS)
            for (int u = c.u0(); u <= c.u1(); u++)
                for (int v = c.vFrom(); v <= c.vTo(); v++) {
                    int g = Terrain.h(u, v);
                    Canvas.STREET[u - Canvas.MINX][v - Canvas.MINZ] = true;
                    Canvas.use(u, v, u, v);
                    boolean edge = u == c.u0() || u == c.u1();
                    if (river(u, v)) {
                        Canvas.set(u, g, v, "stone_bricks");
                        continue;
                    }
                    Canvas.set(u, g, v, edge ? "polished_andesite" : "stone_bricks");
                    if (v + 1 <= c.vTo() && Terrain.h(u, v + 1) == g + 1)
                        Canvas.set(u, g + 1, v, B.stairs(edge ? "polished_andesite" : "stone_brick", B.Dir.SOUTH));
                    else if (edge && (v % 6 == 0)) Canvas.setIfAir(u, g + 1, v, "stone_brick_wall");
                }
    }

    static void parapets() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                if (!Terrain.WALL[u - Canvas.MINX][v - Canvas.MINZ]) continue;
                if (Terrain.corridorAt(u, v) != null || river(u, v)) continue;
                int g = Terrain.h(u, v);
                if (!Canvas.isAir(u, g + 1, v)) continue;
                if (Terrain.nearBasin(u, v)) {
                    if (Math.floorMod(u * 7 + v * 13, 9) == 0) Canvas.set(u, g + 1, v, "stone_brick_wall");
                    continue;
                }
                int low = g;
                for (B.Dir d : B.Dir.values()) low = Math.min(low, Terrain.h(u + d.dx, v + d.dz));
                if (g - low >= 2) Canvas.set(u, g + 1, v, Canvas.rnd() < 0.8 ? "stone_brick_wall" : "cobblestone_wall");
            }
    }

    // ------------------------------------------------------------------ harbour

    /** Walks from the basin centre towards (du, dv) and returns the last water cell before the quay. */
    static int[] quayPoint(int du, int dv) {
        int u = Terrain.CU, v = Terrain.CV;
        for (int k = 0; k < 60; k++) {
            int nu = u + du, nv = v + dv;
            if (!Terrain.inBasin(nu, nv)) return new int[] {u, v};
            u = nu;
            v = nv;
        }
        return new int[] {u, v};
    }

    static void harbour() {
        // the arrival ship alongside the straight quay, bow to the east
        B.Frame ship = B.Frame.facing(-17, 0, -9, B.Dir.EAST);
        Harbour.longship(ship, 35, 9, new String[] {"red_wool", "white_wool"}, false);
        Canvas.use(-22, -14, 22, -4);
        for (int u = -6; u <= -4; u++) {
            Canvas.set(u, 0, -4, "spruce_planks");
            Canvas.set(u, -1, -4, "air");
        }
        Canvas.set(-7, 1, -4, "spruce_fence");
        Canvas.set(-3, 1, -4, "spruce_fence");
        SPAWN = new int[] {-5, 1, -9};
        // a second ship moored in the west of the basin, sail furled
        Harbour.longship(B.Frame.facing(-17, 0, -20, B.Dir.NORTH), 25, 7, null, false);
        // a ship under full sail out in the bay, heading for the mouth
        Harbour.longship(B.Frame.facing(-52, 0, -112, B.Dir.EAST), 31, 9, new String[] {"red_wool", "red_wool", "white_wool"}, true);
        // piers into the basin
        int[][] dirs = {{-1, 1}, {1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            int[] q = quayPoint(d[0], d[1]);
            B.Dir towards = Math.abs(d[0]) >= Math.abs(d[1]) ? (d[0] < 0 ? B.Dir.EAST : B.Dir.WEST) : (d[1] < 0 ? B.Dir.SOUTH : B.Dir.NORTH);
            Harbour.pier(B.Frame.facing(q[0], 0, q[1], towards), 9);
        }
        // boathouses on the western shore of the basin
        for (int k = 0; k < 3; k++) {
            int[] q = quayPoint(-1, 0);
            int v = Terrain.CV - 12 + k * 8;
            int u = q[0];
            while (u > -60 && Terrain.inBasin(u - 1, v)) u--;
            Harbour.boathouse(B.Frame.facing(u + 3, 0, v, B.Dir.EAST), 5, 7);
            Canvas.use(u - 6, v - 4, u + 4, v + 4);
        }
        // the stilt quarter over the eastern basin
        int su = 14, sv = -48, sw = 13, sd = 22;
        B.Frame deck = new B.Frame(su, 1, sv, 0);
        Harbour.stiltDeck(deck, sw, sd, -12);
        Build.house(new B.Frame(su + 10, 2, sv + 1, 1), 9, 8, 3, Build.FALU, new Build.Opts(false, true, true, true, false, true, false));
        Build.house(new B.Frame(su + 10, 2, sv + 12, 1), 9, 8, 3, Build.OCHRE, new Build.Opts(true, true, false, false, false, true, true));
        Harbour.tallChimney(deck, 11, 2, 1, 26);
        Harbour.tallChimney(deck, 11, 13, 1, 22);
        Harbour.armDetails();
        // boat sheds on the inner side of both arms
        for (int sign : new int[] {-1, 1})
            for (int deg : new int[] {118, 142}) {
                double a = Math.toRadians(deg);
                int u = sign * (int) Math.round(Math.sin(a) * 40), v = Terrain.CV + (int) Math.round(Math.cos(a) * 40);
                B.Dir front = sign < 0 ? B.Dir.EAST : B.Dir.WEST;
                B.Frame zero = B.Frame.facing(0, 0, 0, front);
                int ou = u - zero.wx(3, 3), ov = v - zero.wz(3, 3);
                B.Frame probe = B.Frame.facing(ou, 0, ov, front);
                boolean ok = true;
                for (int x = 0; x < 7 && ok; x++)
                    for (int z = 0; z < 6 && ok; z++) ok = Terrain.arm(probe.wx(x, z), probe.wz(x, z));
                if (ok) Build.house(B.Frame.facing(ou, 2, ov, front), 7, 6, 1, sign < 0 ? Build.FALU : Build.TARRED,
                        new Build.Opts(true, false, false, false, false, true, false));
            }
        // rowing boats in the basin
        for (int i = 0, n = 0; i < 400 && n < 9; i++) {
            int u = Canvas.rint(50) - 25, v = Terrain.CV + Canvas.rint(50) - 25;
            if (!Terrain.inBasin(u, v) || Canvas.used(u, v) || Math.abs(v + 9) < 7 || !Canvas.get(u, Canvas.SEA + 1, v).equals("air")) continue;
            boolean clear = true;
            for (int du = -3; du <= 3; du++) for (int dv = -3; dv <= 3; dv++) if (!Canvas.isAir(u + du, 0, v + dv)) clear = false;
            if (!clear) continue;
            Canvas.ENTITIES.add(String.format(java.util.Locale.ROOT, "summon %s ~%.1f ~-1.4 ~%.1f {Tags:[\"nordia_spawn\"],Rotation:[%df,0f]}",
                    Canvas.pick("spruce_boat", "oak_boat", "dark_oak_boat", "birch_boat", "spruce_chest_boat"), u + 0.5, v + 0.5, Canvas.rint(360)));
            Canvas.use(u - 1, v - 1, u + 1, v + 1);
            n++;
        }
        // crane on the eastern quay
        Harbour.crane(B.Frame.facing(27, 1, -6, B.Dir.NORTH));
        // beacon lighthouse on the north-east headland
        int bu = 100, bv = -106;
        Harbour.beacon(new B.Frame(bu, Math.max(Canvas.ground(bu, bv), 2) + 1, bv, 0));
        Canvas.use(bu - 6, bv - 6, bu + 6, bv + 6);
    }

    // ------------------------------------------------------------------ landmarks

    static void landmarks() {
        label(0.5, 13.5, 16.5, "NORDHAMN", "Välkommen till NORDIA", "#FFD37A", 3.0f);
        label(0.5, Terrain.PLATEAU + 16.5, 70.5, "KUNGSHALLEN", "", "#F3C969", 2.4f);
        for (int sign : new int[] {-1, 1}) {
            B.Frame f = B.Frame.facing(sign * 19, 0, -93, B.Dir.NORTH);
            Monument.plinth(f, -13);
            Monument.guardian(f);
            Canvas.use(sign * 19 - 11, -104, sign * 19 + 11, -82);
        }
        // the king's hall on the plateau, entrance towards the town
        int hallW = 19, hallL = 33;
        B.Frame hall = new B.Frame(hallW / 2, Terrain.PLATEAU, 76 + hallL - 1, 2);
        Landmark.hall(hall, hallW, hallL);
        Monument.worldTree(38, 84);
        for (int[] r : new int[][] {{24, 72}, {48, 70}, {52, 96}, {30, 100}}) {
            B.Frame rs = new B.Frame(r[0], Canvas.ground(r[0], r[1]) + 1, r[1], Canvas.rint(4));
            Monument.runestone(rs);
        }
        // stave church on the western slope
        place(-58, 30, 11, 15, B.Dir.EAST, (f) -> Landmark.staveChurch(f));
        // watchtowers on the harbour arms
        for (int sign : new int[] {-1, 1}) {
            int u = sign * 20 - 2, v = -76;
            Landmark.watchtower(new B.Frame(u, Canvas.ground(u + 2, v + 2) + 1, v, 0), 14);
        }
    }

    interface Builder {
        void build(B.Frame f);
    }

    /** Places a structure with its front towards {@code front}, levelled on the highest ground under it. */
    static void place(int u, int v, int w, int d, B.Dir front, Builder b) {
        B.Frame probe = B.Frame.facing(u, 0, v, front);
        int y = probe.maxGround(0, 0, w - 1, d - 1) + 1;
        b.build(B.Frame.facing(u, y, v, front));
    }

    // ------------------------------------------------------------------ market square

    static void market() {
        int mu = -13, mv = 5;
        int y = Terrain.h(mu, mv);
        // midsummer pole: dressed in leaves and flowers, a cross-bar with two hanging wreaths, blue and yellow ribbons
        int top = y + 17;
        Canvas.fill(mu, y + 1, mv, mu, top, mv, "stripped_spruce_log[axis=y]");
        for (int yy = y + 3; yy <= top - 1; yy++) {
            B.Dir d = B.Dir.values()[yy % 4];
            Canvas.set(mu + d.dx, yy, mv + d.dz, yy % 3 == 0 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
        }
        Canvas.fill(mu - 5, top - 3, mv, mu + 5, top - 3, mv, "stripped_spruce_log[axis=x]");
        for (int x = -5; x <= 5; x++) if (x != 0) Canvas.set(mu + x, top - 2, mv, "azalea_leaves[persistent=true]");
        for (int side : new int[] {-4, 4}) {
            Canvas.set(mu + side, top - 4, mv, "iron_chain[axis=y]");
            int cy = top - 7;
            for (int a = 0; a < 360; a += 30) {
                double r = Math.toRadians(a);
                int x = mu + side + (int) Math.round(Math.cos(r) * 2), yy = cy + (int) Math.round(Math.sin(r) * 2);
                Canvas.set(x, yy, mv, a % 90 == 0 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
            }
        }
        for (int x : new int[] {-2, -1, 1, 2}) Canvas.fill(mu + x, top - 7, mv + 1, mu + x, top - 4, mv + 1, Math.abs(x) == 1 ? "blue_wool" : "yellow_wool");
        Canvas.set(mu, top + 1, mv, "flowering_azalea_leaves[persistent=true]");
        for (int x = -3; x <= 3; x++)
            for (int z = 1; z <= 7; z++)
                if (Math.abs(x) + Math.abs(z - 4) == 3) Canvas.setIfAir(mu + x, y + 1, mv - 4 + z, Canvas.pick("potted_poppy", "potted_cornflower", "potted_dandelion", "potted_azure_bluet"));
        // stalls around the square (future rentable player shops)
        String[][] cloth = {{"red_wool", "white_wool"}, {"blue_wool", "white_wool"}, {"yellow_wool", "white_wool"}, {"green_wool", "white_wool"}};
        int c = 0;
        for (int x = -20; x <= 16; x += 6) {
            if (Math.abs(x + 2) < 7) continue;
            stall(x, 8, cloth[c++ % 4]);
        }
        // well
        int wu = 14, wv = 3;
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                Canvas.set(wu + x, y, wv + z, x == 0 && z == 0 ? "water" : "cobblestone");
                if (x != 0 || z != 0) Canvas.set(wu + x, y + 1, wv + z, "cobblestone_wall");
            }
        Canvas.fill(wu - 1, y + 2, wv, wu - 1, y + 3, wv, "spruce_fence");
        Canvas.fill(wu + 1, y + 2, wv, wu + 1, y + 3, wv, "spruce_fence");
        Canvas.fill(wu - 1, y + 4, wv, wu + 1, y + 4, wv, "spruce_slab[type=bottom]");
        Canvas.set(wu, y + 3, wv, "iron_chain[axis=y]");
        for (int[] b : new int[][] {{-8, 1}, {8, 1}, {-20, 5}, {20, 5}}) Build.bench(b[0], b[1], B.Dir.NORTH);
        for (int[] l : new int[][] {{-7, 0}, {7, 0}, {-22, 0}, {22, 0}, {-7, 9}, {7, 9}}) Build.hangingLamp(l[0], l[1], B.Dir.NORTH);
    }

    static void stall(int u, int v, String[] cloth) {
        int y = Terrain.h(u, v);
        for (int[] p : new int[][] {{0, 0}, {3, 0}, {0, 2}, {3, 2}}) Canvas.fill(u + p[0], y + 1, v + p[1], u + p[0], y + 3, v + p[1], "spruce_fence");
        for (int x = 0; x <= 3; x++)
            for (int z = 0; z <= 2; z++) Canvas.set(u + x, y + 4 - (z == 0 ? 1 : 0) + (z == 2 ? 0 : 0), v + z, ((x & 1) == 0) ? cloth[0] : cloth[1]);
        for (int x = 1; x <= 2; x++) {
            Canvas.set(u + x, y + 1, v, "stripped_spruce_wood");
            Canvas.set(u + x, y + 1, v + 2, Canvas.pick("barrel[facing=up]", "hay_block", "melon", "pumpkin", "decorated_pot"));
        }
        Canvas.set(u + 1, y + 2, v, Canvas.pick("potted_red_tulip", "flower_pot", "potted_fern", "candle[candles=3,lit=true]"));
        Canvas.set(u + 2, y + 3, v + 1, "lantern[hanging=true]");
        Canvas.use(u, v, u + 3, v + 2);
    }

    // ------------------------------------------------------------------ named districts

    static void districts() {
        // a Viking longhouse row on the eastern slope and on the plateau
        tryLonghouse(56, 10, 23, 9, true);
        tryLonghouse(-36, 68, 21, 9, true);
        tryLonghouse(-60, 60, 19, 9, false);
        tryLonghouse(66, 40, 21, 9, true);
    }

    static void tryLonghouse(int u, int v, int len, int wid, boolean turf) {
        for (int k = 0; k < 40; k++) {
            int uu = u + Canvas.rint(15) - 7, vv = v + Canvas.rint(15) - 7;
            B.Dir front = B.Dir.SOUTH;
            B.Frame probe = B.Frame.facing(uu, 0, vv, front);
            boolean ok = true;
            int min = 999, max = -999;
            for (int x = -3; x < len + 3 && ok; x++)
                for (int z = -3; z < wid + 3 && ok; z++) {
                    int wu = probe.wx(x, z), wv = probe.wz(x, z);
                    if (!Canvas.inXZ(wu, wv) || Canvas.used(wu, wv) || Terrain.inBasin(wu, wv) || river(wu, wv) || Terrain.w(wu, wv) < 0.9) ok = false;
                    else {
                        min = Math.min(min, Terrain.h(wu, wv));
                        max = Math.max(max, Terrain.h(wu, wv));
                    }
                }
            if (!ok || max - min > 6) continue;
            Build.longhouse(B.Frame.facing(uu, max + 1, vv, front), len, wid, turf);
            return;
        }
    }

    // ------------------------------------------------------------------ houses

    static boolean buildable(int u, int v) {
        if (!Canvas.inXZ(u, v) || Canvas.used(u, v) || Terrain.inBasin(u, v) || river(u, v)) return false;
        return Terrain.town(u, v) && Terrain.h(u, v) >= 0;
    }

    /**
     * Walks along every street edge and tries to put a house there with its door on the street: the way real towns
     * grow. Larger houses are tried first at each spot.
     */
    static void houses() {
        int townCells = 0, usedCells = 0, streetCells = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                if (!Terrain.town(u, v) || Terrain.inBasin(u, v)) continue;
                townCells++;
                if (Canvas.used(u, v)) usedCells++;
                if (Canvas.street(u, v)) streetCells++;
            }
        System.out.println("town cells " + townCells + " used " + usedCells + " street " + streetCells);
        java.util.List<int[]> edges = new java.util.ArrayList<>();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                if (!Canvas.street(u, v) || Terrain.corridorAt(u, v) != null) continue;
                for (B.Dir d : B.Dir.values()) if (buildable(u + d.dx, v + d.dz)) edges.add(new int[] {u, v, d.ordinal()});
            }
        java.util.Collections.shuffle(edges, Canvas.RNG);
        int[][] sizes = {{13, 9, 3}, {11, 9, 3}, {11, 8, 2}, {9, 8, 2}, {9, 7, 2}, {9, 7, 1}, {8, 7, 2}, {7, 7, 2}, {7, 6, 1}};
        int placed = 0;
        for (int[] e : edges) {
            B.Dir out = B.Dir.values()[e[2]];
            int nu = e[0] + out.dx, nv = e[1] + out.dz;
            if (!buildable(nu, nv)) continue;
            B.Dir front = out.opposite();
            int start = Canvas.rint(3);
            for (int s = start; s < sizes.length; s++) {
                int[] size = sizes[s];
                boolean swap = size[0] > 9 && Canvas.rnd() < 0.25;
                int w = swap ? size[1] : size[0], d = swap ? size[0] : size[1];
                B.Frame zero = B.Frame.facing(0, 0, 0, front);
                int lx = (w - 1) / 2 + Canvas.rint(3) - 1, lz = d - 1;
                int ou = nu - zero.wx(lx, lz), ov = nv - zero.wz(lx, lz);
                B.Frame probe = B.Frame.facing(ou, 0, ov, front);
                if (!plotOk(probe, w, d)) continue;
                int y = Build.floorLevel(probe, w, d);
                Build.house(B.Frame.facing(ou, y, ov, front), w, d, size[2], Canvas.pick(Build.TOWN_STYLES), Build.Opts.random(w, d, size[2]));
                placed++;
                break;
            }
        }
        System.out.println("houses: " + placed + " rejected used/other/zone/slope " + java.util.Arrays.toString(REJECT));
    }

    static final int[] REJECT = new int[4];

    static boolean plotOk(B.Frame f, int w, int d) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int overlap = 0;
        for (int x = -1; x <= w; x++)
            for (int z = 0; z < d; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                // the two front rows may bite into the street edge (curved streets vs square houses)
                if (z >= d - 2 && Canvas.street(u, v) && Terrain.corridorAt(u, v) == null && !Terrain.inBasin(u, v)
                    && Terrain.h(u, v) >= 0 && overlap++ < w) continue;
                if (!buildable(u, v)) {
                    REJECT[Canvas.used(u, v) ? 0 : Terrain.town(u, v) ? 1 : 2]++;
                    return false;
                }
                if (x >= 0 && x < w) {
                    int g = Terrain.h(u, v);
                    min = Math.min(min, g);
                    max = Math.max(max, g);
                }
            }
        if (max - min > 5) {
            REJECT[3]++;
            return false;
        }
        int streets = 0;
        for (int x = 1; x < w - 1; x++)
            for (int z = d; z <= d + 2; z++) if (Canvas.street(f.wx(x, z), f.wz(x, z))) streets++;
        return streets >= 2;
    }

    // ------------------------------------------------------------------ defences and lights

    static void defences() {
        Landmark.palisade(new int[][] {{-58, -26}, {-72, -10}, {-84, 12}, {-88, 36}, {-80, 58}, {-62, 74}});
        Landmark.palisade(new int[][] {{58, -26}, {72, -10}, {84, 12}, {88, 36}, {80, 58}, {62, 74}});
        for (int[] t : new int[][] {{-86, 22}, {86, 22}, {-76, 64}, {76, 64}}) {
            int g = Canvas.ground(t[0], t[1]);
            if (g > Canvas.SEA) Landmark.watchtower(new B.Frame(t[0] - 2, g + 1, t[1] - 2, 0), 12);
        }
    }

    static void lamps() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u += 1)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v += 1) {
                if (!Canvas.street(u, v) || Math.floorMod(u * 5 + v * 3, 23) != 0) continue;
                boolean edge = false;
                for (B.Dir d : B.Dir.values()) if (!Canvas.street(u + d.dx, v + d.dz)) edge = true;
                if (edge && Terrain.corridorAt(u, v) == null) Build.lamp(u, v);
            }
    }

    // ------------------------------------------------------------------ labels

    static void label(double u, double y, double v, String title, String subtitle, String colour, float scale) {
        String text = subtitle.isEmpty()
                ? "{text:\"" + title + "\",color:\"" + colour + "\",bold:true}"
                : "[{text:\"" + title + "\",color:\"" + colour + "\",bold:true},{text:\"\\n" + subtitle + "\",color:\"#E8E8E8\",bold:false}]";
        Canvas.ENTITIES.add(String.format(java.util.Locale.ROOT,
                "summon text_display ~%.1f ~%.1f ~%.1f {Tags:[\"nordia_spawn\"],billboard:\"center\",shadow:1b,background:1073741824,"
                + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%.1ff,%.1ff,%.1ff]},text:%s}",
                u, y, v, scale, scale, scale, text));
    }
}
