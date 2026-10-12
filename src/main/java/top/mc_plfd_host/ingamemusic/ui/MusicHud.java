package top.mc_plfd_host.ingamemusic.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import top.mc_plfd_host.ingamemusic.InGameMusic;
import top.mc_plfd_host.ingamemusic.MusicController;

public final class MusicHud implements AutoCloseable {
    private final MusicController app;
    private final CoverCache covers;
    private final UiMotion motion = new UiMotion();
    public MusicHud(MusicController app, CoverCache covers) { this.app = app; this.covers = covers; }
    public int logicalWidth() { return HudView.width(app.state.hudCompact); }
    public int logicalHeight() { return HudView.height(app.state.hudCompact); }
    public double scale() {
        var window = MinecraftClient.getInstance().getWindow();
        return Math.min(.66, Math.min(window.getScaledWidth() / (double) logicalWidth(), window.getScaledHeight() / (double) logicalHeight()) * .9);
    }
    public int width() { return (int) Math.ceil(logicalWidth() * scale()); }
    public int height() { return (int) Math.ceil(logicalHeight() * scale()); }
    public int x() { return (int) (app.state.hudX * Math.max(0, MinecraftClient.getInstance().getWindow().getScaledWidth() - width())); }
    public int y() { return (int) (app.state.hudY * Math.max(0, MinecraftClient.getInstance().getWindow().getScaledHeight() - height())); }
    public void render(DrawContext context, boolean editing, int x, int y) {
        render(context, editing, x, y, -1, -1, false);
    }
    public void render(DrawContext context, boolean editing, int x, int y, double mouseX, double mouseY, boolean pressed) {
        var client = MinecraftClient.getInstance();
        if (!editing && (!app.state.hud || app.player.track() == null || client.options.hudHidden || client.currentScreen != null)) return;
        motion.begin(System.nanoTime());
        InGameMusic.renderer().draw(HudView.scene(app, covers, app.state.hudCompact, motion, mouseX, mouseY, pressed), context, x, y, scale());
    }
    @Override public void close() { }
}
