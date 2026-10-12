package top.mc_plfd_host.ingamemusic.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import java.util.*;
import java.util.concurrent.*;
import static top.mc_plfd_host.ingamemusic.ui.SpriteRasterizer.*;

/** Cached GPU sprites; no full-window rasterization or per-frame pixel conversion/upload. */
public final class CachedGuiRenderer implements AutoCloseable {
    private static final int MAX_ENTRIES = 768, UPLOAD_BUDGET = 4 * 1024 * 1024;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "InGameMusic-sprites"); t.setDaemon(true); return t; });
    private static final class Entry {
        CompletableFuture<Bitmap> future;
        TextureSurface texture;
        int width, height, anchorX, anchorY;
        long frame;
    }
    private final Map<Key, Entry> cache = new LinkedHashMap<>(256, .75f, true);
    private long frame, serial, bytes;
    private int uploaded, uploadCount;
    private double sx, sy, ox, oy;
    public CachedGuiRenderer() { worker.submit(Canvas::warmup); }
    public void draw(Canvas.Scene scene, DrawContext context, double guiX, double guiY, double designScale) {
        var window = MinecraftClient.getInstance().getWindow();
        double ratioX = window.getFramebufferWidth() / (double) window.getScaledWidth();
        double ratioY = window.getFramebufferHeight() / (double) window.getScaledHeight();
        sx = designScale * ratioX; sy = designScale * ratioY;
        ox = Math.rint(guiX * ratioX); oy = Math.rint(guiY * ratioY);
        frame++; uploaded = 0; uploadCount = 0; evict();
        var matrix = context.getMatrices(); matrix.pushMatrix(); matrix.scale((float) (1 / ratioX), (float) (1 / ratioY));
        try {
            for (var command : scene.commands()) switch (command) {
                case Canvas.Round r -> {
                    if (r.w() <= 8 || r.h() <= 6) preciseRound(context, ox + r.x() * sx, oy + r.y() * sy, r.w() * sx, r.h() * sy, r.radius() * Math.min(sx, sy), r.color());
                    else round(context, px(r.x()), py(r.y()), px(r.x() + r.w()) - px(r.x()), py(r.y() + r.h()) - py(r.y()), (int) Math.round(r.radius() * Math.min(sx, sy)), r.color());
                }
                case Canvas.Line r -> {
                    int x1 = px(Math.min(r.x(), r.xx())), y1 = py(Math.min(r.y(), r.yy()));
                    int x2 = px(Math.max(r.x(), r.xx())), y2 = py(Math.max(r.y(), r.yy()));
                    int thick = Math.max(1, (int) Math.round(r.thickness() * Math.min(sx, sy)));
                    if (x1 == x2) context.fill(x1 - thick / 2, y1, x1 - thick / 2 + thick, Math.max(y1 + 1, y2), r.color());
                    else context.fill(x1, y1 - thick / 2, x2, y1 - thick / 2 + thick, r.color());
                }
                case Canvas.Label r -> {
                    var key = new Key("text", r.text(), 0, Math.max(1, (int) Math.round(r.size() * sy)), 0, 0, 0, null, 0);
                    sprite(context, key, px(r.x()), py(r.baseline()), r.color());
                }
                case Canvas.Icon r -> {
                    int size = Math.max(1, (int) Math.round(r.size() * Math.min(sx, sy)));
                    sprite(context, new Key("icon", r.name(), size, size, 0, 0, 0, null, 0), px(r.x()), py(r.y()), r.color());
                }
                case Canvas.Cover r -> {
                    int size = Math.max(1, (int) Math.round(r.size() * Math.min(sx, sy)));
                    sprite(context, new Key("cover", "", size, size, 0, 0, 0, r.image(), r.seed()), px(r.x()), py(r.y()), -1);
                }
                case Canvas.Gradient r -> {
                    int w = Math.max(1, px(r.x() + r.w()) - px(r.x())), h = Math.max(1, py(r.y() + r.h()) - py(r.y()));
                    sprite(context, new Key("gradient", "", w, h, (int) Math.round(r.radius() * Math.min(sx, sy)), r.first(), r.last(), null, 0), px(r.x()), py(r.y()), -1);
                }
                case Canvas.Glass r -> {
                    int w = Math.max(1, px(r.x() + r.w()) - px(r.x())), h = Math.max(1, py(r.y() + r.h()) - py(r.y()));
                    sprite(context, new Key("glass", "", w, h, Math.max(1, (int) Math.round(r.radius() * Math.min(sx, sy))), r.tint(), 0, null, 0), px(r.x()), py(r.y()), -1);
                }
                case Canvas.Clip r -> context.enableScissor(px(r.x()), py(r.y()), px(r.x() + r.w()), py(r.y() + r.h()));
                case Canvas.Unclip ignored -> context.disableScissor();
            }
        } finally { matrix.popMatrix(); }
    }
    private int px(double x) { return (int) Math.round(ox + x * sx); }
    private int py(double y) { return (int) Math.round(oy + y * sy); }
    private Entry get(Key key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            entry = new Entry();
            // Text must be drawable on its first frame, including counters, typing and cache eviction.
            // Only these small sprites bypass the background queue; unchanged strings remain cached.
            if (key.kind().equals("text")) upload(entry, SpriteRasterizer.render(key));
            else entry.future = CompletableFuture.supplyAsync(() -> SpriteRasterizer.render(key), worker);
            cache.put(key, entry);
        }
        entry.frame = frame;
        if (entry.texture == null && entry.future != null && entry.future.isDone() && !entry.future.isCompletedExceptionally() && uploaded < UPLOAD_BUDGET && uploadCount < 32) {
            Bitmap bitmap = entry.future.getNow(null);
            if (bitmap != null) {
                upload(entry, bitmap); entry.future = null;
            }
        }
        return entry;
    }
    private void upload(Entry entry, Bitmap bitmap) {
        entry.texture = new TextureSurface("sprite_" + serial++);
        entry.texture.update(bitmap.width(), bitmap.height(), bitmap.rgba());
        entry.width = bitmap.width(); entry.height = bitmap.height(); entry.anchorX = bitmap.anchorX(); entry.anchorY = bitmap.anchorY();
        int amount = bitmap.rgba().length * 4; uploaded += amount; bytes += amount; uploadCount++;
    }
    private void preciseRound(DrawContext c, double x, double y, double w, double h, double radius, int color) {
        if (w <= 0 || h <= 0 || color >>> 24 == 0) return;
        double r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (r < .01) { fill(c, x, y, w, h, color); return; }
        fill(c, x + r, y, w - 2 * r, h, color);
        fill(c, x, y + r, r, h - 2 * r, color);
        fill(c, x + w - r, y + r, r, h - 2 * r, color);
        int pixels = Math.max(1, (int) Math.ceil(r));
        Entry disk = get(new Key("disk", "", pixels * 2, pixels * 2, 0, 0, 0, null, 0));
        if (disk.texture == null) return;
        corner(c, disk, x, y, r, pixels, 0, 0, color);
        corner(c, disk, x + w - r, y, r, pixels, pixels, 0, color);
        corner(c, disk, x, y + h - r, r, pixels, 0, pixels, color);
        corner(c, disk, x + w - r, y + h - r, r, pixels, pixels, pixels, color);
    }
    private void fill(DrawContext c, double x, double y, double w, double h, int color) {
        if (w <= 0 || h <= 0) return;
        var matrix = c.getMatrices(); matrix.pushMatrix();
        matrix.translate((float) x, (float) y); matrix.scale((float) w, (float) h);
        c.fill(0, 0, 1, 1, color); matrix.popMatrix();
    }
    private void corner(DrawContext c, Entry disk, double x, double y, double r, int pixels, int u, int v, int color) {
        var matrix = c.getMatrices(); matrix.pushMatrix();
        matrix.translate((float) x, (float) y); matrix.scale((float) (r / pixels), (float) (r / pixels));
        disk.texture.drawRegion(c, 0, 0, pixels, pixels, u, v, pixels, pixels, color); matrix.popMatrix();
    }
    private void sprite(DrawContext context, Key key, int x, int y, int color) {
        Entry e = get(key);
        if (e.texture != null) e.texture.drawRegion(context, x - e.anchorX, y - e.anchorY, e.width, e.height, 0, 0, e.width, e.height, color);
    }
    private void round(DrawContext c, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.clamp(radius, 0, Math.min(w, h) / 2);
        if (r == 0) { c.fill(x, y, x + w, y + h, color); return; }
        c.fill(x + r, y, x + w - r, y + h, color);
        c.fill(x, y + r, x + r, y + h - r, color);
        c.fill(x + w - r, y + r, x + w, y + h - r, color);
        Entry e = get(new Key("disk", "", r * 2, r * 2, 0, 0, 0, null, 0));
        if (e.texture == null) return;
        e.texture.drawRegion(c, x, y, r, r, 0, 0, r, r, color);
        e.texture.drawRegion(c, x + w - r, y, r, r, r, 0, r, r, color);
        e.texture.drawRegion(c, x, y + h - r, r, r, 0, r, r, r, color);
        e.texture.drawRegion(c, x + w - r, y + h - r, r, r, r, r, r, r, color);
    }
    private void evict() {
        var iterator = cache.entrySet().iterator();
        while ((cache.size() > MAX_ENTRIES || bytes > MAX_BYTES) && iterator.hasNext()) {
            Entry e = iterator.next().getValue();
            if (e.frame >= frame - 1) continue;
            if (e.texture != null) { e.texture.close(); bytes -= (long) e.width * e.height * 4; }
            if (e.future != null) e.future.cancel(false);
            iterator.remove();
        }
    }
    @Override public void close() {
        worker.shutdownNow(); cache.values().forEach(e -> { if (e.texture != null) e.texture.close(); }); cache.clear(); bytes = 0;
    }
}
