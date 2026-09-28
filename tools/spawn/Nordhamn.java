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
        int originX = WORLD_X, originZ = WORLD_Z, srcX = 1595, srcZ = 72;
        Path source = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world" -> world = Path.of(args[++i]);
                case "--blocks" -> blocks = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--renders" -> renders = args[++i];
                case "--compare" -> built = Path.of(args[++i]);
                case "--region" -> region = args[++i];
                case "--source" -> source = Path.of(args[++i]);
                case "--src-center" -> {
                    String[] o = args[++i].split(",");
                    srcX = Integer.parseInt(o[0].trim());
                    srcZ = Integer.parseInt(o[1].trim());
                }
                case "--origin" -> {
                    String[] o = args[++i].split(",");
                    originX = Integer.parseInt(o[0].trim());
                    originZ = Integer.parseInt(o[1].trim());
                }
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        long t0 = System.currentTimeMillis();
        boolean massif = region.equals("massif");
        if (region.equals("transplant")) {
            Box.region("hub", -255, 254, -247, 247, -30, 146, originX, originZ);
            Terrain.worldX = originX;
            Terrain.worldZ = originZ;
            Terrain.nat = new WorldReader(world);
            Transplant.generate(new WorldReader(source), srcX, srcZ);
            log(t0, "transplant");
            if (!renders.equals("none")) {
                Render.prepare();
                Render.prepareContext();
                List<Shot> shots = List.of(
                        new Shot("t_overview", true, 1600, 1000, 0, 60, 0, 215, 35, 700, 0),
                        new Shot("t_top", true, 1200, 1200, 0, 60, 0, 180, 89.9, 620, 0),
                        new Shot("t_hub", true, 1600, 1000, 0, 62, 0, 125, 38, 360, 0));
                for (Shot s : shots) {
                    Path p = out.resolve("preview").resolve(s.name() + ".png");
                    Render.ortho(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                    log(t0, "render " + s.name());
                }
            }
            Canvas.writeDatapack(out.resolve("datapack/nordia_hub"), Canvas.compile(), 0, Integer.MIN_VALUE, 0);
            log(t0, "datapack");
            return;
        }
        if (region.equals("oldtown") || region.equals("districts")) {
            if (region.equals("oldtown")) Box.region("oldtown", -96, 95, -72, 71, -40, 110, 1740, -80);
            else Box.region("districts", -250, 250, -240, 240, -40, 110, originX, originZ);
            Terrain.worldX = Box.worldX;
            Terrain.worldZ = Box.worldZ;
            Terrain.nat = new WorldReader(world);
            OldTown.generate();
            log(t0, "oldtown");
            Canvas.validate(blocks);
            OldTown.registry(out.resolve(region + "_lots.json"));
            if (!renders.equals("none")) {
                Render.prepare();
                Render.prepareContext();
                List<Shot> shots = List.of(
                        new Shot("o_overview", true, 1600, 1000, 0, 60, 0, 200, 38, 220, 0),
                        new Shot("o_overview_w", true, 1600, 1000, 0, 60, 60, 125, 35, 300, 0),
                        new Shot("o_top", true, 1200, 900, 0, 60, 0, 180, 89.9, 200, 0),
                        new Shot("o_street", false, 1280, 720, 60, Canvas.ground(60, 2) + 2.6, 2, 0, Canvas.ground(0, 10) + 6, 10, 72));
                for (Shot s : shots) {
                    Path p = out.resolve("preview").resolve(s.name() + ".png");
                    if (s.ortho()) Render.ortho(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                    else Render.view(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f(), s.fov());
                    log(t0, "render " + s.name());
                }
            }
            Canvas.writeDatapack(out.resolve("datapack/nordia_" + region), Canvas.compile(), 0, Integer.MIN_VALUE, 0);
            log(t0, "datapack");
            return;
        }
        if (region.equals("villas")) {
            Box.region("villas", -112, 111, -112, 111, -30, 60, originX, originZ);
            Terrain.worldX = originX;
            Terrain.worldZ = originZ;
            if (world != null) Terrain.nat = new WorldReader(world);
            VillaDistrict.generate();
            log(t0, "villas");
            Canvas.validate(blocks);
            VillaDistrict.registry(out.resolve("villas_lots.json"));
            if (!renders.equals("none")) {
                Render.prepare();
                Render.prepareContext();
                for (Shot s : villaShots(renders)) {
                    Path p = out.resolve("preview").resolve(s.name() + ".png");
                    if (s.ortho()) Render.ortho(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f());
                    else Render.view(p, s.w(), s.h(), s.a(), s.b(), s.c(), s.d(), s.e(), s.f(), s.fov());
                    log(t0, "render " + s.name());
                }
            }
            Canvas.writeDatapack(out.resolve("datapack/nordia_villas"), Canvas.compile(), 0, Integer.MIN_VALUE, 0);
            log(t0, "datapack");
            return;
        }
        if (region.equals("view")) {
            Box.region("view", -4, 4, -4, 4, 0, 1, originX, originZ);
            Terrain.worldX = originX;
            Terrain.worldZ = originZ;
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
        if (mode.equals("hubtop")) return List.of(new Shot("hub_top_big", true, 1600, 1600, 0, 60, 0, 180, 89.9, 560, 0));
        if (mode.equals("hub")) return List.of(
                new Shot("hub_top", true, 1400, 1400, 0, 60, 0, 180, 89.9, 420, 0),
                new Shot("hub_iso", true, 1600, 1000, 0, 62, 0, 215, 40, 360, 0),
                new Shot("hub_iso_w", true, 1600, 1000, 0, 62, 0, 125, 40, 360, 0),
                new Shot("hub_close", true, 1600, 1000, 0, 62, 0, 215, 35, 180, 0));
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

    static List<Shot> villaShots(String mode) {
        Shot over = new Shot("v_overview", true, 1600, 1000, 0, 8, 0, 200, 38, 240, 0);
        Shot top = new Shot("v_top", true, 1200, 1200, 0, 0, 0, 180, 89.9, 224, 0);
        Shot street = new Shot("v_street", false, 1280, 720, 60, Canvas.ground(60, 2) + 2.6, 2, 20, Canvas.ground(20, 8) + 3, 8, 75);
        Shot lake = new Shot("v_lake", false, 1280, 720, 0, 2, -80, 0, 8, -30, 70);
        Shot hill = new Shot("v_hill", false, 1280, 720, 50, Canvas.ground(50, 70) + 3, 70, 0, 4, -20, 72);
        if (mode.equals("quick")) return List.of(over, top, street);
        if (mode.equals("lots")) {
            List<Shot> l = new java.util.ArrayList<>();
            for (VillaDistrict.Lot lot : VillaDistrict.LOTS) {
                B.Frame f = lot.frame();
                int cu = f.wx(lot.w() / 2, lot.d() + 7), cv = f.wz(lot.w() / 2, lot.d() + 7);
                int tu = f.wx(lot.w() / 2, lot.d() / 2), tv = f.wz(lot.w() / 2, lot.d() / 2);
                l.add(new Shot(String.format("lot_%02d", lot.id()), false, 960, 600, cu + 0.5, Canvas.ground(cu, cv) + 5, cv + 0.5,
                        tu + 0.5, Canvas.ground(tu, tv) + 3, tv + 0.5, 70));
            }
            return l;
        }
        return List.of(over, top, street, lake, hill,
                new Shot("v_strand", false, 1280, 720, -20, Canvas.ground(-20, -38) + 2.6, -38, 30, Canvas.ground(30, -40) + 3, -42, 75),
                new Shot("v_park", false, 1280, 720, -40, Canvas.ground(-40, -2) + 3, -2, -40, 2, -30, 72));
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
