import java.nio.file.Path;

/** Looks at the blocks around Brage's post: floor, free space and roof, to see why he cannot find places to walk. */
public final class ProbeBrage {
    static boolean air(String b) {
        return b == null || b.contains("air") || b.contains("carpet") || b.contains("torch") || b.contains("lantern")
                || b.contains("banner") || b.contains("sign") || b.contains("button") || b.contains("pressure") || b.contains("rail");
    }

    public static void main(String[] args) {
        WorldReader w = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        int cx = Integer.parseInt(args[0]);
        int cy = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        System.out.println("blocks at the post column (" + cx + "," + cz + "), y " + (cy - 3) + ".." + (cy + 12));
        for (int y = cy + 12; y >= cy - 3; y--) {
            System.out.println("  y=" + y + " " + w.block(cx, y, cz));
        }
        System.out.println("map at feet level y=" + cy + " (floor y-1 solid = F, feet blocked = #, free+floor = ., no floor = _), roof within 10 = R");
        for (int z = cz - 9; z <= cz + 9; z++) {
            StringBuilder sb = new StringBuilder();
            for (int x = cx - 12; x <= cx + 12; x++) {
                String floor = w.block(x, cy - 1, z);
                String feet = w.block(x, cy, z);
                String head = w.block(x, cy + 1, z);
                char c;
                if (!air(feet) || !air(head)) {
                    c = '#';
                } else if (air(floor)) {
                    c = '_';
                } else {
                    boolean roof = false;
                    for (int h = 2; h <= 10; h++) {
                        if (!air(w.block(x, cy + h, z))) {
                            roof = true;
                        }
                    }
                    c = roof ? 'R' : '.';
                }
                if (x == cx && z == cz) {
                    c = '@';
                }
                sb.append(c);
            }
            System.out.println(sb);
        }
    }
}
