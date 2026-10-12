import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Turns the original quest art in {@code source/} into the 256x256 item textures the resource pack uses:
 * <ul>
 *   <li>{@code quest_available} (gold "!"): the original has a checkerboard baked in instead of real transparency, so the
 *       grey background is flood-filled away from the edges and the soft glow is given a matching alpha;</li>
 *   <li>{@code quest_ready} (green check): already transparent, only cropped and centered.</li>
 * </ul>
 * Run from the repository root: {@code java swedencore/resourcepack/tools/MakeEmblems.java}
 */
public final class MakeEmblems {

    private static final int OUT = 256;

    public static void main(String[] args) throws IOException {
        Path pack = Path.of("swedencore/resourcepack");
        Path out = pack.resolve("assets/nordia/textures/item");
        Files.createDirectories(out);
        BufferedImage gold = ImageIO.read(pack.resolve("source/quest_available_source.png").toFile());
        ImageIO.write(finish(removeCheckerboard(gold)), "png", out.resolve("quest_available.png").toFile());
        BufferedImage green = ImageIO.read(pack.resolve("source/quest_ready_source.png").toFile());
        ImageIO.write(finish(toArgb(green)), "png", out.resolve("quest_ready.png").toFile());
        System.out.println("Wrote emblems to " + out.toAbsolutePath());
    }

    private static BufferedImage toArgb(BufferedImage in) {
        BufferedImage copy = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static int spread(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    /** Removes a grey checkerboard background: flood fill from the borders through unsaturated pixels, then feather the glow. */
    private static BufferedImage removeCheckerboard(BufferedImage in) {
        int w = in.getWidth();
        int h = in.getHeight();
        boolean[] background = new boolean[w * h];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            queue.add(new int[]{x, 0});
            queue.add(new int[]{x, h - 1});
        }
        for (int y = 0; y < h; y++) {
            queue.add(new int[]{0, y});
            queue.add(new int[]{w - 1, y});
        }
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            int x = p[0];
            int y = p[1];
            if (x < 0 || y < 0 || x >= w || y >= h || background[y * w + x] || spread(in.getRGB(x, y)) > 38) {
                continue;
            }
            background[y * w + x] = true;
            queue.add(new int[]{x + 1, y});
            queue.add(new int[]{x - 1, y});
            queue.add(new int[]{x, y + 1});
            queue.add(new int[]{x, y - 1});
        }
        // distance (in pixels) to the nearest background pixel, up to a small feather width
        int feather = 14;
        int[] distance = new int[w * h];
        java.util.Arrays.fill(distance, Integer.MAX_VALUE);
        ArrayDeque<int[]> wave = new ArrayDeque<>();
        for (int i = 0; i < background.length; i++) {
            if (background[i]) {
                distance[i] = 0;
                wave.add(new int[]{i % w, i / w});
            }
        }
        while (!wave.isEmpty()) {
            int[] p = wave.poll();
            int d = distance[p[1] * w + p[0]];
            if (d >= feather) {
                continue;
            }
            int[][] next = {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1}};
            for (int[] n : next) {
                if (n[0] >= 0 && n[1] >= 0 && n[0] < w && n[1] < h && distance[n[1] * w + n[0]] > d + 1) {
                    distance[n[1] * w + n[0]] = d + 1;
                    wave.add(n);
                }
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = in.getRGB(x, y);
                if (background[y * w + x]) {
                    continue;
                }
                double alpha = 1.0;
                if (distance[y * w + x] <= feather) {
                    // near the background: the glow is mixed with the grey checkerboard, so its saturation tells how opaque it is
                    alpha = Math.clamp((spread(rgb) - 30) / 150.0, 0.0, 1.0);
                }
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                if (alpha > 0 && alpha < 1) {
                    // undo the mix with the (about 208 grey) background
                    r = (int) Math.clamp((r - (1 - alpha) * 208) / alpha, 0, 255);
                    g = (int) Math.clamp((g - (1 - alpha) * 208) / alpha, 0, 255);
                    b = (int) Math.clamp((b - (1 - alpha) * 208) / alpha, 0, 255);
                }
                out.setRGB(x, y, ((int) Math.round(alpha * 255) << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    /** Crops to the visible part, centers it on a square canvas with a little padding, and scales to 256x256. */
    private static BufferedImage finish(BufferedImage in) {
        int minX = in.getWidth();
        int minY = in.getHeight();
        int maxX = 0;
        int maxY = 0;
        for (int y = 0; y < in.getHeight(); y++) {
            for (int x = 0; x < in.getWidth(); x++) {
                if ((in.getRGB(x, y) >>> 24) > 12) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        int side = (int) Math.round(Math.max(cropW, cropH) * 1.08);
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.drawImage(in.getSubimage(minX, minY, cropW, cropH), (side - cropW) / 2, (side - cropH) / 2, null);
        g.dispose();
        // scale down in halving steps so the pixel art stays crisp
        BufferedImage current = square;
        while (current.getWidth() / 2 >= OUT) {
            current = scale(current, current.getWidth() / 2);
        }
        return scale(current, OUT);
    }

    private static BufferedImage scale(BufferedImage in, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(in, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
