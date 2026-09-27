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
        String renders = "quick";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world" -> world = Path.of(args[++i]);
                case "--blocks" -> blocks = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--renders" -> renders = args[++i];
                case "--compare" -> built = Path.of(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        long t0 = System.currentTimeMillis();
        Terrain.worldX = WORLD_X;
        Terrain.worldZ = WORLD_Z;
        if (world != null) Terrain.nat = new WorldReader(world);
        Terrain.generate();
        log(t0, "terrain");
        Town.build();
        log(t0, "town");
        Canvas.validate(blocks);
        if (!renders.equals("none")) {
            Render.prepare();
            Render.prepareContext();
            Path p = out.resolve("preview");
            for (Shot s : shots(renders)) {
                if (s.ortho()) Render.ortho(p.resolve(s.name() + ".png"), s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                else Render.view(p.resolve(s.name() + ".png"), s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f(), s.fov());
                log(t0, "render " + s.name());
            }
        }
        if (built != null) compare(new WorldReader(built));
        List<Canvas.Cmd> cmds = Canvas.compile();
        Canvas.writeDatapack(out.resolve("datapack/nordia_spawn"), cmds, Town.SPAWN[0], Town.SPAWN[1], Town.SPAWN[2]);
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
