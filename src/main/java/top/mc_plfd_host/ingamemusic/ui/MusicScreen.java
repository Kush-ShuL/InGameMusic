package top.mc_plfd_host.ingamemusic.ui;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import top.mc_plfd_host.ingamemusic.InGameMusic;
import top.mc_plfd_host.ingamemusic.compat.IMBlockerBridge;
import net.fabricmc.loader.api.FabricLoader;

public final class MusicScreen extends Screen {
    private final Screen parent;
    private final MusicView view;
    private double uiScale;
    private IMBlockerBridge inputMethod;
    private int left, top, drawnWidth, drawnHeight;
    public MusicScreen(Screen parent) {
        super(Text.literal(tr("screen.player"))); this.parent = parent;
        view = new MusicView(InGameMusic.controller(), InGameMusic.covers(), new MusicView.Host() {
            @Override public void close() { MusicScreen.this.close(); }
            @Override public String clipboard() { return MinecraftClient.getInstance().keyboard.getClipboard(); }
            @Override public void clipboard(String text) { MinecraftClient.getInstance().keyboard.setClipboard(text); }
            @Override public String binding(String action) {
                var key = switch (action) {
                    case "open" -> InGameMusic.openKey; case "pause" -> InGameMusic.pauseKey;
                    case "next" -> InGameMusic.nextKey; case "hud" -> InGameMusic.hudKey;
                    default -> InGameMusic.interactKey;
                };
                return key.getBoundKeyLocalizedText().getString();
            }
            @Override public void editHud() { MinecraftClient.getInstance().setScreen(new HudScreen(MusicScreen.this)); }
        });
    }
    @Override protected void init() {
        if (inputMethod == null && FabricLoader.getInstance().isModLoaded("imblocker"))
            inputMethod = IMBlockerBridge.connect(getClass().getClassLoader());
        uiScale = Math.min(width / (double) MusicView.WIDTH, height / (double) MusicView.HEIGHT) * .97;
        drawnWidth = (int) (MusicView.WIDTH * uiScale); drawnHeight = (int) (MusicView.HEIGHT * uiScale);
        left = (width - drawnWidth) / 2; top = (height - drawnHeight) / 2;
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (view.app.glassStyle.enabled()) context.applyBlur();
        context.fill(0, 0, width, height, view.app.glassStyle.enabled() ? 0x20070d1c : 0x48070d1c);
        InGameMusic.renderer().draw(view.scene(localX(mouseX), localY(mouseY)), context, left, top, uiScale, view.app.glassStyle.enabled());
        syncInput();
    }
    @Override public void renderBackground(DrawContext context, int x, int y, float delta) { }
    private double localX(double x) { return (x - left) / uiScale; }
    private double localY(double y) { return (y - top) / uiScale; }
    @Override public boolean mouseClicked(Click click, boolean doubled) { boolean result = view.click(localX(click.x()), localY(click.y()), click.button(), click.modifiers()); syncInput(); return result; }
    @Override public boolean mouseReleased(Click click) { view.release(localX(click.x()), localY(click.y())); return true; }
    @Override public boolean mouseDragged(Click click, double dx, double dy) { view.drag(localX(click.x()), localY(click.y())); return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { view.scroll(localX(x), localY(y), vertical); return true; }
    @Override public boolean keyPressed(KeyInput input) { boolean result = view.key(input.key(), input.modifiers()); syncInput(); return result; }
    @Override public boolean charTyped(CharInput input) {
        if (inputMethod != null && inputMethod.consumeFocusProbe()) return true;
        boolean result = view.character(input.asString()); syncInput(); return result;
    }
    private void syncInput() {
        if (inputMethod != null) inputMethod.sync(
                MinecraftClient.getInstance().currentScreen == this ? view.focusedInput() : null, left, top, uiScale);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }
    @Override public void removed() { if (inputMethod != null) inputMethod.close(); view.dispose(); InGameMusic.renderer().releaseGlass(); }
}
