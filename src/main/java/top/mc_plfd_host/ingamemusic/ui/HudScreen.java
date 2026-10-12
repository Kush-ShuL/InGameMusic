package top.mc_plfd_host.ingamemusic.ui;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import top.mc_plfd_host.ingamemusic.InGameMusic;
import static org.lwjgl.glfw.GLFW.*;

/** Releases the mouse for overlay controls without pausing the world. */
public final class HudScreen extends Screen {
    private final Screen parent;
    private boolean dragging;
    private boolean pointerDown;
    private long pressUntil;
    private double offsetX, offsetY;
    private int x, y;
    public HudScreen(Screen parent) { super(Text.literal(tr("screen.hud"))); this.parent = parent; }
    @Override protected void init() { x = InGameMusic.hud().x(); y = InGameMusic.hud().y(); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        x = Math.clamp(x, 0, Math.max(0, width - InGameMusic.hud().width()));
        y = Math.clamp(y, 0, Math.max(0, height - InGameMusic.hud().height()));
        var hud = InGameMusic.hud();
        hud.render(context, true, x, y, (mouseX - x) / hud.scale(), (mouseY - y) / hud.scale(), !dragging && (pointerDown || System.nanoTime() < pressUntil));
    }
    @Override public void renderBackground(DrawContext context, int x, int y, float delta) { }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return false;
        var hud = InGameMusic.hud(); double px = (click.x() - x) / hud.scale(), py = (click.y() - y) / hud.scale();
        if (px < 0 || px > hud.logicalWidth() || py < 0 || py > hud.logicalHeight()) { close(); return true; }
        pointerDown = true; pressUntil = System.nanoTime() + 90_000_000L;
        var app = InGameMusic.controller();
        if (app.state.hudCompact) {
            if (py >= 46 && app.player.track() != null) app.player.seek(Math.clamp((px - 10) / 280, 0, 1) * app.player.track().duration());
            else if (px >= 269) MinecraftClient.getInstance().setScreen(new MusicScreen(null));
            else if (px >= 241) app.edit(tr("action.hud_style"), s -> s.hudCompact = false);
            else if (px >= 208) app.toggle();
            else { dragging = true; offsetX = click.x() - x; offsetY = click.y() - y; }
            return true;
        }
        if (px >= 280 && px < 307 && py < 45) { app.edit(tr("action.hud_style"), s -> s.hudCompact = true); return true; }
        if (px >= 301 && py < 48) { MinecraftClient.getInstance().setScreen(new MusicScreen(null)); return true; }
        if (py >= 55 && py < 83 && px >= 73 && px < 180) {
            if (px < 104) app.next(true, false); else if (px < 143) app.toggle(); else app.next(false, false);
        } else if (py >= 83 && app.player.track() != null) app.player.seek(Math.clamp((px - 14) / 314, 0, 1) * app.player.track().duration());
        else { dragging = true; offsetX = click.x() - x; offsetY = click.y() - y; }
        return true;
    }
    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging) { x = Math.clamp((int) (click.x() - offsetX), 0, Math.max(0, width - InGameMusic.hud().width())); y = Math.clamp((int) (click.y() - offsetY), 0, Math.max(0, height - InGameMusic.hud().height())); }
        return true;
    }
    @Override public boolean mouseReleased(Click click) { pointerDown = false; if (dragging) persist(); dragging = false; return true; }
    private void persist() {
        double xx = x / (double) Math.max(1, width - InGameMusic.hud().width()), yy = y / (double) Math.max(1, height - InGameMusic.hud().height());
        InGameMusic.controller().edit(tr("action.hud_position"), s -> { s.hudX = xx; s.hudY = yy; s.hud = true; });
    }
    @Override public boolean keyPressed(KeyInput key) {
        if (key.key() == GLFW_KEY_ESCAPE || InGameMusic.interactKey.matchesKey(key)) { close(); return true; }
        if (key.key() == GLFW_KEY_SPACE) { InGameMusic.controller().toggle(); return true; }
        return false;
    }
    @Override public void close() { if (dragging) persist(); MinecraftClient.getInstance().setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
