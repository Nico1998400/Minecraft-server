import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Prints Pos / Rotation / Dimension from gzip player.dat files. */
public final class DumpPos {
    public static void main(String[] args) throws Exception {
        String[] files = {
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/players/data/0fe1dc7a-ac12-4fd7-a897-3fd820f9441f.dat",
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/players/data/0fe1dc7a-ac12-4fd7-a897-3fd820f9441f.dat_old",
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/players/data/422d266c-f182-4fda-843c-ca5f73302ad3.dat",
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/players/data/422d266c-f182-4fda-843c-ca5f73302ad3.dat_old",
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world_kullar_2026-09-29/players/data/0fe1dc7a-ac12-4fd7-a897-3fd820f9441f.dat",
                "c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world_oldspawn_2026-09-28/players/data/0fe1dc7a-ac12-4fd7-a897-3fd820f9441f.dat",
        };
        for (String f : files) dump(Path.of(f));
    }

    static void dump(Path p) throws IOException {
        System.out.println("=== " + p + " exists=" + Files.exists(p) + " ===");
        if (!Files.exists(p)) return;
        byte[] raw = Files.readAllBytes(p);
        DataInputStream in;
        try {
            in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)));
        } catch (IOException e) {
            in = new DataInputStream(new ByteArrayInputStream(raw));
        }
        in.readByte();
        try { in.readUTF(); } catch (IOException ignored) {}
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) WorldReader.Nbt.read(in, 10);
        Object pos = root.get("Pos");
        Object rot = root.get("Rotation");
        Object dim = root.get("Dimension");
        System.out.println("  Pos=" + pos);
        System.out.println("  Rot=" + rot);
        System.out.println("  Dim=" + dim);
        Object spawnX = root.get("SpawnX");
        Object spawnY = root.get("SpawnY");
        Object spawnZ = root.get("SpawnZ");
        System.out.println("  Spawn=" + spawnX + " " + spawnY + " " + spawnZ + " world=" + root.get("SpawnDimension"));
        if (pos instanceof List<?> l && l.size() >= 3) {
            System.out.printf("  coords %.1f %.1f %.1f%n", ((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue());
        }
    }
}
