import java.util.function.BiPredicate;

/**
 * Farmland: small terraced fields levelled into the slope, crops in rows with irrigation ditches, fences with a gate,
 * hay and a scarecrow. Laid after the terrain is painted; trees keep out of claimed ground.
 */
final class Fields {

    static final java.util.Random R = Canvas.TERRAIN_RNG;
    static final String[] CROPS = {"wheat[age=7]", "wheat[age=6]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]", "wheat[age=7]"};

    /** Tries to lay up to {@code count} fields in the rectangle; {@code allowed} decides which cells may be farmed. */
    static int lay(int u0, int v0, int u1, int v1, int count, BiPredicate<Integer, Integer> allowed) {
        int laid = 0;
        for (int attempt = 0; attempt < count * 150 && laid < count; attempt++) {
            boolean alongU = R.nextBoolean();
            int w = 6 + R.nextInt(4), l = 9 + R.nextInt(7);
            int fu = alongU ? l : w, fv = alongU ? w : l;
            int u = u0 + R.nextInt(Math.max(1, u1 - u0 - fu)), v = v0 + R.nextInt(Math.max(1, v1 - v0 - fv));
            if (!fits(u, v, fu, fv, allowed)) continue;
            field(u, v, fu, fv, alongU);
            laid++;
        }
        return laid;
    }

    static boolean fits(int u, int v, int fu, int fv, BiPredicate<Integer, Integer> allowed) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = u - 1; x <= u + fu; x++)
            for (int z = v - 1; z <= v + fv; z++) {
                if (!Canvas.inXZ(x, z) || !allowed.test(x, z) || Canvas.used(x, z)) return false;
                int g = Canvas.ground(x, z);
                if (g <= Canvas.SEA + 1) return false;
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
        return max - min <= 6;
    }

    static void field(int u, int v, int fu, int fv, boolean alongU) {
        // level the field at its median height: a small terrace with a stone edge where it stands proud
        int[] hs = new int[fu * fv];
        int k = 0;
        for (int x = 0; x < fu; x++) for (int z = 0; z < fv; z++) hs[k++] = Canvas.ground(u + x, v + z);
        java.util.Arrays.sort(hs);
        int y = hs[hs.length / 2];
        String crop = CROPS[R.nextInt(CROPS.length)];
        for (int x = -1; x <= fu; x++)
            for (int z = -1; z <= fv; z++) {
                int cu = u + x, cv = v + z;
                int g = Canvas.ground(cu, cv);
                boolean edge = x == -1 || z == -1 || x == fu || z == fv;
                for (int yy = Math.min(g, y) - 2; yy <= y; yy++)
                    if (!Canvas.isSolid(cu, yy, cv) || yy > g) Canvas.set(cu, yy, cv, edge && yy < y ? Terrain.masonry() : "dirt");
                Canvas.fill(cu, y + 1, cv, cu, Math.max(y + 1, g + 3), cv, "air");
                int row = alongU ? z : x;
                if (edge) {
                    Canvas.set(cu, y, cv, "coarse_dirt");
                    if (!(x == fu / 2 && z == -1) && !(z == fv / 2 && x == -1)) Canvas.set(cu, y + 1, cv, "spruce_fence");
                    else Canvas.set(cu, y + 1, cv, "spruce_fence_gate[facing=" + (z == -1 ? "north" : "west") + ",open=false]");
                } else if (row % 5 == 2) {
                    Canvas.set(cu, y, cv, "water");            // irrigation ditch
                    if (R.nextDouble() < 0.1) Canvas.set(cu, y + 1, cv, "lily_pad");
                } else {
                    Canvas.set(cu, y, cv, "farmland[moisture=7]");
                    Canvas.set(cu, y + 1, cv, crop);
                }
                Canvas.setGround(cu, cv, y);
                Terrain.H[cu - Canvas.MINX][cv - Canvas.MINZ] = y;
                Canvas.use(cu, cv, cu, cv);
            }
        // hay stack and a scarecrow
        Canvas.set(u - 2, Canvas.ground(u - 2, v) + 1, v, "hay_block");
        Canvas.set(u - 2, Canvas.ground(u - 2, v) + 2, v, "hay_block");
        Canvas.set(u - 2, Canvas.ground(u - 2, v + 1) + 1, v + 1, "hay_block");
        int su = u + fu / 2, sv = v + fv / 2;
        if (Canvas.get(su, y + 1, sv).contains("wheat") || Canvas.get(su, y + 1, sv).contains("carrots")) {
            Canvas.set(su, y + 1, sv, "spruce_fence");
            Canvas.set(su, y + 2, sv, "hay_block");
            Canvas.set(su, y + 3, sv, "carved_pumpkin[facing=south]");
            Canvas.set(su - 1, y + 2, sv, "spruce_trapdoor[facing=west,half=top,open=true]");
            Canvas.set(su + 1, y + 2, sv, "spruce_trapdoor[facing=east,half=top,open=true]");
        }
    }
}
