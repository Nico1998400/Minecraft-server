import java.nio.file.Path;
import java.util.List;

/**
 * NORDIA spawn: "Nordhamn", a Viking-age Nordic harbour town built as a vanilla data pack.
 *
 * <pre>
 * java tools/spawn/Nordhamn.java [--world regionDir] [--blocks blocks.txt] [--out dir] [--renders all|quick|none]
 * In game: /execute positioned -572 64 378 run function nordia:spawn/build   (dev world centre)
 * </pre>
 *
 * Files: {@link Canvas} voxel model + compiler, {@link B} block helpers/frames, {@link Terrain} landscape,
 * {@link Town} layout, {@link Render} previews, {@link WorldReader} natural terrain, plus the building modules.
 */
public final class Nordhamn {

    /** World position of the design centre in the dev world. */
    static final int WORLD_X = -572, WORLD_Z = 378;

    public static void main(String[] args) throws Exception {
        Path out = Path.of("tools/spawn/build"), blocks = null, world = null, built = null;
        String renders = "quick", region = "spawn";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world" -> world = Path.of(args[++i]);
                case "--blocks" -> blocks = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--renders" -> renders = args[++i];
                case "--compare" -> built = Path.of(args[++i]);
                case "--region" -> region = args[++i];
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        long t0 = System.currentTimeMillis();
        boolean massif = region.equals("massif");
        if (region.equals("view")) {
            Box.region("view", -4, 4, -4, 4, 0, 1, WORLD_X, WORLD_Z);
            Terrain.worldX = WORLD_X;
            Terrain.worldZ = WORLD_Z;
            Terrain.nat = new WorldReader(world);
            Render.WORLD_ONLY = true;
            Render.CTX = 440;
            Render.prepare();
            Render.prepareContext();
            log(t0, "world loaded");
            for (Shot s : viewShots(renders)) {
                Path p = out.resolve("preview").resolve(s.name() + ".png");
                if (s.ortho()) Render.ortho(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                else Render.view(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f(), s.fov());
                log(t0, "render " + s.name());
            }
            return;
        }
        if (massif) Box.region("massif", -192, 191, 128, 383, -30, 176, WORLD_X, WORLD_Z);
        Terrain.worldX = WORLD_X;
        Terrain.worldZ = WORLD_Z;
        if (world != null) Terrain.nat = new WorldReader(world);
        if (massif) {
            Massif.generate();
            log(t0, "massif");
            Forest.grow(Massif::density, 62, Massif::blocked);
            log(t0, "forest");
        } else {
            Terrain.generate();
            log(t0, "terrain");
            Town.build();
            log(t0, "town");
        }
        Canvas.validate(blocks);
        if (!renders.equals("none")) {
            Render.prepare();
            Render.prepareContext();
            Path p = out.resolve("preview");
            for (Shot s : massif ? massifShots(renders) : shots(renders)) {
                if (s.ortho()) Render.ortho(p.resolve(s.name() + ".png"), s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                else Render.view(p.resolve(s.name() + ".png"), s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f(), s.fov());
                log(t0, "render " + s.name());
            }
        }
        if (built != null) compare(new WorldReader(built));
        List<Canvas.Cmd> cmds = Canvas.compile();
        if (massif) Canvas.writeDatapack(out.resolve("datapack/nordia_massif"), cmds, 0, Integer.MIN_VALUE, 0);
        else Canvas.writeDatapack(out.resolve("datapack/nordia_spawn"), cmds, Town.SPAWN[0], Town.SPAWN[1], Town.SPAWN[2]);
        log(t0, "datapack");
    }

    record Shot(String name, boolean ortho, int w, int h, double a, double b, double c, double d, double e, double f, double fov) {}

    static List<Shot> shots(String mode) {
        int[] s = Town.SPAWN;
        Shot overview = new Shot("overview", true, 1600, 1000, 0, 10, 0, 215, 38, 300, 0);
        Shot arrival = new Shot("arrival", false, 1280, 720, s[0], s[1] + 1.6, s[2], 0, s[1] + 17, 60, 75);
        Shot mouth = new Shot("from_bay", false, 1280, 720, 0, 5, -124, 0, 18, -30, 62);
        if (mode.equals("quick")) return List.of(overview, arrival, mouth);
        if (mode.equals("top")) return List.of(new Shot("top", true, 1024, 1024, 0, 0, 0, 180, 89.9, 256, 0));
        return List.of(overview, arrival, mouth,
                new Shot("overview_west", true, 1600, 1000, 0, 10, 0, 125, 38, 300, 0),
                new Shot("hall", false, 1280, 720, -22, 52, 60, 0, 50, 92, 70),
                new Shot("harbour", false, 1280, 720, 0, 4, -56, 0, 8, 10, 72),
                new Shot("terraces", false, 1280, 720, 60, 30, -40, 0, 15, 40, 70),
                new Shot("top", true, 1400, 1400, 0, 0, 0, 180, 89.9, 256, 0));
    }

    /** Eye height above the real ground at (u, v). */
    static double eye(int u, int v, double above) {
        int g = Terrain.nat.ground(WORLD_X + u, WORLD_Z + v);
        return (g == WorldReader.MISSING ? 0 : Math.max(g - 64, -2)) + above;
    }

    /** The highest ground within r of (u, v): where a player would stand to look out. */
    static int[] hill(int u, int v, int r) {
        int[] best = {u, v, Integer.MIN_VALUE};
        for (int du = -r; du <= r; du += 2)
            for (int dv = -r; dv <= r; dv += 2) {
                int g = Terrain.nat.ground(WORLD_X + u + du, WORLD_Z + v + dv);
                if (g > best[2]) best = new int[] {u + du, v + dv, g};
            }
        best[2] -= 64;
        return best;
    }

    static Shot from(String name, int[] at, double up, double tx, double ty, double tz, double fov) {
        return new Shot(name, false, 1280, 720, at[0] + 0.5, at[2] + up, at[1] + 0.5, tx, ty, tz, fov);
    }

    /** The "wow test": what a player sees from the places that matter, rendered from the built world. */
    static List<Shot> viewShots(String mode) {
        List<Shot> all = List.of(
                new Shot("wow1_arrival", false, 1280, 720, -5, 2.6, -9, 0, 22, 70, 80),
                new Shot("wow1_land_entrance", false, 1280, 720, 74, eye(74, 24, 2.6), 24, 0, 14, 0, 75),
                from("wow2_hill_over_city", hill(-98, 56, 16), 3.5, 0, 0, -20, 72),
                from("wow3_mountain_view", hill(-40, 205, 14), 3.5, 0, 5, -30, 75),
                from("wow4_coast", hill(98, -100, 10), 3.5, -10, 25, 60, 70),
                new Shot("wow5_river_bridge", false, 1280, 720, 34, eye(34, 6, 2), 6, 22, 8, 22, 70),
                from("wow6_countryside", hill(88, 40, 10), 3.5, 10, 60, 230, 72),
                new Shot("wow_top", true, 1400, 1400, 0, 0, 0, 180, 89.9, 200, 0),
                new Shot("wow7_aerial", false, 1600, 900, -170, 95, -150, 0, 30, 110, 60));
        return mode.equals("quick") ? all.subList(0, 4) : all;
    }

    static List<Shot> massifShots(String mode) {
        int vg = Canvas.ground(-40, 212);
        int bg = Canvas.ground(-9, 186);
        int lg = Canvas.ground(34, 142);
        Shot over = new Shot("m_overview", true, 1600, 1000, 0, 40, 250, 200, 32, 440, 0);
        Shot fromTown = new Shot("m_from_harbour", false, 1280, 720, -5, 6, -20, 0, 60, 260, 70);
        Shot view = new Shot("m_viewpoint", false, 1280, 720, -40, vg + 2.6, 207, 0, 5, 0, 75);
        Shot fromBay = new Shot("m_from_bay", false, 1280, 720, 10, 14, -140, 0, 55, 220, 62);
        if (mode.equals("quick")) return List.of(over, fromBay, view);
        return List.of(over, fromTown, view,
                new Shot("m_bridge", false, 1280, 720, 6, bg + 4, 176, -12, bg - 2, 190, 70),
                new Shot("m_lake", false, 1280, 720, 34, lg + 2.6, 142, 40, 55, 230, 72),
                new Shot("m_from_plateau", false, 1280, 720, 0, 46, 104, 0, 60, 250, 70),
                new Shot("m_top", true, 1400, 940, 0, 0, 256, 180, 89.9, 384, 0));
    }

    /** Reports cells where the built world differs from the model in a way physics causes (spilled water, missing blocks). */
    static void compare(WorldReader w) {
        int spilled = 0, missing = 0;
        java.util.Map<String, Integer> spillAt = new java.util.TreeMap<>();
        for (int u = Canvas.MINX; u <= Canvas.MAXX; u++)
            for (int v = Canvas.MINZ; v <= Canvas.MAXZ; v++)
                for (int y = -12; y <= Canvas.MAXY; y++) {
                    String want = B.base(Canvas.get(u, y, v));
                    String got = w.block(WORLD_X + u, y + 64, WORLD_Z + v);
                    if (got == null) continue;
                    if (got.equals("water") && !want.equals("water") && !want.contains("seagrass") && !want.equals("light")) {
                        spilled++;
                        spillAt.merge((u / 8 * 8) + "," + (v / 8 * 8), 1, Integer::sum);
                    } else if (!want.equals("air") && !want.equals("light") && got.equals("air")) missing++;
                }
        System.out.println("compare: spilled water " + spilled + ", missing blocks " + missing);
        spillAt.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
                .forEach(e -> System.out.println("  spill near " + e.getKey() + ": " + e.getValue()));
    }

    static void log(long t0, String what) {
        System.out.printf("%6.1fs %s%n", (System.currentTimeMillis() - t0) / 1000.0, what);
    }
}
