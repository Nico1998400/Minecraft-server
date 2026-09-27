/**
 * Bounds of the region being generated (relative to the region origin) and where that origin sits in the world. Set
 * once in {@code main} before any other generator class is touched: {@link Canvas} and the terrain arrays size
 * themselves from these values when they are first loaded.
 */
final class Box {

    static String name = "spawn";          // data pack function folder and marker tag suffix
    static int minX = -128, maxX = 127, minZ = -128, maxZ = 127, minY = -30, maxY = 112;
    static int worldX = -572, worldZ = 378; // world position of relative (0, 0); relative y 0 = world y 64

    static void region(String n, int x0, int x1, int z0, int z1, int y0, int y1, int wx, int wz) {
        name = n;
        minX = x0;
        maxX = x1;
        minZ = z0;
        maxZ = z1;
        minY = y0;
        maxY = y1;
        worldX = wx;
        worldZ = wz;
    }
}
