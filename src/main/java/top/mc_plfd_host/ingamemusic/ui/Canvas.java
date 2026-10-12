package top.mc_plfd_host.ingamemusic.ui;

import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.*;

/** Cheap immutable drawing commands. Rasterization is used only for sprites and offline previews. */
public final class Canvas implements AutoCloseable {
    public static final int BG = 0xfff5f7fa, PANEL = 0xfffdfefe, CARD = 0xffeaf0f4,
            TEXT = 0xff243348, MUTED = 0xff64748b, ACCENT = 0xff187b70, DANGER = 0xffb8324b,
            SELECTED = 0xffdeeee9, BORDER = 0xffdce3eb, TRACK = 0xffd2dce5;
    public sealed interface Command permits Round, Gradient, Glass, Line, Label, Cover, Icon, Clip, Unclip { }
    public record Round(double x, double y, double w, double h, double radius, int color) implements Command { }
    public record Gradient(double x, double y, double w, double h, double radius, int first, int last) implements Command { }
    public record Glass(double x, double y, double w, double h, double radius, int tint) implements Command { }
    public record Line(double x, double y, double xx, double yy, float thickness, int color) implements Command { }
    public record Label(String text, double x, double baseline, float size, int color) implements Command { }
    public record Cover(BufferedImage image, double x, double y, int size, int seed) implements Command { }
    public record Icon(String name, double x, double y, double size, int color) implements Command { }
    public record Clip(int x, int y, int w, int h) implements Command { }
    public record Unclip() implements Command { }
    public record Scene(int width, int height, List<Command> commands) {
        public Scene { commands = List.copyOf(commands); }
        public BufferedImage rasterize(double scale) {
            try (RasterCanvas c = new RasterCanvas(width, height, scale)) {
                var clips = new ArrayList<Shape>();
                for (Command command : commands) switch (command) {
                    case Round r -> c.round(r.x, r.y, r.w, r.h, r.radius, r.color);
                    case Gradient r -> c.gradient(r.x, r.y, r.w, r.h, r.radius, r.first, r.last);
                    case Glass r -> c.glass(r.x, r.y, r.w, r.h, r.radius, r.tint);
                    case Line r -> c.line(r.x, r.y, r.xx, r.yy, r.thickness, r.color);
                    case Label r -> c.text(r.text, r.x, r.baseline, r.size, r.color);
                    case Cover r -> c.cover(r.image, r.x, r.y, r.size, r.seed);
                    case Icon r -> c.icon(r.name, r.x, r.y, r.size, r.color);
                    case Clip r -> { clips.add(c.g.getClip()); c.g.clipRect(r.x, r.y, r.w, r.h); }
                    case Unclip ignored -> c.g.setClip(clips.removeLast());
                }
                return c.image;
            }
        }
    }
    private record Measure(String text, float size) { }
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, false);
    private static final Map<Measure, Integer> WIDTHS = Collections.synchronizedMap(new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Measure, Integer> e) { return size() > 4096; }
    });
    private final int width, height;
    private final List<Command> commands = new ArrayList<>(256);
    public Canvas(int width, int height) { this.width = width; this.height = height; }
    public static void warmup() { measure("InGame Music", 14); }
    public static int measure(String text, float size) {
        return WIDTHS.computeIfAbsent(new Measure(text, size), key -> (int) Math.ceil(RasterCanvas.FONT.deriveFont(size).getStringBounds(text, METRICS).getWidth()));
    }
    public Scene finish() { return new Scene(width, height, commands); }
    public void round(double x, double y, double w, double h, double radius, int color) { if (w > 0 && h > 0) commands.add(new Round(x, y, w, h, radius, color)); }
    public void gradient(double x, double y, double w, double h, double radius, int first, int last) { commands.add(new Gradient(x, y, w, h, radius, first, last)); }
    public void glass(double x, double y, double w, double h, double radius, int tint) { if (w > 0 && h > 0) commands.add(new Glass(x, y, w, h, radius, tint)); }
    public void line(double x, double y, double xx, double yy, float thickness, int color) { commands.add(new Line(x, y, xx, yy, thickness, color)); }
    public void text(String text, double x, double baseline, float size, int color) { if (text != null && !text.isEmpty()) commands.add(new Label(text, x, baseline, size, color)); }
    public void fit(String text, double x, double baseline, float size, int color, int maxWidth) {
        String display = text == null ? "" : text;
        if (measure(display, size) > maxWidth) {
            int end = display.length();
            while (end > 0 && measure(display.substring(0, end) + "…", size) > maxWidth) end = display.offsetByCodePoints(end, -1);
            display = display.substring(0, end) + "…";
        }
        text(display, x, baseline, size, color);
    }
    public void centered(String text, double x, double baseline, float size, int color) { text(text, x - measure(text, size) / 2.0, baseline, size, color); }
    public void cover(BufferedImage cover, double x, double y, int size, int seed) { commands.add(new Cover(cover, x, y, size, seed)); }
    public void icon(String icon, double x, double y, double size, int color) { commands.add(new Icon(icon, x, y, size, color)); }
    public void clip(int x, int y, int w, int h) { commands.add(new Clip(x, y, w, h)); }
    public void unclip() { commands.add(new Unclip()); }
    public static String time(double seconds) { return RasterCanvas.time(seconds); }
    @Override public void close() { }
}
