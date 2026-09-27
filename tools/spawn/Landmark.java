/** The big set pieces: the king's hall, the stave church, watchtowers, gates and palisades. All in local frames. */
final class Landmark {

    static String log(String m, char axis) {
        return m + "[axis=" + axis + "]";
    }

    // ------------------------------------------------------------------ carved dragon heads

    /** A carved dragon neck and head rising out of a gable apex, pointing along local +z. {@code s} = scale. */
    static void dragon(B.Frame f, int x, int y, int z, int s, boolean forward) {
        int[][] neck = {{0, 0}, {0, 1}, {1, 1}, {1, 2}, {2, 3}, {2, 4}, {3, 4}, {3, 5}};
        int[][] head = {{4, 5}, {5, 5}, {4, 6}, {5, 6}, {6, 5}, {3, 6}};
        int dir = forward ? 1 : -1;
        for (int[] p : neck) cube(f, x, y + p[1] * s, z + dir * p[0] * s, s, "dark_oak_planks");
        for (int[] p : head) cube(f, x, y + p[1] * s, z + dir * p[0] * s, s, "dark_oak_planks");
        cube(f, x, y + 4 * s, z + dir * 6 * s, s, B.stairsTop("dark_oak", forward ? B.Dir.NORTH : B.Dir.SOUTH)); // jaw
        cube(f, x, y + 7 * s, z + dir * 3 * s, s, B.stairs("dark_oak", forward ? B.Dir.SOUTH : B.Dir.NORTH));   // crest
        // gilded eyes on both sides
        for (int k = 0; k < s; k++) {
            f.set(x - 1, y + 6 * s + k, z + dir * 5 * s, "gold_block");
            f.set(x + s, y + 6 * s + k, z + dir * 5 * s, "gold_block");
        }
    }

    static void cube(B.Frame f, int x, int y, int z, int s, String state) {
        for (int a = 0; a < s; a++) for (int b = 0; b < s; b++) for (int c = 0; c < s; c++) f.set(x + a, y + b, z + c, state);
    }

    /** A round painted shield on a wall facing local +z at (cx, cy, z). */
    static void shield(B.Frame f, int cx, int cy, int z, int r, String field, String rim) {
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++) {
                double d = Math.hypot(x, y);
                if (d > r + 0.4) continue;
                String s = d > r - 0.6 ? rim : d > r - 1.6 ? field : d > 1.2 ? "black_wool" : "gold_block";
                f.set(cx + x, cy + y, z, s);
            }
    }

    // ------------------------------------------------------------------ Kungshallen

    /**
     * The king's mead hall: 19 wide, 41 long, gable entrance at local +z. Outer gallery, huge shingle roof, dragon heads,
     * hearth fires, long tables and a throne inside. Local y 0 = podium top.
     */
    static void hall(B.Frame f, int w, int l) {
        int wall = 8;
        f.use(-7, -7, w + 6, l + 12);
        // podium with steps in front
        f.foundation(-6, -5, w + 5, l + 4, 0, "stone_bricks");
        for (int x = -6; x <= w + 5; x++)
            for (int z = -5; z <= l + 4; z++) {
                boolean edge = x == -6 || x == w + 5 || z == -5 || z == l + 4;
                f.set(x, 0, z, edge ? "chiseled_stone_bricks" : (x + z) % 2 == 0 ? "polished_andesite" : "stone_bricks");
            }
        for (int k = 1; k <= 6; k++)
            for (int x = w / 2 - 6; x <= w / 2 + 6; x++) {
                f.set(x, 1 - k, l + 4 + k, B.stairs("stone_brick", B.Dir.NORTH));
                f.fill(x, -8, l + 4 + k, x, -k, l + 4 + k, "stone_bricks");
            }
        // walls: stone socle, vertical logs and planks
        for (int y = 1; y <= wall; y++)
            for (int x = 0; x < w; x++)
                for (int z = 0; z < l; z++) {
                    boolean xe = x == 0 || x == w - 1, ze = z == 0 || z == l - 1;
                    if (!xe && !ze) {
                        f.set(x, y, z, "air");
                        continue;
                    }
                    String s;
                    if (y == 1) s = "stone_bricks";
                    else if (xe && ze) s = log("dark_oak_log", 'y');
                    else if ((xe && z % 4 == 0) || (ze && x % 4 == 0)) s = log("stripped_dark_oak_log", 'y');
                    else if (y == wall) s = xe ? log("dark_oak_log", 'z') : log("dark_oak_log", 'x');
                    else s = (y % 3 == 0) ? "dark_oak_planks" : "spruce_planks";
                    f.set(x, y, z, s);
                }
        for (int x = 1; x < w - 1; x++) for (int z = 1; z < l - 1; z++) f.set(x, 0, z, (x + z) % 3 == 0 ? "stripped_spruce_wood" : "spruce_planks");
        // high windows between the posts
        for (int z = 2; z < l - 2; z += 4) {
            f.fill(0, 5, z, 0, 6, z + 1, "glass_pane");
            f.fill(w - 1, 5, z, w - 1, 6, z + 1, "glass_pane");
        }
        // outer gallery (svalgang) along both long sides with a lean-to roof
        for (int side : new int[] {-1, 1}) {
            int xo = side < 0 ? -4 : w + 3;
            for (int z = -2; z <= l + 1; z++) {
                for (int k = 0; k < 4; k++) {
                    int x = side < 0 ? -1 - k : w + k;
                    f.set(x, wall + 1 - k, z, B.stairs("dark_oak", side < 0 ? B.Dir.EAST : B.Dir.WEST));
                }
                if (z % 4 == 0 || z == -2 || z == l + 1) {
                    for (int y = 1; y <= wall - 3; y++) f.set(xo + (side < 0 ? 1 : -1), y, z, log("stripped_dark_oak_log", 'y'));
                    f.set(xo + (side < 0 ? 1 : -1), wall - 2, z, B.stairsTop("dark_oak", side < 0 ? B.Dir.EAST : B.Dir.WEST));
                } else {
                    f.set(xo + (side < 0 ? 1 : -1), 1, z, "dark_oak_fence");
                }
            }
        }
        // main roof: ridge along z, dark shingles, thick eaves
        int ridge = 0;
        int lo = -1, hi = w, y = wall + 1;
        while (lo <= hi) {
            for (int z = -3; z <= l + 2; z++) {
                if (lo == hi) f.set(lo, y, z, "dark_oak_planks");
                else {
                    f.set(lo, y, z, B.stairs("dark_oak", B.Dir.EAST));
                    f.set(hi, y, z, B.stairs("dark_oak", B.Dir.WEST));
                    if (y > wall + 1 && (z == -3 || z == l + 2)) {
                        f.set(lo, y - 1, z, B.stairsTop("dark_oak", B.Dir.WEST));
                        f.set(hi, y - 1, z, B.stairsTop("dark_oak", B.Dir.EAST));
                    }
                }
            }
            ridge = y;
            lo++;
            hi--;
            y++;
        }
        // ridge crest
        for (int z = -3; z <= l + 2; z += 2) f.set(w / 2, ridge + 1, z, "dark_oak_fence");
        // gable facades with carved patterns, a great shield and the entrance
        for (int z : new int[] {-1, l}) {
            for (int x = 0; x < w; x++) {
                int roofY = wall + 1 + Math.min(x + 1, w - x);
                for (int yy = wall + 1; yy < roofY; yy++) {
                    boolean diamond = (Math.abs(x - w / 2) + yy) % 4 == 0;
                    f.set(x, yy, z, diamond ? "stripped_spruce_wood" : "dark_oak_planks");
                }
            }
        }
        shield(f, w / 2, wall + 5, l, 3, "red_wool", "gold_block");
        shield(f, w / 2, wall + 5, -1, 3, "blue_wool", "gold_block");
        // crossed gable boards and dragon heads at both ends
        for (int end : new int[] {0, 1}) {
            int z = end == 1 ? l + 2 : -3;
            dragon(f, w / 2 - 1, ridge, z, 2, end == 1);
            for (int k = 1; k <= 4; k++) {
                f.set(w / 2 - k, ridge - k + 1, z, B.stairs("spruce", B.Dir.EAST));
                f.set(w / 2 + k, ridge - k + 1, z, B.stairs("spruce", B.Dir.WEST));
            }
        }
        // entrance: double doors in a carved portal
        int dx = w / 2;
        f.fill(dx - 2, 1, l - 1, dx + 2, 5, l - 1, log("stripped_dark_oak_log", 'y'));
        f.fill(dx - 1, 1, l - 1, dx + 1, 4, l - 1, "air");
        f.set(dx - 1, 1, l - 1, "spruce_door[facing=south,half=lower,hinge=left]");
        f.set(dx - 1, 2, l - 1, "spruce_door[facing=south,half=upper,hinge=left]");
        f.set(dx + 1, 1, l - 1, "spruce_door[facing=south,half=lower,hinge=right]");
        f.set(dx + 1, 2, l - 1, "spruce_door[facing=south,half=upper,hinge=right]");
        f.set(dx, 1, l - 1, "spruce_planks");
        f.set(dx, 2, l - 1, "spruce_planks");
        f.set(dx, 3, l - 1, "gold_block");
        for (int x : new int[] {dx - 4, dx + 4}) {
            f.set(x, 4, l, "red_wall_banner[facing=south]");
        }
        // porch roof over the entrance
        for (int z = l; z <= l + 3; z++) {
            f.set(dx - 3, 5, z, B.stairs("dark_oak", B.Dir.EAST));
            f.set(dx + 3, 5, z, B.stairs("dark_oak", B.Dir.WEST));
            f.set(dx - 2, 6, z, B.stairs("dark_oak", B.Dir.EAST));
            f.set(dx + 2, 6, z, B.stairs("dark_oak", B.Dir.WEST));
            f.set(dx - 1, 7, z, B.stairs("dark_oak", B.Dir.EAST));
            f.set(dx + 1, 7, z, B.stairs("dark_oak", B.Dir.WEST));
            f.set(dx, 7, z, "dark_oak_planks");
            f.set(dx, 8, z, B.slab("dark_oak"));
        }
        for (int x : new int[] {dx - 3, dx + 3}) f.fill(x, 1, l + 3, x, 4, l + 3, log("stripped_dark_oak_log", 'y'));
        f.set(dx, 6, l + 3, "lantern[hanging=true]");
        // braziers on the podium
        for (int x : new int[] {-4, 3, w - 4, w + 3}) {
            f.set(x, 1, l + 3, "stone_brick_wall");
            f.set(x, 2, l + 3, "polished_andesite");
            f.set(x, 3, l + 3, "campfire[lit=true,signal_fire=false]");
        }
        // interior: pillars, hearth, tables, throne, chandeliers
        for (int z = 4; z < l - 3; z += 6)
            for (int x : new int[] {4, w - 5}) f.fill(x, 1, z, x, wall + 3, z, log("stripped_dark_oak_log", 'y'));
        for (int z = 6; z < l - 6; z += 3) f.set(w / 2, 1, z, "campfire[lit=true,signal_fire=false]");
        for (int z = 5; z < l - 5; z++) {
            f.set(6, 1, z, "spruce_fence");
            f.set(6, 2, z, B.slab("spruce"));
            f.set(w - 7, 1, z, "spruce_fence");
            f.set(w - 7, 2, z, B.slab("spruce"));
            f.set(7, 1, z, B.stairs("spruce", B.Dir.EAST));
            f.set(w - 8, 1, z, B.stairs("spruce", B.Dir.WEST));
        }
        f.fill(w / 2 - 2, 1, 1, w / 2 + 2, 1, 3, "polished_andesite");
        f.set(w / 2, 2, 2, B.stairs("dark_oak", B.Dir.NORTH));
        f.set(w / 2, 3, 1, "gold_block");
        f.set(w / 2 - 1, 2, 2, "gold_block");
        f.set(w / 2 + 1, 2, 2, "gold_block");
        for (int z = 6; z < l - 4; z += 8) {
            f.fill(w / 2, wall + 2, z, w / 2, ridge - 1, z, "iron_chain[axis=y]");
            f.set(w / 2, wall + 1, z, "lantern[hanging=true]");
        }
        for (int z = 3; z < l - 2; z += 5) {
            f.set(1, 4, z, "blue_wall_banner[facing=east]");
            f.set(w - 2, 4, z, "red_wall_banner[facing=west]");
        }
    }

    // ------------------------------------------------------------------ stave church

    /** A Borgund-style stave church: gallery, stacked shingle roofs, cross gables, dragon heads and a spire. */
    static void staveChurch(B.Frame f) {
        int w = 11, l = 15;
        f.use(-4, -4, w + 3, l + 4);
        f.foundation(-3, -3, w + 2, l + 2, 0, "cobblestone");
        for (int x = -3; x <= w + 2; x++) for (int z = -3; z <= l + 2; z++) f.set(x, 0, z, Build.stone());
        // gallery: arcade posts and a low roof around the core
        for (int x = -2; x <= w + 1; x++)
            for (int z = -2; z <= l + 1; z++) {
                boolean ring = x == -2 || x == w + 1 || z == -2 || z == l + 1;
                if (!ring) continue;
                boolean post = (x + z) % 2 == 0;
                f.set(x, 1, z, post ? log("dark_oak_log", 'y') : "dark_oak_fence");
                f.set(x, 2, z, post ? log("dark_oak_log", 'y') : "air");
                f.set(x, 3, z, "dark_oak_planks");
            }
        for (int k = 0; k < 2; k++)
            for (int x = -3 + k; x <= w + 2 - k; x++)
                for (int z = -3 + k; z <= l + 2 - k; z++) {
                    boolean ring = x == -3 + k || x == w + 2 - k || z == -3 + k || z == l + 2 - k;
                    if (!ring) continue;
                    B.Dir up = x == -3 + k ? B.Dir.EAST : x == w + 2 - k ? B.Dir.WEST : z == -3 + k ? B.Dir.SOUTH : B.Dir.NORTH;
                    f.set(x, 4 + k, z, B.stairs("dark_oak", up));
                }
        // core walls
        for (int y = 1; y <= 10; y++)
            for (int x = 0; x < w; x++)
                for (int z = 0; z < l; z++) {
                    boolean xe = x == 0 || x == w - 1, ze = z == 0 || z == l - 1;
                    if (!xe && !ze) {
                        f.set(x, y, z, "air");
                        continue;
                    }
                    f.set(x, y, z, (xe && ze) ? log("dark_oak_log", 'y') : (x + z) % 2 == 0 ? "dark_oak_planks" : "spruce_planks");
                }
        // tier 1 roof (ridge along z) with cross gables on the long sides
        Build.Style dark = new Build.Style("dark_oak_log", "stripped_dark_oak_log", "dark_oak_planks", "dark_oak_planks", "dark_oak",
                "dark_oak_planks", "dark_oak", "dark_oak", "dark_oak", "dark_oak_planks");
        int[] r1 = Build.roof(f, -1, -1, w, l, 10, false, dark);
        int mz = l / 2;
        for (int z = mz - 2; z <= mz + 2; z++) {
            for (int x = -2; x <= w + 1; x++) {
                int k = Math.abs(z - mz);
                if (k == 2) f.set(x, 10, z, B.stairs("dark_oak", z < mz ? B.Dir.SOUTH : B.Dir.NORTH));
                else if (k == 1) f.set(x, 11, z, B.stairs("dark_oak", z < mz ? B.Dir.SOUTH : B.Dir.NORTH));
                else f.set(x, 12, z, "dark_oak_planks");
            }
        }
        // tier 2: raised clerestory with its own roof
        int cx0 = 2, cx1 = w - 3, cz0 = 3, cz1 = l - 4;
        for (int y = r1[0] - 3; y <= r1[0] + 1; y++)
            for (int x = cx0; x <= cx1; x++)
                for (int z = cz0; z <= cz1; z++) {
                    boolean edge = x == cx0 || x == cx1 || z == cz0 || z == cz1;
                    if (edge) f.set(x, y, z, (x == cx0 || x == cx1) && (z == cz0 || z == cz1) ? log("dark_oak_log", 'y') : "dark_oak_planks");
                }
        int[] r2 = Build.roof(f, cx0 - 1, cz0 - 1, cx1 + 1, cz1 + 1, r1[0] + 2, false, dark);
        // tower and spire
        int tx = w / 2;
        int tz = l / 2;
        for (int y = r2[0]; y <= r2[0] + 4; y++)
            for (int x = tx - 1; x <= tx + 1; x++)
                for (int z = tz - 1; z <= tz + 1; z++)
                    f.set(x, y, z, (x == tx && z == tz) ? "air" : (y == r2[0] + 2 && x != tx && z != tz) ? log("dark_oak_log", 'y')
                            : (y == r2[0] + 2) ? "air" : "dark_oak_planks");
        int sy = r2[0] + 5;
        for (int k = 0; k < 9; k++) {
            int hs = k < 3 ? 2 : k < 6 ? 1 : 0;
            for (int x = tx - hs; x <= tx + hs; x++)
                for (int z = tz - hs; z <= tz + hs; z++) {
                    boolean edge = Math.abs(x - tx) == hs || Math.abs(z - tz) == hs;
                    if (hs == 0) f.set(x, sy + k, z, "dark_oak_planks");
                    else if (edge) {
                        B.Dir up = x == tx - hs ? B.Dir.EAST : x == tx + hs ? B.Dir.WEST : z == tz - hs ? B.Dir.SOUTH : B.Dir.NORTH;
                        f.set(x, sy + k, z, B.stairs("dark_oak", up));
                    } else f.set(x, sy + k, z, "dark_oak_planks");
                }
        }
        f.set(tx, sy + 9, tz, "gold_block");
        f.set(tx, sy + 10, tz, "lightning_rod");
        // dragon heads on both tiers
        dragon(f, w / 2, r1[0], l + 1, 1, true);
        dragon(f, w / 2, r1[0], -2, 1, false);
        dragon(f, w / 2, r2[0], cz1 + 2, 1, true);
        dragon(f, w / 2, r2[0], cz0 - 2, 1, false);
        // entrance, windows, candles
        f.set(w / 2, 1, l - 1, "dark_oak_door[facing=south,half=lower,hinge=left]");
        f.set(w / 2, 2, l - 1, "dark_oak_door[facing=south,half=upper,hinge=left]");
        f.set(w / 2, 3, l - 1, "gold_block");
        for (int z = 3; z < l - 2; z += 4) {
            f.set(0, 7, z, "glass_pane");
            f.set(w - 1, 7, z, "glass_pane");
        }
        for (int z = 2; z < l - 3; z += 3) {
            f.set(2, 1, z, B.stairs("spruce", B.Dir.WEST));
            f.set(w - 3, 1, z, B.stairs("spruce", B.Dir.WEST));
        }
        f.set(w / 2, 1, 1, "gold_block");
        f.set(w / 2 - 1, 1, 1, "white_candle[candles=4,lit=true]".replace("white_candle", "candle"));
        f.set(w / 2 + 1, 1, 1, "candle[candles=3,lit=true]");
        f.set(w / 2, 2, 1, "candle[candles=4,lit=true]");
        f.set(w / 2, 7, l / 2, "lantern[hanging=true]");
    }

    // ------------------------------------------------------------------ towers, gates, palisades

    /** Square watchtower: stone base, timber upper room, open lookout and a steep roof. Footprint 5x5 at (0..4). */
    static void watchtower(B.Frame f, int height) {
        f.use(-1, -1, 5, 5);
        f.foundation(0, 0, 4, 4, 0, "cobblestone");
        for (int y = 0; y <= height; y++)
            for (int x = 0; x < 5; x++)
                for (int z = 0; z < 5; z++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    boolean corner = (x == 0 || x == 4) && (z == 0 || z == 4);
                    String s;
                    if (y < height - 5) s = edge ? Build.stone() : "air";
                    else if (y == height - 5) s = "spruce_planks";
                    else s = corner ? log("dark_oak_log", 'y') : edge ? (y % 2 == 0 ? "spruce_planks" : "dark_oak_planks") : "air";
                    f.set(x, y, z, s);
                }
        for (int y = 2; y < height - 5; y += 4) f.set(2, y, 4, "air");
        // lookout platform with railing and overhanging brackets
        int p = height + 1;
        for (int x = -1; x <= 5; x++)
            for (int z = -1; z <= 5; z++) {
                f.set(x, p, z, "spruce_planks");
                boolean edge = x == -1 || x == 5 || z == -1 || z == 5;
                if (edge) f.set(x, p + 1, z, "spruce_fence");
            }
        for (int x : new int[] {-1, 5}) for (int z : new int[] {-1, 5}) f.fill(x, p + 1, z, x, p + 3, z, log("dark_oak_log", 'y'));
        f.set(2, p - 1, -1, B.stairsTop("spruce", B.Dir.SOUTH));
        f.set(2, p - 1, 5, B.stairsTop("spruce", B.Dir.NORTH));
        f.set(-1, p - 1, 2, B.stairsTop("spruce", B.Dir.EAST));
        f.set(5, p - 1, 2, B.stairsTop("spruce", B.Dir.WEST));
        // steep pyramid roof
        for (int k = 0; k < 6; k++) {
            int hs = 4 - k * 4 / 6;
            int y = p + 4 + k;
            for (int x = 2 - hs; x <= 2 + hs; x++)
                for (int z = 2 - hs; z <= 2 + hs; z++) {
                    boolean edge = Math.abs(x - 2) == hs || Math.abs(z - 2) == hs;
                    if (!edge && hs > 0) {
                        f.set(x, y, z, "dark_oak_planks");
                        continue;
                    }
                    if (hs == 0) {
                        f.set(x, y, z, "dark_oak_planks");
                        continue;
                    }
                    B.Dir up = x == 2 - hs ? B.Dir.EAST : x == 2 + hs ? B.Dir.WEST : z == 2 - hs ? B.Dir.SOUTH : B.Dir.NORTH;
                    f.set(x, y, z, B.stairs("dark_oak", up));
                }
        }
        f.set(2, p + 10, 2, "dark_oak_fence");
        f.set(2, p + 11, 2, "red_wool");
        f.set(2, p + 3, 2, "lantern[hanging=true]");
        f.set(2, p + 1, 2, "campfire[lit=true,signal_fire=false]");
    }

    /** Palisade along a list of relative (u, v) points: sharpened logs on a stone footing, with a walkway behind. */
    static void palisade(int[][] pts) {
        for (int i = 0; i + 1 < pts.length; i++) {
            int u0 = pts[i][0], v0 = pts[i][1], u1 = pts[i + 1][0], v1 = pts[i + 1][1];
            int n = Math.max(Math.abs(u1 - u0), Math.abs(v1 - v0));
            for (int k = 0; k <= n; k++) {
                int u = u0 + Math.round((u1 - u0) * k / (float) n), v = v0 + Math.round((v1 - v0) * k / (float) n);
                if (Canvas.street(u, v) || Terrain.RIVER[u - Canvas.MINX][v - Canvas.MINZ]) continue;
                int g = Canvas.ground(u, v);
                if (g < Canvas.SEA) continue;
                Canvas.set(u, g, v, "cobblestone");
                int h = 5 + ((u * 31 + v * 17) & 1);
                for (int y = g + 1; y <= g + h; y++) Canvas.set(u, y, v, log(((u + v) & 1) == 0 ? "spruce_log" : "stripped_spruce_log", 'y'));
                Canvas.set(u, g + h + 1, v, "spruce_fence");
                Canvas.use(u, v, u, v);
            }
        }
    }
}
