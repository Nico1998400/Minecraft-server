import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Lists tall man-made structures (20x20 cells) around the hub in a world. */
public final class ScanTall {
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0]
                : "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region";
        int cx = args.length > 2 ? Integer.parseInt(args[1]) : -1150, cz = args.length > 2 ? Integer.parseInt(args[2]) : -1050;
        WorldReader w = new WorldReader(Path.of(dir));
        Map<String, String> out = new TreeMap<>();
        for (int x = cx - 400; x <= cx + 400; x += 20) {
            for (int z = cz - 400; z <= cz + 400; z += 20) {
                int best = Integer.MIN_VALUE, bx = 0, bz = 0;
                String bb = null;
                for (int dx = 0; dx < 20; dx += 2)
                    for (int dz = 0; dz < 20; dz += 2) {
                        int s = w.surface(x + dx, z + dz);
                        if (s == WorldReader.MISSING) continue;
                        String b = w.block(x + dx, s, z + dz);
                        if (b == null || b.contains("leaves") || b.contains("log") || b.endsWith("_wood") || b.contains("grass")
                            || b.equals("stone") || b.equals("dirt") || b.contains("snow") || b.equals("water")) continue;
                        if (s < 140) continue;
                        if (s > best) { best = s; bx = x + dx; bz = z + dz; bb = b; }
                    }
                if (bb != null) out.put(String.format("%06d,%06d", bx + 100000, bz + 100000),
                        bx + "," + bz + " top=" + best + " " + bb);
            }
        }
        System.out.println("cells=" + out.size() + " castle=" + w.surface(-1052, -1078));
        out.values().forEach(System.out::println);
    }
}
