import java.nio.file.Path;

/** Compares old hub ground with the new world's natural ground for a transplant origin. */
public final class ProbeSite {
    public static void main(String[] args) {
        int ox = Integer.parseInt(args[0]), oz = Integer.parseInt(args[1]);
        WorldReader nw = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        WorldReader old = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world_oldspawn_2026-09-28/dimensions/minecraft/overworld/region"));
        int missing = 0, total = 0, water = 0;
        for (int u = -300; u <= 300; u += 16)
            for (int v = -300; v <= 300; v += 16) {
                total++;
                int s = nw.surface(ox + u, oz + v);
                if (s == WorldReader.MISSING) { missing++; continue; }
                String b = nw.block(ox + u, s, oz + v);
                if ("water".equals(b)) water++;
            }
        System.out.printf("new world chunks in +-300: %d samples, missing %d, water %d%n", total, missing, water);
        long so = 0, sn = 0; int n = 0;
        StringBuilder ring = new StringBuilder();
        for (int a = 0; a < 360; a += 15) {
            double r = Math.toRadians(a);
            int u = (int) Math.round(Math.cos(r) * 215), v = (int) Math.round(Math.sin(r) * 210);
            int og = Transplant.terrain(old, 1595 + u, 72 + v) + 64;
            int s = nw.surface(ox + u, oz + v);
            int ng = s == WorldReader.MISSING ? -999 : nw.ground(ox + u, oz + v);
            ring.append(String.format("  a=%3d old=%4d new=%4d%n", a, og, ng));
            if (ng > -999) { so += og; sn += ng; n++; }
        }
        System.out.print(ring);
        if (n > 0) System.out.printf("ring avg old=%.1f new=%.1f diff=%.1f%n", so / (double) n, sn / (double) n, (so - sn) / (double) n);
        System.out.println("old castle ground " + (Transplant.terrain(old, 1693, 44) + 64) + ", new at castle spot " + nw.ground(ox + 98, oz - 28));
        long c = 0; int cn = 0;
        for (int u = -120; u <= 120; u += 20) for (int v = -120; v <= 120; v += 20) { c += Transplant.terrain(old, 1595 + u, 72 + v) + 64; cn++; }
        System.out.printf("old hub core avg ground %.1f%n", c / (double) cn);
    }
}
