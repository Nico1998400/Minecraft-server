import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Makes the 64x64 server icon (the picture in the multiplayer server list) from the logo in {@code branding/logo-source.png}.
 * The source has a checkerboard baked in instead of real transparency, so the grey background is flood-filled away from the
 * edges. Run from the repository root: {@code java branding/tools/MakeServerIcon.java}
 */
public final class MakeServerIcon {

    private static final int SIZE = 64;

    public static void main(String[] args) throws IOException {
        BufferedImage source = ImageIO.read(new File("branding/logo-source.png"));
        BufferedImage icon = fit(removeBackground(source));
        ImageIO.write(icon, "png", new File("branding/server-icon.png"));
        System.out.println("Wrote branding/server-icon.png (" + SIZE + "x" + SIZE + ")");
    }

    private static int spread(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static BufferedImage removeBackground(BufferedImage in) {
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
            if (x < 0 || y < 0 || x >= w || y >= h || background[y * w + x] || spread(in.getRGB(x, y)) > 30) {
                continue;
            }
            background[y * w + x] = true;
            queue.add(new int[]{x + 1, y});
            queue.add(new int[]{x - 1, y});
            queue.add(new int[]{x, y + 1});
            queue.add(new int[]{x, y - 1});
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!background[y * w + x]) {
                    out.setRGB(x, y, in.getRGB(x, y) | 0xFF000000);
                }
            }
        }
        return out;
    }

    /**
     * Crops to the logo, centers it on a square canvas (no wasted margin), shrinks it to 64x64 by averaging every source pixel
     * exactly (area resampling, much sharper than the usual bicubic), then sharpens a little so the details survive.
     */
    private static BufferedImage fit(BufferedImage in) {
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
        int side = Math.max(cropW, cropH);
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.drawImage(in.getSubimage(minX, minY, cropW, cropH), (side - cropW) / 2, (side - cropH) / 2, null);
        g.dispose();
        return sharpen(areaResample(square, SIZE), 0.9);
    }

    /** Every destination pixel is the exact average of the source area it covers (alpha-weighted). */
    private static BufferedImage areaResample(BufferedImage in, int size) {
        double scale = in.getWidth() / (double) size;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int oy = 0; oy < size; oy++) {
            for (int ox = 0; ox < size; ox++) {
                double x0 = ox * scale;
                double x1 = (ox + 1) * scale;
                double y0 = oy * scale;
                double y1 = (oy + 1) * scale;
                double a = 0;
                double r = 0;
                double gr = 0;
                double b = 0;
                double total = 0;
                for (int y = (int) Math.floor(y0); y < Math.ceil(y1) && y < in.getHeight(); y++) {
                    double wy = Math.min(y + 1, y1) - Math.max(y, y0);
                    for (int x = (int) Math.floor(x0); x < Math.ceil(x1) && x < in.getWidth(); x++) {
                        double w = wy * (Math.min(x + 1, x1) - Math.max(x, x0));
                        int p = in.getRGB(x, y);
                        double pa = (p >>> 24) / 255.0;
                        a += w * pa;
                        r += w * pa * ((p >> 16) & 255);
                        gr += w * pa * ((p >> 8) & 255);
                        b += w * pa * (p & 255);
                        total += w;
                    }
                }
                if (a <= 0) {
                    continue;
                }
                int alpha = (int) Math.round(255 * a / total);
                int red = (int) Math.round(r / a);
                int green = (int) Math.round(gr / a);
                int blue = (int) Math.round(b / a);
                out.setRGB(ox, oy, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return out;
    }

    /** Unsharp mask on the colour channels: adds back the difference between the picture and a slightly blurred copy. */
    private static BufferedImage sharpen(BufferedImage in, double amount) {
        int w = in.getWidth();
        int h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[][] kernel = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = in.getRGB(x, y);
                int alpha = p >>> 24;
                if (alpha == 0) {
                    continue;
                }
                double[] blur = new double[3];
                double weight = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = Math.clamp(x + dx, 0, w - 1);
                        int ny = Math.clamp(y + dy, 0, h - 1);
                        int q = in.getRGB(nx, ny);
                        if ((q >>> 24) == 0) {
                            continue;
                        }
                        int k = kernel[dy + 1][dx + 1];
                        blur[0] += k * ((q >> 16) & 255);
                        blur[1] += k * ((q >> 8) & 255);
                        blur[2] += k * (q & 255);
                        weight += k;
                    }
                }
                int[] c = {(p >> 16) & 255, (p >> 8) & 255, p & 255};
                for (int i = 0; i < 3; i++) {
                    double soft = weight == 0 ? c[i] : blur[i] / weight;
                    c[i] = (int) Math.clamp(Math.round(c[i] + amount * (c[i] - soft)), 0, 255);
                }
                out.setRGB(x, y, (alpha << 24) | (c[0] << 16) | (c[1] << 8) | c[2]);
            }
        }
        return out;
    }
}
