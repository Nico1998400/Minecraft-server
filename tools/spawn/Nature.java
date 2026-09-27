import java.util.ArrayList;
import java.util.List;

/** Trees, bushes, flowers and boulders; invisible light so no monsters spawn in town. */
final class Nature {

    static boolean soil(String b) {
        return b.equals("grass_block") || b.equals("podzol") || b.equals("coarse_dirt") || b.equals("moss_block");
    }

    static void grow() {
        trees();
        undergrowth();
        boulders();
        lights();
    }

    /** How wooded a spot is: dense on the wild slopes and the mountain, sparse in the town. */
    static double density(int u, int v) {
        if (Terrain.town(u, v)) return 0.012;
        if (Terrain.dq(u, v) > Terrain.PLATEAU_DQ - 4 && Terrain.mountainSector(u, v)) return 0.02;
        return 0.06;
    }

    static void trees() {
        List<int[]> placed = new ArrayList<>();
        for (int attempt = 0; attempt < 60000; attempt++) {
            int u = Canvas.MINX + Canvas.rint(Canvas.SX), v = Canvas.MINZ + Canvas.rint(Canvas.SZ);
            if (Canvas.rnd() > density(u, v) * 12) continue;
            int g = Canvas.ground(u, v);
            if (g <= Canvas.SEA || !soil(Canvas.get(u, g, v)) || Canvas.used(u, v) || Canvas.street(u, v)) continue;
            boolean clear = true;
            for (int du = -2; du <= 2 && clear; du++)
                for (int dv = -2; dv <= 2 && clear; dv++) if (Canvas.street(u + du, v + dv)) clear = false;
            if (!clear) continue;
            for (int[] p : placed) if (Math.abs(p[0] - u) + Math.abs(p[1] - v) < 5) clear = false;
            if (!clear) continue;
            double r = Canvas.rnd();
            boolean ok;
            if (r < 0.08 && !Terrain.town(u, v)) ok = bigSpruce(u, v, g);
            else if (r < 0.62) ok = spruce(u, v, g, 7 + Canvas.rint(7));
            else if (r < 0.86) ok = birch(u, v, g, 6 + Canvas.rint(4));
            else ok = oak(u, v, g, 5 + Canvas.rint(3));
            if (ok) placed.add(new int[] {u, v});
        }
        System.out.println("trees: " + placed.size());
    }

    static boolean airColumn(int u, int v, int y0, int y1, int r) {
        for (int y = y0; y <= y1; y++)
            for (int du = -r; du <= r; du++)
                for (int dv = -r; dv <= r; dv++) if (!Canvas.isAir(u + du, y, v + dv) && !B.isPlant(B.base(Canvas.get(u + du, y, v + dv)))) return false;
        return true;
    }

    static boolean spruce(int u, int v, int g, int h) {
        if (!airColumn(u, v, g + 1, g + h + 1, 1)) return false;
        for (int y = g + 1; y < g + h; y++) Canvas.set(u, y, v, "spruce_log[axis=y]");
        for (int y = g + 3; y <= g + h; y++) {
            int k = g + h - y;
            int r = Math.min(3, (int) Math.round(k * 0.45 + ((y & 1) == 0 ? 0.7 : 0)));
            for (int du = -r; du <= r; du++)
                for (int dv = -r; dv <= r; dv++) {
                    if (Math.abs(du) + Math.abs(dv) > r + 0.5 || (du == 0 && dv == 0 && y < g + h)) continue;
                    Canvas.setIfAir(u + du, y, v + dv, "spruce_leaves[persistent=true]");
                }
        }
        Canvas.set(u, g + h, v, "spruce_leaves[persistent=true]");
        Canvas.setIfAir(u, g + h + 1, v, "spruce_leaves[persistent=true]");
        Canvas.set(u, g, v, "podzol");
        return true;
    }

    static boolean bigSpruce(int u, int v, int g) {
        int h = 18 + Canvas.rint(9);
        if (!airColumn(u, v, g + 1, g + h, 2)) return false;
        for (int y = g - 1; y < g + h; y++)
            for (int du = 0; du <= 1; du++) for (int dv = 0; dv <= 1; dv++) Canvas.set(u + du, y, v + dv, "spruce_log[axis=y]");
        for (int y = g + 5; y <= g + h + 1; y++) {
            int k = g + h - y;
            int r = Math.min(6, (int) Math.round(k * 0.33 + ((y % 3) == 0 ? 1.2 : 0)));
            for (int du = -r; du <= r + 1; du++)
                for (int dv = -r; dv <= r + 1; dv++) {
                    double d = Math.hypot(du - 0.5, dv - 0.5);
                    if (d > r + 0.6) continue;
                    Canvas.setIfAir(u + du, y, v + dv, "spruce_leaves[persistent=true]");
                }
        }
        for (int du = -2; du <= 3; du++) for (int dv = -2; dv <= 3; dv++) if (Canvas.rnd() < 0.6) Canvas.set(u + du, Canvas.ground(u + du, v + dv), v + dv, "podzol");
        Canvas.use(u - 1, v - 1, u + 2, v + 2);
        return true;
    }

    static boolean birch(int u, int v, int g, int h) {
        if (!airColumn(u, v, g + 1, g + h + 1, 2)) return false;
        for (int y = g + 1; y < g + h; y++) Canvas.set(u, y, v, "birch_log[axis=y]");
        for (int du = -3; du <= 3; du++)
            for (int dy = -2; dy <= 2; dy++)
                for (int dv = -3; dv <= 3; dv++) {
                    double d = Math.sqrt(du * du + dy * dy * 1.8 + dv * dv);
                    if (d > 2.6 + Canvas.rnd() * 0.6) continue;
                    Canvas.setIfAir(u + du, g + h - 1 + dy, v + dv, "birch_leaves[persistent=true]");
                }
        return true;
    }

    static boolean oak(int u, int v, int g, int h) {
        if (!airColumn(u, v, g + 1, g + h + 2, 2)) return false;
        for (int y = g + 1; y < g + h; y++) Canvas.set(u, y, v, "oak_log[axis=y]");
        for (int k = 0; k < 3; k++) {
            int bu = u + Canvas.rint(5) - 2, bv = v + Canvas.rint(5) - 2, by = g + h - 1 + Canvas.rint(2);
            for (int du = -3; du <= 3; du++)
                for (int dy = -2; dy <= 2; dy++)
                    for (int dv = -3; dv <= 3; dv++) {
                        double d = Math.sqrt(du * du + dy * dy * 2 + dv * dv);
                        if (d > 3 + Canvas.rnd() * 0.5) continue;
                        Canvas.setIfAir(bu + du, by + dy, bv + dv, "oak_leaves[persistent=true]");
                    }
        }
        return true;
    }

    /** Grass, ferns, flowers and bushes on every free patch of soil. */
    static void undergrowth() {
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++) {
                int g = Canvas.ground(u, v);
                if (g <= Canvas.SEA || Canvas.street(u, v)) continue;
                String top = Canvas.get(u, g, v);
                if (!soil(top) || !Canvas.isAir(u, g + 1, v)) continue;
                double r = Canvas.rnd();
                double lush = Terrain.fbm(u, v, 9, 55);
                if (r < 0.18 + lush * 0.2) Canvas.set(u, g + 1, v, top.equals("podzol") ? "fern" : "short_grass");
                else if (r < 0.26 + lush * 0.2) Canvas.set(u, g + 1, v, B.FLOWERS.get(Math.floorMod((int) (Terrain.noise(u, v, 6, 3) * 40), B.FLOWERS.size())));
                else if (r < 0.28 + lush * 0.2 && Canvas.isAir(u, g + 2, v)) {
                    Canvas.set(u, g + 1, v, "tall_grass[half=lower]");
                    Canvas.set(u, g + 2, v, "tall_grass[half=upper]");
                } else if (r < 0.30 + lush * 0.2) Canvas.set(u, g + 1, v, Canvas.pick("bush", "fern", "sweet_berry_bush[age=3]", "firefly_bush"));
                else if (r < 0.31 + lush * 0.2 && !Terrain.town(u, v)) Canvas.set(u, g + 1, v, Canvas.pick("azalea_leaves[persistent=true]", "oak_leaves[persistent=true]"));
                else if (r < 0.33 + lush * 0.2) Canvas.set(u, g + 1, v, "pink_petals[flower_amount=4]");
            }
    }

    static void boulders() {
        for (int i = 0; i < 160; i++) {
            int u = Canvas.MINX + Canvas.rint(Canvas.SX), v = Canvas.MINZ + Canvas.rint(Canvas.SZ);
            int g = Canvas.ground(u, v);
            if (g <= Canvas.SEA || Canvas.used(u, v) || Canvas.street(u, v) || Terrain.town(u, v) && Canvas.rnd() < 0.7) continue;
            int r = 1 + Canvas.rint(2);
            for (int du = -r; du <= r; du++)
                for (int dv = -r; dv <= r; dv++)
                    for (int dy = 0; dy <= r; dy++) {
                        if (Math.sqrt(du * du + dv * dv + dy * dy * 1.6) > r + 0.3) continue;
                        int y = Canvas.ground(u + du, v + dv) + dy;
                        if (Canvas.street(u + du, v + dv)) continue;
                        Canvas.set(u + du, y, v + dv, Canvas.pick("mossy_cobblestone", "andesite", "stone", "cobblestone", "tuff", "mossy_cobblestone"));
                    }
            Canvas.setIfAir(u, Canvas.ground(u, v) + r + 1, v, "moss_carpet");
        }
    }

    /** Invisible light blocks on a grid: the town stays monster-free at night. */
    static void lights() {
        for (int u = Canvas.MINX + 3; u <= Canvas.MAXX; u += 8)
            for (int v = Canvas.MINZ + 3; v <= Canvas.MAXZ; v += 8) {
                int g = Canvas.ground(u, v);
                if (g < Canvas.SEA) {
                    if (Canvas.get(u, Canvas.SEA - 2, v).equals("water")) Canvas.set(u, Canvas.SEA - 2, v, "light[level=15,waterlogged=true]");
                    continue;
                }
                for (int y = g + 1; y <= g + 3; y++)
                    if (Canvas.isAir(u, y, v)) {
                        Canvas.set(u, y, v, "light[level=15]");
                        break;
                    }
                if (Canvas.isAir(u, g + 16, v)) Canvas.set(u, g + 16, v, "light[level=12]");
            }
    }
}
