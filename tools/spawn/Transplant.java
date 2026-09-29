/**
 * Moves a whole area (terrain and builds, block for block with all block states) from a source world into the canvas,
 * blending its outer band into the natural terrain of the target world so it sits in the new landscape.
 */
final class Transplant {

    static final int BAND = 44;

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
                int edge = Math.min(Math.min(u - Canvas.MINX, Canvas.MAXX - u), Math.min(v - Canvas.MINZ, Canvas.MAXZ - v));
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
        // soften the band: grass, flowers and a few trees so the seam disappears into the new landscape
        Forest.grow((u, v) -> Terrain.w(u, v) < 0.9 && Terrain.w(u, v) > 0.1 ? 0.0035 : 0, 999, (u, v) -> Terrain.w(u, v) >= 0.9 || Terrain.w(u, v) <= 0.1);
    }
}
