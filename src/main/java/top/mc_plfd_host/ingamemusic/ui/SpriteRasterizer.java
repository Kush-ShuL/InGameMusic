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
            if (!key.text.isEmpty()) {
                var layout = UiFonts.layout(key.text, height, (key.last & 1) != 0);
                var ink = layout.getPixelBounds(UiFonts.METRICS, 0, 0);
                int left = Math.min(0, ink.x), top = Math.min(-(int) Math.ceil(layout.getAscent()), ink.y);
                int right = Math.max((int) Math.ceil(layout.getAdvance()), ink.x + ink.width);
                int bottom = Math.max((int) Math.ceil(layout.getDescent() + layout.getLeading()), ink.y + ink.height);
                width = right - left + 6; height = bottom - top + 6;
                anchorX = 3 - left; anchorY = 3 - top;
            }
        }
        try (RasterCanvas c = new RasterCanvas(width, height, 1)) {
            switch (key.kind) {
                case "text" -> c.text(key.text, anchorX, anchorY, key.height, 0xffffffff, (key.last & 1) != 0, false);
                case "disk" -> { c.g.setColor(Color.WHITE); c.g.fillOval(0, 0, width, height); }
                case "icon" -> c.icon(key.text, 0, 0, width, 0xffffffff, false);
                case "cover" -> c.cover(key.image, 0, 0, width, key.seed);
                case "gradient" -> c.gradient(0, 0, width, height, key.radius, key.first, key.last);
                case "clear-control" -> c.clearControl(0, 0, width, height, key.radius, key.first == Canvas.ACCENT || key.first == Canvas.SELECTED);
                case "glass" -> c.glass(0, 0, width, height, key.radius, key.first);
                default -> throw new IllegalArgumentException(key.kind);
            }
            int[] pixels = ((DataBufferInt) c.image.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i];
                // White alpha masks, including transparent texels: filtering must never
                // pull black RGB into antialiased glyph edges. Shadows are separate draws.
                if (key.kind.equals("text") || key.kind.equals("icon")) argb |= 0x00ffffff;
                pixels[i] = (argb & 0xff00ff00) | (argb >>> 16 & 255) | (argb & 255) << 16;
            }
            return new Bitmap(width, height, anchorX, anchorY, pixels);
        }
    }
}
