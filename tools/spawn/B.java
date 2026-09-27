import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Block-state helpers and a rotatable local frame for building things facing any direction. */
final class B {

    enum Dir {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        final int dx, dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        String n() {
            return name().toLowerCase(Locale.ROOT);
        }

        Dir cw(int times) {
            return values()[Math.floorMod(ordinal() + times, 4)];
        }

        Dir opposite() {
            return cw(2);
        }

        static Dir of(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    static final Set<String> PLANTS = Set.of("short_grass", "tall_grass", "fern", "large_fern", "bush", "firefly_bush",
            "pink_petals", "wildflowers", "leaf_litter", "seagrass", "tall_seagrass", "dandelion", "poppy", "blue_orchid",
            "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "sweet_berry_bush", "lilac", "rose_bush", "peony", "sunflower", "kelp", "kelp_plant",
            "sea_pickle", "lily_pad", "moss_carpet", "brown_mushroom", "red_mushroom", "cave_vines", "cave_vines_plant",
            "vine", "glow_lichen", "hanging_roots", "spore_blossom", "dead_bush", "torchflower");

    static boolean isPlant(String b) {
        return PLANTS.contains(b) || b.endsWith("_carpet") || b.startsWith("potted_");
    }

    static final Set<String> SUPPORTED = Set.of("lantern", "soul_lantern", "bell", "campfire", "lectern", "flower_pot",
            "candle", "white_candle", "torch", "wall_torch", "ladder", "lever", "decorated_pot", "chain", "item_frame");

    /** Blocks placed in the last phase, after everything they may rest on or hang from exists. */
    static boolean needsSupport(String b) {
        return isPlant(b) || SUPPORTED.contains(b) || b.endsWith("_door") || b.endsWith("_banner") || b.endsWith("_sign")
               || b.endsWith("_button") || b.endsWith("_candle") || b.endsWith("_torch") || b.endsWith("_pressure_plate");
    }

    static final Set<String> TWO_HALVES = Set.of("tall_grass", "large_fern", "lilac", "rose_bush", "peony", "sunflower",
            "tall_seagrass");

    static boolean strictPlacement(String state) {
        String b = base(state);
        return b.endsWith("_door") || TWO_HALVES.contains(b) || b.startsWith("cave_vines");
    }

    // ------------------------------------------------------------------ state builders

    static String stairs(String mat, Dir facing) {
        return mat + "_stairs[facing=" + facing.n() + ",half=bottom]";
    }

    static String stairsTop(String mat, Dir facing) {
        return mat + "_stairs[facing=" + facing.n() + ",half=top]";
    }

    static String slab(String mat) {
        return mat + "_slab[type=bottom]";
    }

    static String slabTop(String mat) {
        return mat + "_slab[type=top]";
    }

    static String log(String mat, char axis) {
        return mat + "[axis=" + axis + "]";
    }

    static String trapdoor(String mat, Dir facing, boolean open, boolean top) {
        return mat + "_trapdoor[facing=" + facing.n() + ",open=" + open + ",half=" + (top ? "top" : "bottom") + "]";
    }

    static String facing(String block, Dir d) {
        return block + "[facing=" + d.n() + "]";
    }

    static final List<String> FLOWERS = List.of("poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium",
            "lily_of_the_valley", "red_tulip", "white_tulip", "blue_orchid", "pink_tulip", "orange_tulip");

    // ------------------------------------------------------------------ rotation

    /** Rotates facing/axis/rotation properties of a state clockwise by {@code turns} quarter turns. */
    static String rotate(String state, int turns) {
        turns = Math.floorMod(turns, 4);
        if (turns == 0 || state.indexOf('[') < 0) return state;
        int open = state.indexOf('[');
        String name = state.substring(0, open);
        String[] props = state.substring(open + 1, state.length() - 1).split(",");
        StringBuilder sb = new StringBuilder(name).append('[');
        for (int i = 0; i < props.length; i++) {
            String[] kv = props[i].split("=");
            String k = kv[0], v = kv[1];
            switch (k) {
                case "facing" -> {
                    if (!v.equals("up") && !v.equals("down")) v = Dir.of(v).cw(turns).n();
                }
                case "axis" -> {
                    if ((turns & 1) == 1 && !v.equals("y")) v = v.equals("x") ? "z" : "x";
                }
                case "rotation" -> v = String.valueOf(Math.floorMod(Integer.parseInt(v) + 4 * turns, 16));
                default -> {}
            }
            if (i > 0) sb.append(',');
            sb.append(k).append('=').append(v);
        }
        return sb.append(']').toString();
    }

    /**
     * A local coordinate frame: local +z is the building's front ("south" in local terms). {@code turns} rotates the
     * front clockwise: 0 = front faces south, 1 = west, 2 = north, 3 = east.
     */
    record Frame(int ox, int oy, int oz, int turns) {

        static Frame facing(int ox, int oy, int oz, Dir front) {
            int t = switch (front) {
                case SOUTH -> 0;
                case WEST -> 1;
                case NORTH -> 2;
                case EAST -> 3;
            };
            return new Frame(ox, oy, oz, t);
        }

        int wx(int lx, int lz) {
            return ox + switch (turns & 3) {
                case 0 -> lx;
                case 1 -> -lz;
                case 2 -> -lx;
                default -> lz;
            };
        }

        int wz(int lx, int lz) {
            return oz + switch (turns & 3) {
                case 0 -> lz;
                case 1 -> lx;
                case 2 -> -lz;
                default -> -lx;
            };
        }

        void set(int lx, int ly, int lz, String state) {
            Canvas.set(wx(lx, lz), oy + ly, wz(lx, lz), rotate(state, turns));
        }

        void setIfAir(int lx, int ly, int lz, String state) {
            Canvas.setIfAir(wx(lx, lz), oy + ly, wz(lx, lz), rotate(state, turns));
        }

        String get(int lx, int ly, int lz) {
            return Canvas.get(wx(lx, lz), oy + ly, wz(lx, lz));
        }

        boolean isAir(int lx, int ly, int lz) {
            return Canvas.isAir(wx(lx, lz), oy + ly, wz(lx, lz));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                    for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, state);
        }

        void use(int x0, int z0, int x1, int z1) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) Canvas.use(wx(x, z), wz(x, z), wx(x, z), wz(x, z));
        }

        /** Lowest ground (world-relative y) under a local rectangle. */
        int minGround(int x0, int z0, int x1, int z1) {
            int m = Integer.MAX_VALUE;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) m = Math.min(m, Canvas.ground(wx(x, z), wz(x, z)));
            return m;
        }

        int maxGround(int x0, int z0, int x1, int z1) {
            int m = Integer.MIN_VALUE;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) m = Math.max(m, Canvas.ground(wx(x, z), wz(x, z)));
            return m;
        }

        /** Fills from the terrain up to local y (exclusive) so a structure never floats. */
        void foundation(int x0, int z0, int x1, int z1, int topLocal, String mat) {
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    int g = Canvas.ground(wx(x, z), wz(x, z));
                    for (int y = Math.min(g, oy + topLocal - 1) - 4; y < oy + topLocal; y++)
                        Canvas.set(wx(x, z), y, wz(x, z), mat);
                }
        }

        void summon(double lx, double ly, double lz, String entity, String nbt) {
            double x = ox + 0.5 + switch (turns & 3) {
                case 0 -> lx;
                case 1 -> -lz;
                case 2 -> -lx;
                default -> lz;
            };
            double z = oz + 0.5 + switch (turns & 3) {
                case 0 -> lz;
                case 1 -> lx;
                case 2 -> -lz;
                default -> -lx;
            };
            Canvas.ENTITIES.add(String.format(Locale.ROOT, "summon %s ~%.2f ~%.2f ~%.2f %s", entity, x - 0.5, oy + ly, z - 0.5,
                    nbt));
        }
    }
}
