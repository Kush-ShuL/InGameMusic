package top.mc_plfd_host.ingamemusic.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Runs on the raster worker. Every text sprite is produced at its final physical pixel size. */
public final class SpriteRasterizer {
    public record Key(String kind, String text, int width, int height, int radius, int first, int last, BufferedImage image, int seed) { }
    public record Bitmap(int width, int height, int anchorX, int anchorY, int[] rgba) { }
    private SpriteRasterizer() { }
    public static Bitmap render(Key key) {
        int width = Math.max(1, key.width), height = Math.max(1, key.height), anchorX = 0, anchorY = 0;
        if (key.kind.equals("text")) {
            var font = RasterCanvas.FONT.deriveFont((float) height);
            var metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            var graphics = metricsImage.createGraphics(); graphics.setFont(font);
            var metrics = graphics.getFontMetrics();
            width = metrics.stringWidth(key.text) + 6; anchorY = metrics.getAscent() + 3; anchorX = 3;
            height = metrics.getHeight() + 6; graphics.dispose();
        }
        try (RasterCanvas c = new RasterCanvas(width, height, 1)) {
            switch (key.kind) {
                case "text" -> c.text(key.text, anchorX, anchorY, key.height, 0xffffffff);
                case "disk" -> { c.g.setColor(Color.WHITE); c.g.fillOval(0, 0, width, height); }
                case "icon" -> c.icon(key.text, 0, 0, width, 0xffffffff);
                case "cover" -> c.cover(key.image, 0, 0, width, key.seed);
                case "gradient" -> c.gradient(0, 0, width, height, key.radius, key.first, key.last);
                case "glass" -> c.glass(0, 0, width, height, key.radius, key.first);
                default -> throw new IllegalArgumentException(key.kind);
            }
            int[] pixels = ((DataBufferInt) c.image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i]; pixels[i] = (argb & 0xff00ff00) | (argb >>> 16 & 255) | (argb & 255) << 16;
            }
            return new Bitmap(width, height, anchorX, anchorY, pixels);
        }
    }
}
