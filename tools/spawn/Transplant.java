/**
 * Moves a whole area (terrain and builds, block for block with all block states) from a source world into the canvas,
 * blending its outer band into the natural terrain of the target world so it sits in the new landscape.
 */
final class Transplant {

    static int BAND = 44;

    /** Bounds of the whole transplanted area; the blend band is measured from these even when built in tiles. */
    static int HX0 = -255, HX1 = 254, HZ0 = -247, HZ1 = 247;

    static final java.util.Set<String> SOIL = java.util.Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "stone",
            "andesite", "diorite", "granite", "gravel", "sand", "clay", "tuff", "deepslate", "moss_block", "mud", "dirt_path", "calcite",
            "snow_block", "sandstone", "red_sand", "terracotta", "water");

    static final java.util.Set<String> DEEP = java.util.Set.of("deepslate", "tuff", "stone", "cobbled_deepslate", "lava", "water",
            "calcite", "smooth_basalt", "amethyst_block", "budding_amethyst", "amethyst_cluster", "small_amethyst_bud",
            "medium_amethyst_bud", "large_amethyst_bud", "dripstone_block", "pointed_dripstone", "glow_lichen", "moss_block", "clay",
            "cave_air", "infested_deepslate", "infested_stone", "bedrock", "obsidian", "magma_block", "cave_vines", "cave_vines_plant",
            "moss_carpet", "rooted_dirt", "hanging_roots", "azalea", "flowering_azalea", "big_dripleaf", "big_dripleaf_stem",
            "small_dripleaf", "spore_blossom", "sculk", "sculk_vein", "sculk_sensor", "sculk_shrieker", "sculk_catalyst", "seagrass",
            "kelp", "kelp_plant", "mossy_cobblestone", "spawner", "chest", "cobweb", "air", "granite", "diorite", "andesite",
            "gravel", "dirt", "sand", "sandstone", "bubble_column", "rail", "oak_fence", "oak_planks", "mud", "powder_snow", "packed_ice", "ice", "blue_ice");

    /** y (relative) of the deepest man-made block in the column, or a large value if there is none. */
    static int deepestBuilt(WorldReader src, int x, int z) {
        int deepest = 9999;
        int top = src.surface(x, z);
        if (top == WorldReader.MISSING) return deepest;
        for (int y = -64; y < top - 16; y++) {
            String b = src.block(x, y, z);
            if (b == null) break;
            if (!DEEP.contains(b) && !b.endsWith("_ore") && !OldTown.natural(b)) return y - 64;
        }
        return deepest;
    }

    /** The natural ground under a column (roofs and floors of buildings skipped), relative to y 64. */
    static int terrain(WorldReader src, int x, int z) {
        int top = src.surface(x, z);
        if (top == WorldReader.MISSING) return 0;
        for (int y = top; y > top - 120; y--) {
            String b = src.block(x, y, z);
            if (b != null && SOIL.contains(b)) {
                String above = src.block(x, y + 1, z);
                if (b.equals("water") || above == null || !SOIL.contains(above) || above.equals("water")) return y - 64;
            }
        }
        return top - 64;
    }

    static void generate(WorldReader src, int srcX, int srcZ) {
        Canvas.overlay();
        int copied = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int sx = srcX + u, sz = srcZ + v;
                int edge = Math.min(Math.min(u - HX0, HX1 - u), Math.min(v - HZ0, HZ1 - v));
                double ragged = edge + 14 * (Terrain.fbm(u, v, 40, 91) - 0.5) * 2;
                double w = Terrain.smooth((ragged - 10) / (double) (BAND - 10));
                if (w <= 0.01) { // the outer band keeps the new world as it is
                    int nh = (int) Math.round(Terrain.naturalHeight(u, v));
                    Terrain.H[i][j] = nh;
                    Canvas.setGround(u, v, Math.max(nh, Canvas.SEA));
                    continue;
                }
                int oldG = terrain(src, sx, sz);
                if (w > 0.999) {
                    // from a little below the ground to the top: buildings keep interiors, cellars and foundations;
                    // deeper down the new world keeps its own bedrock and caves
                    int from = Math.min(Math.min(oldG, src.surface(sx, sz) - 64) - 12, deepestBuilt(src, sx, sz) - 2);
                    for (int y = Math.max(Canvas.MINY, from); y <= Canvas.MAXY; y++) {
                        String s = src.state(sx, y + 64, sz);
                        Canvas.set(u, y, v, s == null ? "air" : s);
                    }
                    Terrain.H[i][j] = oldG;
                    Terrain.WEIGHT[i][j] = 1;
                    Canvas.setGround(u, v, oldG);
                    copied++;
                    continue;
                }
                double target = Terrain.naturalHeight(u, v);
                int h = (int) Math.round(w * oldG + (1 - w) * target);
                Terrain.H[i][j] = h;
                Terrain.WEIGHT[i][j] = w;
                Canvas.fill(u, Canvas.MINY, v, u, h - 4, v, "stone");
                if (h < Canvas.SEA) {
                    Canvas.fill(u, h - 3, v, u, h - 1, v, "stone");
                    Canvas.set(u, h, v, Terrain.noise(u, v, 5, 3) < 0.6 ? "sand" : "gravel");
                    Canvas.fill(u, h + 1, v, u, Canvas.SEA, v, "water");
                    Canvas.setGround(u, v, Canvas.SEA);
                } else {
                    Canvas.fill(u, h - 3, v, u, h - 1, v, "dirt");
                    Canvas.set(u, h, v, Terrain.fbm(u, v, 9, 92) > 0.72 ? "coarse_dirt" : "grass_block");
                    Canvas.fill(u, h + 1, v, u, h + 40, v, "air");
                    Canvas.setGround(u, v, h);
                }
            }
        System.out.println("transplant: " + copied + " columns copied block for block");
        Forest.grow((u, v) -> Terrain.w(u, v) < 0.9 && Terrain.w(u, v) > 0.1 ? 0.0035 : 0, 999, (u, v) -> Terrain.w(u, v) >= 0.9 || Terrain.w(u, v) <= 0.1);
    }

    static boolean builtColumn(WorldReader src, int x, int z) {
        int top = src.surface(x, z);
        if (top == WorldReader.MISSING) return false;
        int g = src.ground(x, z);
        if (g == WorldReader.MISSING) g = top;
        int from = Math.max(-64, g - 8);
        for (int y = from; y <= top; y++) {
            String b = src.block(x, y, z);
            if (b != null && !OldTown.natural(b)) return true;
        }
        return false;
    }

    /** Puts original hub builds back (church, shops, stalls, castle) without touching the cleared district lots. */
    static void restoreBuilt(WorldReader src, int srcX, int srcZ) {
        Canvas.overlay();
        int restored = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int sx = srcX + u, sz = srcZ + v;
                if (!builtColumn(src, sx, sz)) continue;
                int oldG = terrain(src, sx, sz);
                int from = Math.min(Math.min(oldG, src.surface(sx, sz) - 64) - 12, deepestBuilt(src, sx, sz) - 2);
                for (int y = Math.max(Canvas.MINY, from); y <= Canvas.MAXY; y++) {
                    String s = src.state(sx, y + 64, sz);
                    Canvas.set(u, y, v, s == null ? "air" : s);
                }
                restored++;
            }
        System.out.println("hub-restore: " + restored + " original columns");
        // soften the band: grass, flowers and a few trees so the seam disappears into the new landscape
        Forest.grow((u, v) -> Terrain.w(u, v) < 0.9 && Terrain.w(u, v) > 0.1 ? 0.0035 : 0, 999, (u, v) -> Terrain.w(u, v) >= 0.9 || Terrain.w(u, v) <= 0.1);
    }

    static boolean customTreeColumn(WorldReader src, int x, int z) {
        int top = src.surface(x, z);
        if (top == WorldReader.MISSING) return false;
        int g = src.ground(x, z);
        if (g == WorldReader.MISSING) g = top;
        for (int y = g; y <= top; y++) {
            String b = src.block(x, y, z);
            if (b != null && (b.equals("spruce_wood") || b.equals("stripped_spruce_wood"))) return true;
        }
        return false;
    }

    /**
     * The old custom hub as a coastal town: every original column (church, wall, bridge, towers, shop, tents,
     * colosseum, crane, lumberjack, custom trees) plus solid ground underneath, ocean on the east. No generated cottages.
     */
    static void community(WorldReader src, int srcX, int srcZ) {
        Canvas.overlay();
        int copied = 0, trees = 0;
        int hubMaxU = 250;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                boolean inHub = u <= hubMaxU;
                int sx = srcX + u, sz = srcZ + v;
                if (inHub) {
                    int oldG = terrain(src, sx, sz);
                    int from = Math.min(Math.min(oldG, src.surface(sx, sz) - 64) - 12, deepestBuilt(src, sx, sz) - 2);
                    from = Math.max(Canvas.MINY + 5, from);
                    Canvas.fill(u, Canvas.MINY, v, u, from - 1, v, "stone");
                    int dirtFrom = Math.max(Canvas.MINY, from - 4);
                    Canvas.fill(u, dirtFrom, v, u, from - 1, v, "dirt");
                    for (int y = from; y <= Canvas.MAXY; y++) {
                        String s = src.state(sx, y + 64, sz);
                        Canvas.set(u, y, v, s == null ? "air" : s);
                    }
                    Terrain.H[i][j] = oldG;
                    Terrain.WEIGHT[i][j] = 1;
                    Canvas.setGround(u, v, oldG);
                    copied++;
                    if (customTreeColumn(src, sx, sz)) trees++;
                    continue;
                }
                double t = Terrain.smooth((u - hubMaxU) / 55.0);
                int shore = 6;
                int h = (int) Math.round((1 - t) * shore + t * (Canvas.SEA - 3));
                Canvas.fill(u, Canvas.MINY, v, u, h - 3, v, "stone");
                if (h < Canvas.SEA) {
                    Canvas.fill(u, h - 2, v, u, h, v, "sand");
                    Canvas.fill(u, h + 1, v, u, Canvas.SEA, v, "water");
                    Terrain.H[i][j] = Canvas.SEA;
                    Canvas.setGround(u, v, Canvas.SEA);
                } else {
                    Canvas.fill(u, h - 2, v, u, h - 1, v, t > 0.45 ? "sand" : "dirt");
                    Canvas.set(u, h, v, t > 0.35 ? "sand" : "grass_block");
                    Canvas.fill(u, h + 1, v, u, Canvas.MAXY, v, "air");
                    Terrain.H[i][j] = h;
                    Canvas.setGround(u, v, h);
                }
            }
        System.out.println("community: " + copied + " hub columns (" + trees + " custom trees), ocean east of u=" + hubMaxU);
    }

    static final java.util.Set<String> SKIP = java.util.Set.of("barrier", "structure_void", "light");

    /**
     * Copies man-made columns (plus a small ground pad) from {@code src} so a landmark can be placed at the canvas origin.
     * {@code srcX,srcZ} is the source world point that maps to relative (0, 0).
     */
    static void copyBuilt(WorldReader src, int srcX, int srcZ) {
        Canvas.overlay();
        int sx0 = srcX + Canvas.MINX, sx1 = srcX + Canvas.MAXX, sz0 = srcZ + Canvas.MINZ, sz1 = srcZ + Canvas.MAXZ;
        int w = sx1 - sx0 + 1, d = sz1 - sz0 + 1;
        boolean[][] keep = new boolean[w][d];
        for (int sx = sx0; sx <= sx1; sx++)
            for (int sz = sz0; sz <= sz1; sz++) {
                if (!builtColumn(src, sx, sz)) continue;
                int i = sx - sx0, j = sz - sz0;
                for (int a = -8; a <= 8; a++)
                    for (int b = -8; b <= 8; b++) {
                        int ii = i + a, jj = j + b;
                        if (ii >= 0 && jj >= 0 && ii < w && jj < d) keep[ii][jj] = true;
                    }
            }
        int copied = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!keep[i][j]) continue;
                int sx = srcX + u, sz = srcZ + v;
                int oldG = terrain(src, sx, sz);
                int top = src.surface(sx, sz);
                if (top == WorldReader.MISSING) continue;
                int from = Math.min(oldG - 8, deepestBuilt(src, sx, sz) - 2);
                from = Math.max(Canvas.MINY + 4, from);
                Canvas.fill(u, Canvas.MINY, v, u, from - 1, v, "stone");
                int dirtFrom = Math.max(Canvas.MINY, from - 4);
                Canvas.fill(u, dirtFrom, v, u, from - 1, v, "dirt");
                for (int y = from; y <= Canvas.MAXY; y++) {
                    String s = src.state(sx, y + 64, sz);
                    if (s == null) s = "air";
                    String base = s.indexOf('[') < 0 ? s : s.substring(0, s.indexOf('['));
                    if (SKIP.contains(base)) s = "air";
                    Canvas.set(u, y, v, s);
                }
                Terrain.H[i][j] = oldG;
                Canvas.setGround(u, v, oldG);
                copied++;
            }
        System.out.println("copy: " + copied + " columns from " + srcX + "," + srcZ);
    }

    /** Flattens the canvas to a grass pad so a previous oversized paste can be removed. */
    static void wipeFlat() {
        Canvas.overlay();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                Canvas.fill(u, Canvas.MINY, v, u, Canvas.MAXY, v, "air");
                Canvas.fill(u, Canvas.MINY, v, u, -3, v, "stone");
                Canvas.fill(u, -2, v, u, -1, v, "dirt");
                Canvas.set(u, 0, v, "grass_block");
            }
        System.out.println("wipe: " + Canvas.SX * Canvas.SZ + " columns flattened");
    }

    /**
     * Copies only the connected landmark around {@code srcX,srcZ}, not nearby unrelated builds.
     */
    static void copyConnected(WorldReader src, int srcX, int srcZ, String kind) {
        Canvas.overlay();
        int sx0 = srcX + Canvas.MINX, sx1 = srcX + Canvas.MAXX, sz0 = srcZ + Canvas.MINZ, sz1 = srcZ + Canvas.MAXZ;
        int w = sx1 - sx0 + 1, d = sz1 - sz0 + 1;
        boolean[][] match = new boolean[w][d];
        for (int sx = sx0; sx <= sx1; sx++)
            for (int sz = sz0; sz <= sz1; sz++)
                match[sx - sx0][sz - sz0] = landmarkColumn(src, sx, sz, kind);
        boolean[][] linked = new boolean[w][d];
        for (int i = 0; i < w; i++)
            for (int j = 0; j < d; j++) {
                if (!match[i][j]) continue;
                for (int a = -1; a <= 1; a++)
                    for (int b = -1; b <= 1; b++) {
                        int ii = i + a, jj = j + b;
                        if (ii >= 0 && jj >= 0 && ii < w && jj < d) linked[ii][jj] = true;
                    }
            }
        boolean[][] keep = new boolean[w][d];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        int i0 = -Canvas.MINX, j0 = -Canvas.MINZ;
        for (int i = 0; i < w; i++)
            for (int j = 0; j < d; j++) {
                if (!linked[i][j]) continue;
                if (Math.abs(i - i0) > 28 || Math.abs(j - j0) > 28) continue;
                keep[i][j] = true;
                q.add(new int[] {i, j});
            }
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            for (int[] n : nb) {
                int ii = p[0] + n[0], jj = p[1] + n[1];
                if (ii < 0 || jj < 0 || ii >= w || jj >= d || keep[ii][jj] || !linked[ii][jj]) continue;
                keep[ii][jj] = true;
                q.add(new int[] {ii, jj});
            }
        }
        boolean[][] grown = new boolean[w][d];
        for (int i = 0; i < w; i++)
            for (int j = 0; j < d; j++) {
                if (!keep[i][j]) continue;
                for (int a = -2; a <= 2; a++)
                    for (int b = -2; b <= 2; b++) {
                        int ii = i + a, jj = j + b;
                        if (ii >= 0 && jj >= 0 && ii < w && jj < d) grown[ii][jj] = true;
                    }
            }
        int copied = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!grown[i][j]) continue;
                int sx = srcX + u, sz = srcZ + v;
                int oldG = terrain(src, sx, sz);
                int top = src.surface(sx, sz);
                if (top == WorldReader.MISSING) continue;
                int from = Math.min(oldG - 6, deepestBuilt(src, sx, sz) - 2);
                from = Math.max(Canvas.MINY + 4, from);
                Canvas.fill(u, Canvas.MINY, v, u, from - 1, v, "stone");
                int dirtFrom = Math.max(Canvas.MINY, from - 3);
                Canvas.fill(u, dirtFrom, v, u, from - 1, v, "dirt");
                for (int y = from; y <= Canvas.MAXY; y++) {
                    String s = src.state(sx, y + 64, sz);
                    if (s == null) s = "air";
                    String base = s.indexOf('[') < 0 ? s : s.substring(0, s.indexOf('['));
                    if (SKIP.contains(base)) s = "air";
                    Canvas.set(u, y, v, s);
                }
                Terrain.H[i][j] = oldG;
                Canvas.setGround(u, v, oldG);
                copied++;
            }
        System.out.println("copy-connected " + kind + ": " + copied + " columns from " + srcX + "," + srcZ);
    }

    static boolean landmarkColumn(WorldReader src, int x, int z, String kind) {
        int top = src.surface(x, z);
        if (top == WorldReader.MISSING) return false;
        int n = 0;
        for (int y = Math.max(-64, top - 90); y <= top; y++) {
            String b = src.block(x, y, z);
            if (b == null) continue;
            boolean hit = switch (kind) {
                case "church" -> b.contains("oak_planks") || b.contains("oak_stairs") || b.contains("oak_slab")
                        || b.contains("oak_fence") || b.contains("oak_door") || b.contains("oak_trapdoor")
                        || b.contains("stone_brick") || b.contains("spruce_stairs") || b.contains("spruce_slab")
                        || b.contains("spruce_planks") || b.contains("glass") || b.contains("lantern")
                        || ((b.equals("cobblestone") || b.contains("cobblestone_")) && top >= 90);
                case "colosseum" -> b.contains("sandstone") || b.equals("bricks") || b.contains("brick_stairs")
                        || b.contains("brick_slab") || b.contains("brick_wall") || b.contains("birch_slab")
                        || b.contains("birch_stairs") || b.contains("birch_planks");
                case "bridge" -> (b.contains("stone_brick") || b.contains("oak_slab") || b.contains("oak_stairs")
                        || b.contains("oak_planks") || b.contains("oak_fence")) && top >= 85;
                default -> !OldTown.natural(b);
            };
            if (hit) n++;
        }
        return n >= 5;
    }

    /**
     * Makes the transplanted spawn hill sit in the 26.2 landscape: fills the hollow interior with stone, then
     * slopes the sheer ocean cliffs into a ragged coastal apron. Buildings are not overwritten.
     */
    static boolean FILL_ONLY = false;

    static void hillBlend(WorldReader w) {
        Canvas.overlay();
        int sx = Canvas.SX, sz = Canvas.SZ, r = 22;
        int[][] g = new int[sx][sz];
        boolean[][] land = new boolean[sx][sz];
        boolean[][] built = new boolean[sx][sz];
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                int x = Box.worldX + u, z = Box.worldZ + v;
                int gw = w.ground(x, z);
                int top = w.surface(x, z);
                if (gw == WorldReader.MISSING) gw = top;
                g[i][j] = gw == WorldReader.MISSING ? Canvas.SEA - 8 : gw - 64;
                String surf = top == WorldReader.MISSING ? "air" : w.block(x, top, z);
                boolean wet = surf != null && (surf.equals("water") || surf.contains("kelp") || surf.contains("seagrass"));
                land[i][j] = !wet && g[i][j] > Canvas.SEA;
                built[i][j] = builtColumn(w, x, z);
            }
        int filled = 0, sloped = 0;
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (!land[i][j]) continue;
                int x = Box.worldX + u, z = Box.worldZ + v;
                int cellar = deepestBuilt(w, x, z);
                // ground() stops at roofs and tree crowns; terrain() finds the soil under them
                int soil = FILL_ONLY ? Math.min(g[i][j], terrain(w, x, z)) : g[i][j];
                int start = cellar < 9000 ? Math.min(soil - 2, cellar - 2) : soil - 2;
                start = Math.min(start, Canvas.MAXY);
                boolean inPocket = false;
                int solidRun = 0;
                for (int y = start; y >= Canvas.MINY + 4; y--) {
                    String b = w.block(x, y + 64, z);
                    if (b == null) continue;
                    boolean hollow = b.equals("air") || b.equals("cave_air") || b.equals("water") || b.contains("kelp")
                            || b.contains("seagrass");
                    if (!hollow) {
                        if (FILL_ONLY && inPocket && y < -8 && ++solidRun >= 4) break;
                        continue;
                    }
                    inPocket = true;
                    solidRun = 0;
                    Canvas.set(u, y, v, y < g[i][j] - 5 ? "stone" : "dirt");
                    filled++;
                }
            }
        if (FILL_ONLY) {
            System.out.println("hill-fill: filled " + filled + " hollow cells");
            return;
        }
        int[][] nearH = new int[sx][sz];
        int[][] nearD = new int[sx][sz];
        for (int i = 0; i < sx; i++) java.util.Arrays.fill(nearD[i], Integer.MAX_VALUE);
        for (int i = 0; i < sx; i++)
            for (int j = 0; j < sz; j++) {
                if (!land[i][j] || built[i][j]) continue;
                for (int a = -r; a <= r; a++)
                    for (int b = -r; b <= r; b++) {
                        int ii = i + a, jj = j + b;
                        if (ii < 0 || jj < 0 || ii >= sx || jj >= sz) continue;
                        if (land[ii][jj]) continue;
                        int d2 = a * a + b * b;
                        if (d2 >= nearD[ii][jj]) continue;
                        nearD[ii][jj] = d2;
                        nearH[ii][jj] = g[i][j];
                    }
            }
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int i = u - Canvas.MINX, j = v - Canvas.MINZ;
                if (land[i][j] || built[i][j] || nearD[i][j] == Integer.MAX_VALUE) continue;
                double dist = Math.sqrt(nearD[i][j]);
                double ragged = 18 + 10 * (Terrain.fbm(u, v, 28, 11) - 0.5);
                if (dist > ragged) continue;
                double drop = 2.1 + 0.5 * Terrain.fbm(u + 40, v, 18, 3);
                int target = (int) Math.round(nearH[i][j] - dist * drop);
                int floor = Math.max(g[i][j], Canvas.MINY + 6);
                if (target <= floor) continue;
                target = Math.min(target, Canvas.MAXY - 2);
                Canvas.fill(u, Canvas.MINY, v, u, target - 4, v, "stone");
                if (target < Canvas.SEA) {
                    Canvas.fill(u, target - 3, v, u, target, v, Terrain.fbm(u, v, 9, 4) > 0.55 ? "gravel" : "sand");
                    Canvas.fill(u, target + 1, v, u, Canvas.SEA, v, "water");
                } else if (target <= Canvas.SEA + 2) {
                    Canvas.fill(u, target - 3, v, u, target - 1, v, "sand");
                    Canvas.set(u, target, v, "sand");
                } else {
                    Canvas.fill(u, target - 3, v, u, target - 1, v, "dirt");
                    Canvas.set(u, target, v, Terrain.fbm(u, v, 12, 8) > 0.78 ? "coarse_dirt" : "grass_block");
                    if (target < nearH[i][j] - 8 && Terrain.fbm(u, v, 7, 19) > 0.82)
                        Canvas.set(u, target, v, "stone");
                }
                sloped++;
            }
        System.out.println("hill-blend: filled " + filled + " hollow cells, sloped " + sloped + " coastal columns");
    }
}
