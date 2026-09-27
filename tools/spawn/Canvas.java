import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The voxel model of the town in relative coordinates (origin = design centre, y 0 = world y 64, sea surface = y -2).
 * Every cell inside the box is emitted, so the box fully replaces what the world had there.
 */
final class Canvas {

    static final int MINX = -128, MAXX = 127, MINZ = -128, MAXZ = 127, MINY = -30, MAXY = 112;
    static final int SX = MAXX - MINX + 1, SY = MAXY - MINY + 1, SZ = MAXZ - MINZ + 1;
    static final int SEA = -2; // top water block

    static final short[] VOX = new short[SX * SY * SZ];
    static final List<String> PALETTE = new ArrayList<>();
    static final Map<String, Short> INDEX = new HashMap<>();
    static final List<String> ENTITIES = new ArrayList<>();
    /** Ground height (top solid block) after terrain shaping; buildings update it where they stand. */
    static final int[][] GROUND = new int[SX][SZ];
    /** Cells claimed by structures or streets — trees and clutter keep out. */
    static final boolean[][] USED = new boolean[SX][SZ];
    static final boolean[][] STREET = new boolean[SX][SZ];
    static final Random RNG = new Random(793);

    static {
        id("air");
    }

    static short id(String s) {
        return INDEX.computeIfAbsent(s, k -> {
            PALETTE.add(k);
            if (PALETTE.size() > Short.MAX_VALUE) throw new IllegalStateException("palette overflow");
            return (short) (PALETTE.size() - 1);
        });
    }

    static boolean in(int x, int y, int z) {
        return x >= MINX && x <= MAXX && y >= MINY && y <= MAXY && z >= MINZ && z <= MAXZ;
    }

    static boolean inXZ(int x, int z) {
        return x >= MINX && x <= MAXX && z >= MINZ && z <= MAXZ;
    }

    static int idx(int x, int y, int z) {
        return ((y - MINY) * SZ + (z - MINZ)) * SX + (x - MINX);
    }

    static void set(int x, int y, int z, String s) {
        if (in(x, y, z)) VOX[idx(x, y, z)] = id(s);
    }

    static void setIfAir(int x, int y, int z, String s) {
        if (in(x, y, z) && VOX[idx(x, y, z)] == 0) VOX[idx(x, y, z)] = id(s);
    }

    static String get(int x, int y, int z) {
        return in(x, y, z) ? PALETTE.get(VOX[idx(x, y, z)]) : "air";
    }

    static boolean isAir(int x, int y, int z) {
        return in(x, y, z) && VOX[idx(x, y, z)] == 0;
    }

    static boolean isSolid(int x, int y, int z) {
        if (!in(x, y, z)) return false;
        String b = B.base(get(x, y, z));
        return !(b.equals("air") || b.equals("water") || b.equals("light") || B.isPlant(b));
    }

    static void fill(int x0, int y0, int z0, int x1, int y1, int z1, String s) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, s);
    }

    static int ground(int x, int z) {
        return inXZ(x, z) ? GROUND[x - MINX][z - MINZ] : SEA - 10;
    }

    static void setGround(int x, int z, int y) {
        if (inXZ(x, z)) GROUND[x - MINX][z - MINZ] = y;
    }

    static boolean used(int x, int z) {
        return !inXZ(x, z) || USED[x - MINX][z - MINZ];
    }

    static void use(int x0, int z0, int x1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                if (inXZ(x, z)) USED[x - MINX][z - MINZ] = true;
    }

    static boolean street(int x, int z) {
        return inXZ(x, z) && STREET[x - MINX][z - MINZ];
    }

    static boolean free(int x0, int z0, int x1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                if (used(x, z)) return false;
        return true;
    }

    static double rnd() {
        return RNG.nextDouble();
    }

    static int rint(int n) {
        return RNG.nextInt(n);
    }

    static <T> T pick(List<T> options) {
        return options.get(RNG.nextInt(options.size()));
    }

    @SafeVarargs
    static <T> T pick(T... options) {
        return options[RNG.nextInt(options.length)];
    }

    // ------------------------------------------------------------------ compile

    record Cmd(String text, long volume) {}

    static String rel(int v) {
        return v == 0 ? "~" : "~" + v;
    }

    /** Phase 0: solid and air, bottom-up; 1: water; 2: blocks that need support (placed individually, last). */
    static int phase(String state) {
        String b = B.base(state);
        if (b.equals("water")) return 1;
        return B.needsSupport(b) ? 2 : 0;
    }

    static List<Cmd> compile() {
        List<Cmd> out = new ArrayList<>();
        boolean[] done = new boolean[VOX.length];
        int[] phaseOf = new int[PALETTE.size()];
        for (int i = 0; i < PALETTE.size(); i++) phaseOf[i] = phase(PALETTE.get(i));
        for (int ph = 0; ph < 3; ph++)
            for (int y = MINY; y <= MAXY; y++)
                for (int z = MINZ; z <= MAXZ; z++)
                    for (int x = MINX; x <= MAXX; x++) {
                        int i = idx(x, y, z);
                        short v = VOX[i];
                        if (done[i] || phaseOf[v] != ph) continue;
                        String state = PALETTE.get(v);
                        if (ph == 2) {
                            done[i] = true;
                            out.add(new Cmd("setblock " + rel(x) + " " + rel(y) + " " + rel(z) + " " + state
                                            + (B.strictPlacement(state) ? " strict" : ""), 1));
                            continue;
                        }
                        int x2 = x;
                        while (x2 + 1 <= MAXX && same(x2 + 1, y, z, v, done) && (x2 + 2 - x) <= 32768) x2++;
                        int z2 = z;
                        while (z2 + 1 <= MAXZ && (long) (x2 - x + 1) * (z2 + 2 - z) <= 32768 && row(x, x2, y, z2 + 1, v, done)) z2++;
                        int y2 = y;
                        while (y2 + 1 <= MAXY && (long) (x2 - x + 1) * (z2 - z + 1) * (y2 + 2 - y) <= 32768
                               && layer(x, x2, y2 + 1, z, z2, v, done)) y2++;
                        for (int yy = y; yy <= y2; yy++)
                            for (int zz = z; zz <= z2; zz++)
                                for (int xx = x; xx <= x2; xx++) done[idx(xx, yy, zz)] = true;
                        long vol = (long) (x2 - x + 1) * (y2 - y + 1) * (z2 - z + 1);
                        out.add(vol == 1
                                ? new Cmd("setblock " + rel(x) + " " + rel(y) + " " + rel(z) + " " + state, 1)
                                : new Cmd("fill " + rel(x) + " " + rel(y) + " " + rel(z) + " " + rel(x2) + " " + rel(y2) + " "
                                          + rel(z2) + " " + state, vol));
                    }
        return out;
    }

    static boolean same(int x, int y, int z, short v, boolean[] done) {
        int i = idx(x, y, z);
        return !done[i] && VOX[i] == v;
    }

    static boolean row(int x0, int x1, int y, int z, short v, boolean[] done) {
        for (int x = x0; x <= x1; x++) if (!same(x, y, z, v, done)) return false;
        return true;
    }

    static boolean layer(int x0, int x1, int y, int z0, int z1, short v, boolean[] done) {
        for (int z = z0; z <= z1; z++) if (!row(x0, x1, y, z, v, done)) return false;
        return true;
    }

    // ------------------------------------------------------------------ data pack

    static void writeDatapack(Path root, List<Cmd> cmds, int spawnX, int spawnY, int spawnZ) throws IOException {
        if (Files.exists(root)) try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        Path fn = root.resolve("data/nordia/function/spawn");
        Files.createDirectories(fn);
        write(root.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "description": "NORDIA — Nordhamn spawn town",
                    "pack_format": 107,
                    "min_format": [107, 0],
                    "max_format": [107, 1]
                  }
                }
                """);
        List<List<Cmd>> parts = new ArrayList<>();
        List<Cmd> current = new ArrayList<>();
        long volume = 0;
        for (Cmd c : cmds) {
            if (!current.isEmpty() && (current.size() >= 2000 || volume + c.volume() > 400_000)) {
                parts.add(current);
                current = new ArrayList<>();
                volume = 0;
            }
            current.add(c);
            volume += c.volume();
        }
        if (!current.isEmpty()) parts.add(current);
        String at = "execute at @e[type=marker,tag=nordia_spawn_origin,limit=1] run ";
        String area = "~" + MINX + " ~" + MINZ + " ~" + MAXX + " ~" + MAXZ;
        write(fn.resolve("build.mcfunction"), String.join("\n",
                "# Builds Nordhamn centred on the execution position (y = world 64).",
                "# Usage: /execute positioned <x> 64 <z> run function nordia:spawn/build",
                "kill @e[type=marker,tag=nordia_spawn_origin]",
                "execute align xyz run forceload add ~" + MINX + " ~" + MINZ + " ~" + MAXX + " ~-1",
                "execute align xyz run forceload add ~" + MINX + " ~0 ~" + MAXX + " ~" + MAXZ,
                "schedule function nordia:spawn/start 100t",
                "execute align xyz run summon marker ~ ~ ~ {Tags:[\"nordia_spawn_origin\"]}",
                "tellraw @a {\"text\":\"[NORDIA] Bygger Nordhamn — " + parts.size() + " steg...\",\"color\":\"gold\"}", ""));
        write(fn.resolve("start.mcfunction"), String.join("\n",
                "execute unless entity @e[type=marker,tag=nordia_spawn_origin] run tellraw @a {\"text\":\"[NORDIA] Markören saknas — kör build igen när området är laddat\",\"color\":\"red\"}",
                "execute if entity @e[type=marker,tag=nordia_spawn_origin] run function nordia:spawn/stage_0", ""));
        for (int k = 0; k < parts.size(); k++) {
            StringBuilder sb = new StringBuilder();
            for (Cmd c : parts.get(k)) sb.append(c.text()).append('\n');
            write(fn.resolve("part_" + k + ".mcfunction"), sb.toString());
            String next = k + 1 < parts.size() ? "stage_" + (k + 1) : "entities";
            write(fn.resolve("stage_" + k + ".mcfunction"), String.join("\n",
                    at + "function nordia:spawn/part_" + k,
                    "title @a actionbar {\"text\":\"Bygger Nordhamn " + (k + 1) + "/" + parts.size() + "\",\"color\":\"gold\"}",
                    "schedule function nordia:spawn/" + next + " 3t", ""));
        }
        StringBuilder ents = new StringBuilder();
        ents.append(at).append("kill @e[tag=nordia_spawn,distance=..200]\n");
        for (String e : ENTITIES) ents.append(at).append(e).append('\n');
        ents.append("schedule function nordia:spawn/finish 2t\n");
        write(fn.resolve("entities.mcfunction"), ents.toString());
        write(fn.resolve("finish.mcfunction"), String.join("\n",
                at + "setworldspawn ~" + spawnX + " ~" + spawnY + " ~" + spawnZ,
                at + "forceload remove ~" + MINX + " ~" + MINZ + " ~" + MAXX + " ~-1",
                at + "forceload remove ~" + MINX + " ~0 ~" + MAXX + " ~" + MAXZ,
                "kill @e[type=marker,tag=nordia_spawn_origin]",
                "tellraw @a {\"text\":\"[NORDIA] Nordhamn är klart!\",\"color\":\"green\"}", ""));
        System.out.printf("datapack: %d commands in %d parts, %d entities -> %s%n", cmds.size(), parts.size(),
                ENTITIES.size(), root);
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    static void validate(Path blockList) throws IOException {
        if (blockList == null) return;
        Set<String> known = new TreeSet<>(Files.readAllLines(blockList).stream().map(String::trim).toList());
        known.addAll(List.of("air", "water", "light", "tall_grass", "large_fern", "cave_vines", "cave_vines_plant", "vine",
                "glow_lichen"));
        Set<String> unknown = new TreeSet<>();
        for (String s : PALETTE) {
            String b = B.base(s);
            String standing = b.replace("_wall_banner", "_banner").replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_sign", "_sign");
            if (!known.contains(b) && !known.contains(standing)) unknown.add(b);
        }
        if (!unknown.isEmpty()) throw new IllegalStateException("Unknown blocks: " + unknown);
        System.out.println("validated " + PALETTE.size() + " block states");
    }
}
