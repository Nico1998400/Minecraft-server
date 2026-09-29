import java.nio.file.Path;

/** Finds a dry, even site for the church next to the new hub. */
public final class ProbeChurch {
    public static void main(String[] args) {
        WorldReader nw = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        WorldReader old = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world_oldspawn_2026-09-28/dimensions/minecraft/overworld/region"));
        long s = 0; int n = 0, lo = 999, hi = -999;
        for (int u = -40; u <= 40; u += 8) for (int v = -40; v <= 40; v += 8) {
            int g = Transplant.terrain(old, 8136 + u, -4215 + v) + 64;
            s += g; n++; lo = Math.min(lo, g); hi = Math.max(hi, g);
        }
        System.out.printf("church old ground avg %.1f min %d max %d%n", s / (double) n, lo, hi);
        int ox = 1567, oz = 930;
        for (int a = 0; a < 360; a += 20) {
            double r = Math.toRadians(a);
            for (int dist : new int[] {270, 330, 390}) {
                int cx = ox + (int) Math.round(Math.cos(r) * dist), cz = oz + (int) Math.round(Math.sin(r) * dist);
                int wet = 0, miss = 0, cnt = 0, mn = 999, mx = -999; long sum = 0;
                for (int u = -45; u <= 45; u += 9) for (int v = -45; v <= 45; v += 9) {
                    cnt++;
                    int top = nw.surface(cx + u, cz + v);
                    if (top == WorldReader.MISSING) { miss++; continue; }
                    String b = nw.block(cx + u, top, cz + v);
                    if ("water".equals(b)) { wet++; continue; }
                    int g = nw.ground(cx + u, cz + v);
                    sum += g; mn = Math.min(mn, g); mx = Math.max(mx, g);
                }
                int dry = cnt - wet - miss;
                System.out.printf("a=%3d d=%d at %d,%d  water %d/%d missing %d  ground %s..%s avg %s%n", a, dist, cx, cz, wet, cnt, miss,
                        dry > 0 ? mn : "-", dry > 0 ? mx : "-", dry > 0 ? String.format("%.0f", sum / (double) dry) : "-");
            }
        }
    }
}
