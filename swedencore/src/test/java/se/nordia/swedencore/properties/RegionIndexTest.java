package se.nordia.swedencore.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionIndexTest {

    private record Entry(long id, Region region) {
    }

    private final RegionIndex<Entry> index = new RegionIndex<>(Entry::region, Entry::id);

    @Test
    void findsEntriesAcrossChunkBoundariesIncludingNegativeCoordinates() {
        Entry e = new Entry(1, Region.of("world", -20, 60, -20, 20, 80, 20));
        index.put(e);
        assertThat(index.at("world", -20, 60, -20)).contains(e);
        assertThat(index.at("world", 20, 80, 20)).contains(e);
        assertThat(index.at("world", 0, 70, 0)).contains(e);
        assertThat(index.at("world", 21, 70, 0)).isEmpty();
        assertThat(index.at("world", 0, 81, 0)).isEmpty();
        assertThat(index.at("world_nether", 0, 70, 0)).isEmpty();
    }

    @Test
    void updateAndRemove() {
        index.put(new Entry(1, Region.of("world", 0, 0, 0, 10, 10, 10)));
        index.put(new Entry(1, Region.of("world", 100, 0, 100, 110, 10, 110)));
        assertThat(index.at("world", 5, 5, 5)).isEmpty();
        assertThat(index.at("world", 105, 5, 105)).isPresent();
        index.remove(1);
        assertThat(index.at("world", 105, 5, 105)).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    void regionMath() {
        Region a = Region.of("w", 0, 0, 0, 9, 9, 9);
        assertThat(a.volume()).isEqualTo(1000);
        assertThat(a.intersects(Region.of("w", 9, 9, 9, 20, 20, 20))).isTrue();
        assertThat(a.intersects(Region.of("w", 10, 0, 0, 20, 9, 9))).isFalse();
        assertThat(a.intersects(Region.of("other", 0, 0, 0, 9, 9, 9))).isFalse();
        assertThatThrownBy(() -> new Region("w", 1, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
