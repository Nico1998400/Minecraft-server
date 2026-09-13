package se.nordia.swedencore.properties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Spatial index: world → chunk → entries whose region touches that chunk. Point queries inspect only the entries of
 * one chunk, so block events stay O(1) regardless of how many properties exist.
 *
 * <p>Not thread-safe; confine to one thread (the server main thread).
 */
public final class RegionIndex<T> {

    private record Key(String world, long chunk) {
    }

    private final Function<T, Region> regionOf;
    private final Function<T, Long> idOf;
    private final Map<Key, List<T>> buckets = new HashMap<>();
    private final Map<Long, T> byId = new HashMap<>();

    public RegionIndex(Function<T, Region> regionOf, Function<T, Long> idOf) {
        this.regionOf = regionOf;
        this.idOf = idOf;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xffffffffL) | (((long) chunkZ & 0xffffffffL) << 32);
    }

    public void put(T entry) {
        remove(idOf.apply(entry));
        byId.put(idOf.apply(entry), entry);
        Region r = regionOf.apply(entry);
        for (int cx = r.minChunkX(); cx <= r.maxChunkX(); cx++) {
            for (int cz = r.minChunkZ(); cz <= r.maxChunkZ(); cz++) {
                buckets.computeIfAbsent(new Key(r.world(), chunkKey(cx, cz)), k -> new ArrayList<>(2)).add(entry);
            }
        }
    }

    public void remove(long id) {
        T existing = byId.remove(id);
        if (existing == null) {
            return;
        }
        Region r = regionOf.apply(existing);
        for (int cx = r.minChunkX(); cx <= r.maxChunkX(); cx++) {
            for (int cz = r.minChunkZ(); cz <= r.maxChunkZ(); cz++) {
                Key key = new Key(r.world(), chunkKey(cx, cz));
                List<T> list = buckets.get(key);
                if (list != null) {
                    list.removeIf(e -> idOf.apply(e) == id);
                    if (list.isEmpty()) {
                        buckets.remove(key);
                    }
                }
            }
        }
    }

    public void clear() {
        buckets.clear();
        byId.clear();
    }

    /** The entry containing the point, if any. Regions do not overlap, so there is at most one. */
    public Optional<T> at(String world, int x, int y, int z) {
        List<T> list = buckets.get(new Key(world, chunkKey(x >> 4, z >> 4)));
        if (list == null) {
            return Optional.empty();
        }
        for (T entry : list) {
            if (regionOf.apply(entry).contains(world, x, y, z)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public Optional<T> byId(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int size() {
        return byId.size();
    }
}
