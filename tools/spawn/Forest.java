import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleBiFunction;

/**
 * Forests with character: density that follows the land (thick in valleys, thin on spurs), clearings, a treeline with
 * stunted spruce, fallen trunks, stumps, mushrooms, ferns and berry bushes, boulders; alpine meadows above the trees.
 */
final class Forest {

    static java.util.Random R = Canvas.TERRAIN_RNG;

    static boolean soil(String b) {
        return b.equals("grass_block") || b.equals("podzol") || b.equals("coarse_dirt") || b.equals("moss_block");
    }

    /**
     * Grows the forest. {@code density(u, v)} gives trees per cell (0..~0.08); {@code treeline} is the height above
     * which only stunted trees grow; {@code blocked(u, v)} keeps cells free (paths, buildings).
     */
    static void grow(ToDoubleBiFunction<Integer, Integer> density, int treeline, java.util.function.BiPredicate<Integer, Integer> blocked) {
        List<int[]> placed = new ArrayList<>();
        int attempts = Canvas.SX * Canvas.SZ / 3;
        for (int n = 0; n < attempts; n++) {
            int u = Canvas.MINX + R.nextInt(Canvas.SX), v = Canvas.MINZ + R.nextInt(Canvas.SZ);
            double d = density.applyAsDouble(u, v);
            // clearings and thickets: large-scale noise modulates density
            double patch = Geology.fbm(u, v, 26, 3, 801);
            d *= patch < 0.34 ? 0.08 : patch > 0.62 ? 1.7 : 1;
            if (R.nextDouble() > d * 12) continue;
            int g = Canvas.ground(u, v);
            if (g <= Canvas.SEA || !soil(Canvas.get(u, g, v)) || blocked.test(u, v) || Canvas.used(u, v)) continue;
            boolean clear = true;
            for (int[] p : placed) if (Math.abs(p[0] - u) + Math.abs(p[1] - v) < 4) {
                clear = false;
                break;
            }
            if (!clear) continue;
            boolean ok;
            if (g > treeline + 6) continue;
            if (g > treeline) ok = Nature.spruce(u, v, g, 3 + R.nextInt(3));
            else {
                double r = R.nextDouble();
                double birchy = Geology.noise(u, v, 40, 802);
                if (r < 0.07 && g < treeline - 10) ok = Nature.bigSpruce(u, v, g);
                else if (r < 0.6 + (0.5 - birchy) * 0.4) ok = Nature.spruce(u, v, g, 7 + R.nextInt(8));
                else if (r < 0.9) ok = Nature.birch(u, v, g, 6 + R.nextInt(4));
                else ok = Nature.oak(u, v, g, 5 + R.nextInt(3));
            }
            if (ok) placed.add(new int[] {u, v});
        }
        System.out.println("forest: " + placed.size() + " trees");
        floor(density, treeline, blocked);
    }

    /** The forest floor and the meadows: fallen trunks, stumps, mushrooms, ferns, berries, flowers, boulders. */
    static void floor(ToDoubleBiFunction<Integer, Integer> density, int treeline, java.util.function.BiPredicate<Integer, Integer> blocked) {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int g = Canvas.ground(u, v);
                if (g <= Canvas.SEA || blocked.test(u, v)) continue;
                String top = Canvas.get(u, g, v);
                if (!soil(top) || !Canvas.isAir(u, g + 1, v)) continue;
                double d = density.applyAsDouble(u, v);
                double r = R.nextDouble();
                boolean alpine = g > treeline;
                if (alpine) {
                    if (r < 0.22) Canvas.set(u, g + 1, v, "short_grass");
                    else if (r < 0.30) Canvas.set(u, g + 1, v, Canvas.pick("cornflower", "oxeye_daisy", "dandelion", "azure_bluet", "allium"));
                    else if (r < 0.32) Canvas.set(u, g + 1, v, "short_dry_grass");
                    continue;
                }
                if (d > 0.03) {
                    if (r < 0.14) Canvas.set(u, g + 1, v, "fern");
                    else if (r < 0.20) Canvas.set(u, g + 1, v, "short_grass");
                    else if (r < 0.225) Canvas.set(u, g + 1, v, "sweet_berry_bush[age=3]");
                    else if (r < 0.24) Canvas.set(u, g + 1, v, Canvas.pick("brown_mushroom", "red_mushroom"));
                    else if (r < 0.25 && Canvas.isAir(u, g + 2, v)) {
                        Canvas.set(u, g + 1, v, "large_fern[half=lower]");
                        Canvas.set(u, g + 2, v, "large_fern[half=upper]");
                    } else if (r < 0.262) Canvas.set(u, g + 1, v, "leaf_litter");
                    else if (r < 0.264) fallenLog(u, v, g);
                    else if (r < 0.266) stump(u, g, v);
                } else {
                    if (r < 0.24) Canvas.set(u, g + 1, v, "short_grass");
                    else if (r < 0.30) Canvas.set(u, g + 1, v, B.FLOWERS.get(R.nextInt(B.FLOWERS.size())));
                    else if (r < 0.315 && Canvas.isAir(u, g + 2, v)) {
                        Canvas.set(u, g + 1, v, "tall_grass[half=lower]");
                        Canvas.set(u, g + 2, v, "tall_grass[half=upper]");
                    } else if (r < 0.32) Canvas.set(u, g + 1, v, "bush");
                }
            }
        boulders(blocked);
    }

    static void fallenLog(int u, int v, int g) {
        boolean alongX = R.nextBoolean();
        int len = 4 + R.nextInt(4);
        for (int k = 0; k < len; k++) {
            int x = u + (alongX ? k : 0), z = v + (alongX ? 0 : k);
            if (!Canvas.isAir(x, g + 1, z) || Math.abs(Canvas.ground(x, z) - g) > 1) return;
        }
        for (int k = 0; k < len; k++) {
            int x = u + (alongX ? k : 0), z = v + (alongX ? 0 : k);
            Canvas.set(x, g + 1, z, (R.nextBoolean() ? "spruce_log" : "birch_log") + "[axis=" + (alongX ? "x" : "z") + "]");
            if (R.nextDouble() < 0.4) Canvas.setIfAir(x, g + 2, z, R.nextBoolean() ? "moss_carpet" : "brown_mushroom");
        }
    }

    static void stump(int u, int g, int v) {
        Canvas.set(u, g + 1, v, "spruce_log[axis=y]");
        Canvas.setIfAir(u, g + 2, v, R.nextBoolean() ? "moss_carpet" : "red_mushroom");
    }

    static void boulders(java.util.function.BiPredicate<Integer, Integer> blocked) {
        int n = Canvas.SX * Canvas.SZ / 700;
        for (int k = 0; k < n; k++) {
            int u = Canvas.MINX + R.nextInt(Canvas.SX), v = Canvas.MINZ + R.nextInt(Canvas.SZ);
            int g = Canvas.ground(u, v);
            if (g <= Canvas.SEA || blocked.test(u, v) || Canvas.used(u, v)) continue;
            int r = 1 + R.nextInt(3);
            for (int du = -r; du <= r; du++)
                for (int dv = -r; dv <= r; dv++)
                    for (int dy = -1; dy <= r; dy++) {
                        if (Math.sqrt(du * du + dv * dv + dy * dy * 1.7) > r + 0.3) continue;
                        if (blocked.test(u + du, v + dv)) continue;
                        int y = Canvas.ground(u + du, v + dv) + dy;
                        Canvas.set(u + du, y, v + dv, Canvas.pick("mossy_cobblestone", "andesite", "stone", "cobblestone", "tuff", "mossy_cobblestone", "granite"));
                    }
            Canvas.setIfAir(u, Canvas.ground(u, v) + r + 1, v, "moss_carpet");
        }
    }
}
