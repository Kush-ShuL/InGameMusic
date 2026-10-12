package top.mc_plfd_host.ingamemusic.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** Independent vector-like Java2D UI, rasterized at framebuffer resolution. No vanilla widgets/font. */
final class RasterCanvas implements AutoCloseable {
    public static final int BG = 0xff10141d, PANEL = 0xff171e2a, CARD = 0xff202938,
            TEXT = 0xffedf3fb, MUTED = 0xff8998af, ACCENT = 0xff8ef0c5, DANGER = 0xffff8997;
    static final Font FONT = loadFont();
    public final BufferedImage image;
    public final Graphics2D g;
    public final int width, height;
    public RasterCanvas(int width, int height, double scale) {
        this.width = width; this.height = height;
        image = new BufferedImage((int) Math.ceil(width * scale), (int) Math.ceil(height * scale), BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics(); g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }
    private static Font loadFont() {
        try (InputStream input = RasterCanvas.class.getResourceAsStream("/assets/ingamemusic/font/notosanssc.ttf")) {
            if (input != null) return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (Exception ignored) { }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    }
    public void round(double x, double y, double w, double h, double radius, int color) {
        g.setColor(new Color(color, true)); g.fill(new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2));
    }
    public void gradient(double x, double y, double w, double h, double radius, int first, int last) {
        g.setPaint(new GradientPaint((float) x, (float) y, new Color(first, true), (float) (x + w), (float) (y + h), new Color(last, true)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, radius * 2, radius * 2));
    }
    public void line(double x, double y, double xx, double yy, float thickness, int color) {
        g.setColor(new Color(color, true)); g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x, y, xx, yy));
    }
    public void text(String text, double x, double baseline, float size, int color) {
        g.setFont(FONT.deriveFont(size)); g.setColor(new Color(color, true)); g.drawString(text == null ? "" : text, (float) x, (float) baseline);
    }
    public void fit(String text, double x, double baseline, float size, int color, int maxWidth) {
        g.setFont(FONT.deriveFont(size)); String display = text == null ? "" : text;
        if (g.getFontMetrics().stringWidth(display) > maxWidth) {
            int end = display.length();
            while (end > 0 && g.getFontMetrics().stringWidth(display.substring(0, end) + "…") > maxWidth) end = display.offsetByCodePoints(end, -1);
            display = display.substring(0, end) + "…";
        }
        text(display, x, baseline, size, color);
    }
    public void centered(String text, double x, double baseline, float size, int color) {
        g.setFont(FONT.deriveFont(size)); text(text, x - g.getFontMetrics().stringWidth(text) / 2.0, baseline, size, color);
    }
    public void glass(double x, double y, double w, double h, double radius, int tint) {
        var oldClip = g.getClip(); var oldPaint = g.getPaint(); var oldStroke = g.getStroke();
        var shape = new RoundRectangle2D.Double(x + .6, y + .6, w - 1.2, h - 1.2, radius * 2, radius * 2);
        g.clip(shape);
        g.setColor(new Color(tint | 0xff000000, true)); g.fill(shape);
        g.setPaint(new java.awt.LinearGradientPaint((float) x, (float) y, (float) x, (float) (y + h),
                new float[]{0, 1}, new Color[]{new Color(0x08ffffff, true), new Color(0x03243548, true)}));
        g.fill(shape);
        g.setColor(new Color(0x18243548, true));
        g.setStroke(new BasicStroke(.8f)); g.draw(shape);
        g.setClip(oldClip); g.setPaint(oldPaint); g.setStroke(oldStroke);
    }
    public void cover(BufferedImage cover, double x, double y, int size, int seed) {
        var clip = g.getClip(); g.clip(new RoundRectangle2D.Double(x, y, size, size, Math.min(18, size / 4.0), Math.min(18, size / 4.0)));
        if (cover != null) g.drawImage(cover, (int) x, (int) y, size, size, null);
        else {
            int tint = Color.HSBtoRGB(Math.floorMod(seed, 360) / 360f, .38f, .40f);
            gradient(x, y, size, size, 0, tint, 0xff172534);
            g.setColor(new Color(0x229af0d4, true));
            g.fill(new Ellipse2D.Double(x + size * .1, y + size * .1, size * .8, size * .8));
            g.setColor(new Color(0x337cedcd, true)); g.setStroke(new BasicStroke(Math.max(1, size * .015f)));
            for (int i = 0; i < 3; i++) g.draw(new Ellipse2D.Double(x + size * (.2 + i * .08), y + size * (.2 + i * .08), size * (.6 - i * .16), size * (.6 - i * .16)));
            g.setColor(new Color(ACCENT, true)); g.fill(new Ellipse2D.Double(x + size * .46, y + size * .46, size * .08, size * .08));
        }
        g.setClip(clip);
    }
    public void icon(String icon, double x, double y, double size, int color) {
        var old = g.getTransform(); g.translate(x, y); g.scale(size / 24, size / 24);
        g.setColor(new Color(color, true)); g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (icon) {
            case "play" -> { var p = new Path2D.Double(); p.moveTo(8, 4); p.lineTo(21, 12); p.lineTo(8, 20); p.closePath(); g.fill(p); }
            case "pause" -> { g.fillRoundRect(6, 4, 4, 16, 2, 2); g.fillRoundRect(15, 4, 4, 16, 2, 2); }
            case "next", "prev" -> {
                if (icon.equals("prev")) { g.translate(24, 0); g.scale(-1, 1); }
                var p = new Path2D.Double(); p.moveTo(5, 5); p.lineTo(16, 12); p.lineTo(5, 19); p.closePath(); g.fill(p); g.drawLine(19, 5, 19, 19);
            }
            case "plus" -> { g.drawLine(12, 5, 12, 19); g.drawLine(5, 12, 19, 12); }
            case "close" -> { g.drawLine(6, 6, 18, 18); g.drawLine(18, 6, 6, 18); }
            case "search" -> { g.drawOval(4, 3, 12, 12); g.drawLine(15, 15, 21, 21); }
            case "folder" -> { var p = new Path2D.Double(); p.moveTo(3, 7); p.lineTo(3, 4); p.lineTo(10, 4); p.lineTo(13, 7); p.lineTo(21, 7); p.lineTo(21, 20); p.lineTo(3, 20); p.closePath(); g.draw(p); }
            case "music" -> { g.drawLine(10, 17, 10, 5); g.drawLine(10, 5, 20, 3); g.drawLine(20, 3, 20, 15); g.fillOval(4, 15, 7, 6); g.fillOval(14, 13, 7, 6); }
            case "check" -> { g.drawLine(5, 12, 10, 17); g.drawLine(10, 17, 20, 6); }
            case "volume" -> { var p = new Path2D.Double(); p.moveTo(3, 9); p.lineTo(7, 9); p.lineTo(12, 5); p.lineTo(12, 19); p.lineTo(7, 15); p.lineTo(3, 15); p.closePath(); g.draw(p); g.drawArc(10, 5, 11, 14, -60, 120); }
            case "grid" -> { for (int xx : new int[]{4, 14}) for (int yy : new int[]{4, 14}) g.drawRoundRect(xx, yy, 6, 6, 2, 2); }
            case "compact" -> { g.drawLine(4, 7, 20, 7); g.drawLine(7, 12, 17, 12); g.drawLine(10, 17, 14, 17); }
            case "expand" -> { g.drawLine(4, 9, 4, 4); g.drawLine(4, 4, 9, 4); g.drawLine(15, 20, 20, 20); g.drawLine(20, 20, 20, 15); }
            case "settings" -> { g.drawOval(4, 4, 16, 16); g.drawOval(9, 9, 6, 6); for (int i = 0; i < 8; i++) { g.rotate(Math.PI / 4, 12, 12); g.drawLine(12, 1, 12, 4); } }
            default -> { g.drawLine(5, 6, 19, 6); g.drawLine(5, 12, 19, 12); g.drawLine(5, 18, 19, 18); }
        }
        g.setTransform(old);
    }
    public static String time(double seconds) {
        int total = (int) Math.max(0, seconds); return String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }
    @Override public void close() { g.dispose(); }
}
