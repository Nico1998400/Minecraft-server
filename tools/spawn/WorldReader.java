import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads terrain from Anvil region files (read-only): surface heights and block names. Used to blend the generated
 * town into the natural landscape around it. Chunks that were never generated report {@link #MISSING}.
 */
final class WorldReader {

    static final int MISSING = Integer.MIN_VALUE;
    static final int MIN_Y = -64;

    private final Path regionDir;
    private final Map<Long, Chunk> chunks = new HashMap<>();

    WorldReader(Path regionDir) {
        this.regionDir = regionDir;
    }

    /** y of the highest non-air block, ignoring leaves and plants ("MOTION_BLOCKING_NO_LEAVES" - 1). */
    int surface(int x, int z) {
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null || c.surface == null ? MISSING : c.surface[(z & 15) * 16 + (x & 15)] + MIN_Y - 1;
    }

    /** Ground height: the surface with trees, plants and snow layers stripped away. */
    int ground(int x, int z) {
        int y = floor(x, z);
        if (y == MISSING) return MISSING;
        for (int i = 0; i < 40; i++) {
            String b = block(x, y, z);
            if (b == null || !(b.endsWith("_log") || b.endsWith("_leaves") || b.endsWith("_wood") || b.contains("mushroom")
                               || b.equals("vine") || b.equals("bee_nest") || b.equals("snow") || b.contains("grass")
                               && !b.equals("grass_block") || b.contains("fern") || b.contains("bush"))) return y;
            y--;
        }
        return y;
    }

    /** y of the highest solid block below any water ("OCEAN_FLOOR" - 1). */
    int floor(int x, int z) {
        Chunk c = chunk(x >> 4, z >> 4);
        return c == null || c.floor == null ? MISSING : c.floor[(z & 15) * 16 + (x & 15)] + MIN_Y - 1;
    }

    String block(int x, int y, int z) {
        Chunk c = chunk(x >> 4, z >> 4);
        if (c == null) return null;
        Section s = c.sections.get(Math.floorDiv(y, 16));
        if (s == null) return "air";
        if (s.data == null) return s.palette.get(0);
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(s.palette.size() - 1));
        int perLong = 64 / bits;
        int index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15);
        long word = s.data[index / perLong];
        int value = (int) ((word >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
        return value < s.palette.size() ? s.palette.get(value) : "air";
    }

    private Chunk chunk(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        if (chunks.containsKey(key)) return chunks.get(key);
        Chunk c = null;
        try {
            c = load(cx, cz);
        } catch (IOException e) {
            System.err.println("chunk " + cx + "," + cz + ": " + e);
        }
        chunks.put(key, c);
        return c;
    }

    private Chunk load(int cx, int cz) throws IOException {
        Path file = regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
        if (!Files.exists(file)) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            int slot = (cx & 31) + (cz & 31) * 32;
            raf.seek(slot * 4L);
            int loc = raf.readInt();
            if (loc == 0) return null;
            long offset = (long) (loc >>> 8) * 4096;
            raf.seek(offset);
            int length = raf.readInt();
            int compression = raf.readUnsignedByte();
            byte[] buf = new byte[length - 1];
            raf.readFully(buf);
            DataInputStream in = switch (compression) {
                case 1 -> new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(buf)));
                case 2 -> new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(buf)));
                case 3 -> new DataInputStream(new ByteArrayInputStream(buf));
                default -> throw new IOException("unsupported compression " + compression);
            };
            in.readByte();
            in.readUTF();
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) Nbt.read(in, 10);
            String status = String.valueOf(root.get("Status"));
            if (!status.endsWith("full")) return null;
            Chunk c = new Chunk();
            @SuppressWarnings("unchecked")
            Map<String, Object> maps = (Map<String, Object>) root.get("Heightmaps");
            if (maps != null) {
                c.surface = unpack((long[]) maps.get("MOTION_BLOCKING_NO_LEAVES"));
                c.floor = unpack((long[]) maps.get("OCEAN_FLOOR"));
            }
            @SuppressWarnings("unchecked")
            List<Object> sections = (List<Object>) root.get("sections");
            if (sections != null)
                for (Object o : sections) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sec = (Map<String, Object>) o;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> states = (Map<String, Object>) sec.get("block_states");
                    if (states == null) continue;
                    Section s = new Section();
                    for (Object p : (List<?>) states.get("palette")) {
                        String name = String.valueOf(((Map<?, ?>) p).get("Name"));
                        s.palette.add(name.startsWith("minecraft:") ? name.substring(10) : name);
                    }
                    s.data = (long[]) states.get("data");
                    c.sections.put((int) (byte) sec.get("Y"), s);
                }
            return c;
        }
    }

    private static int[] unpack(long[] packed) {
        if (packed == null) return null;
        int bits = 9; // 384-block worlds
        int perLong = 64 / bits;
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) out[i] = (int) ((packed[i / perLong] >>> ((i % perLong) * bits)) & ((1 << bits) - 1));
        return out;
    }

    static final class Chunk {
        int[] surface, floor;
        final Map<Integer, Section> sections = new HashMap<>();
    }

    static final class Section {
        final List<String> palette = new ArrayList<>();
        long[] data;
    }

    /** Minimal NBT decoder (big-endian, as stored in region files). */
    static final class Nbt {
        static Object read(DataInputStream in, int type) throws IOException {
            return switch (type) {
                case 1 -> in.readByte();
                case 2 -> in.readShort();
                case 3 -> in.readInt();
                case 4 -> in.readLong();
                case 5 -> in.readFloat();
                case 6 -> in.readDouble();
                case 7 -> {
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    yield b;
                }
                case 8 -> {
                    byte[] b = new byte[in.readUnsignedShort()];
                    in.readFully(b);
                    yield new String(b, StandardCharsets.UTF_8);
                }
                case 9 -> {
                    int t = in.readByte(), n = in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) list.add(read(in, t));
                    yield list;
                }
                case 10 -> {
                    Map<String, Object> map = new HashMap<>();
                    for (int t; (t = in.readByte()) != 0; ) {
                        byte[] b = new byte[in.readUnsignedShort()];
                        in.readFully(b);
                        map.put(new String(b, StandardCharsets.UTF_8), read(in, t));
                    }
                    yield map;
                }
                case 11 -> {
                    int[] a = new int[in.readInt()];
                    for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                    yield a;
                }
                case 12 -> {
                    long[] a = new long[in.readInt()];
                    for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                    yield a;
                }
                default -> throw new IOException("bad NBT tag " + type);
            };
        }
    }
}
