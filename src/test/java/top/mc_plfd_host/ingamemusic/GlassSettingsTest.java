package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.mc_plfd_host.ingamemusic.library.Library;
import top.mc_plfd_host.ingamemusic.ui.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class GlassSettingsTest {
    @TempDir Path home;
    private static final MusicView.Host HOST = new MusicView.Host() {
        public void close() { }
        public String clipboard() { return ""; }
        public void clipboard(String value) { }
        public void editHud() { }
        public String binding(String action) { return action.equals("interact") ? "Right Alt" : "M"; }
    };
    @Test void oldConfigDefaultsAndInvalidValuesAreSafe() throws Exception {
        Files.writeString(home.resolve("library.json"), "{\"tracks\":[],\"playlists\":[],\"folders\":[]}");
        var library = new Library(home);
        assertEquals(GlassStyle.DEFAULT, GlassStyle.from(library.snapshot()));
        library.edit(s -> { s.liquidGlass = true; s.glassBlur = 1000; s.glassRefraction = -8; s.glassRespectBackgroundBlur = false; s.glassMaskOpacity = 7; });
        assertEquals(new GlassStyle(true, 24, 1, false, 1), GlassStyle.from(new Library(home).snapshot()));
        library.edit(s -> { s.glassBlur = Double.NaN; s.glassRefraction = Double.POSITIVE_INFINITY; s.glassMaskOpacity = Double.NaN; });
        assertEquals(new GlassStyle(true, GlassStyle.DEFAULT_BLUR, 1.35, false), GlassStyle.from(new Library(home).snapshot()));
    }
    @Test void appearanceSlidersPersistClampAndRetainTheirValuesWhenDisabled() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            var view = new MusicView(app, covers, HOST);
            view.scene(0, 0); view.click(100, 220, 0, 0); view.scene(0, 0);
            assertFalse(app.glassStyle.enabled());
            view.click(654, 365, 0, 0); view.drag(1200, 365); view.release(1200, 365);
            assertEquals(GlassStyle.DEFAULT_BLUR, app.glassStyle.blur(), "Disabled slider must not change stored values");
            view.scene(0, 0); view.click(600, 266, 0, 0);
            assertTrue(app.glassStyle.enabled(), "Mode must update immediately before the disk write");
            assertTrue(view.scene(0, 0).commands().stream().anyMatch(Canvas.LiquidGlass.class::isInstance));
            view.click(654, 365, 0, 0); view.drag(1200, 365); view.release(1200, 365);
            assertEquals(24, app.glassStyle.blur());
            view.scene(0, 0); view.click(244, 451, 0, 0); view.drag(-50, 451); view.release(-50, 451);
            assertEquals(1, app.glassStyle.refraction());
            view.key(GLFW_KEY_RIGHT, 0); assertEquals(1.01, app.glassStyle.refraction(), 1e-6);
            view.key(GLFW_KEY_END, 0); assertEquals(2, app.glassStyle.refraction());
            view.scene(0, 0); view.click(1029, 471, 0, 0);
            assertFalse(app.glassStyle.respectBackgroundBlur());
            view.scene(0, 0); view.click(449, 536, 0, 0); view.release(449, 536);
            assertEquals(.5, app.glassStyle.maskOpacity());
            view.scene(0, 0); view.click(360, 266, 0, 0);
            assertEquals(new GlassStyle(false, 24, 2, false, .5), app.glassStyle);
            assertTrue(view.scene(0, 0).commands().stream().noneMatch(Canvas.LiquidGlass.class::isInstance));
            await(() -> GlassStyle.from(app.state).equals(app.glassStyle));
            assertEquals(app.glassStyle, GlassStyle.from(new Library(home).snapshot()));
            view.dispose();
        }
    }
    @Test void leavingAppearanceDuringDragSavesAndResetOnlyResetsGlassParameters() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            app.glassStyle = GlassStyle.DEFAULT.withEnabled(true);
            var view = new MusicView(app, covers, HOST);
            view.scene(0, 0); view.click(100, 220, 0, 0); view.scene(0, 0);
            view.click(244, 365, 0, 0); view.drag(449, 365);
            view.dispose(); await(() -> app.state.liquidGlass);
            assertEquals(12, new Library(home).snapshot().glassBlur);
            view.scene(0, 0); view.click(1029, 471, 0, 0); view.scene(0, 0);
            view.click(940, 569, 0, 0);
            assertEquals(GlassStyle.DEFAULT.withEnabled(true), app.glassStyle);
            assertTrue(app.state.hud); assertEquals(.35, app.voiceRedirect.gain());
            await(() -> GlassStyle.from(app.state).equals(app.glassStyle));
        }
    }
    @Test void glassHasReadableHeadingsAndButtonFeedbackSettles() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            app.glassStyle = GlassStyle.DEFAULT.withEnabled(true);
            var view = new MusicView(app, covers, HOST);
            view.scene(0, 0); view.click(100, 220, 0, 0);
            long start = System.nanoTime();
            view.scene(940, 345, start - 1_000_000_000L);
            var before = view.scene(940, 345, start);
            assertTrue(before.commands().stream().anyMatch(c -> c instanceof Canvas.Label label && label.bold() && label.color() == 0xfff9fbff && label.shadowed()));
            int resting = before.rasterize(1).getRGB(825, 345);
            view.click(940, 345, 0, 0); long pressed = System.nanoTime();
            int glowing = view.scene(940, 345, pressed + 60_000_000L).rasterize(1).getRGB(825, 345);
            assertNotEquals(resting, glowing);
            view.release(940, 345);
            int settled = view.scene(940, 345, pressed + 1_000_000_000L).rasterize(1).getRGB(825, 345);
            assertEquals(resting, settled);
            Path output = Path.of("build/previews"); Files.createDirectories(output);
            writeGlassPreview(view.scene(0, 0), "settings-glass");
            for (boolean compact : new boolean[]{false, true}) {
                var hud = HudView.scene(app, covers, compact);
                assertEquals(1, hud.commands().stream().filter(Canvas.LiquidGlass.class::isInstance).count());
                assertTrue(hud.commands().stream().filter(Canvas.Label.class::isInstance).map(Canvas.Label.class::cast).allMatch(Canvas.Label::shadowed));
                writeGlassPreview(hud, compact ? "hud-compact-glass" : "hud-full-glass");
            }
            view.click(750, 136, 0, 0); view.scene(0, 0);
            ImageIO.write(view.render(1.5, 0, 0), "png", output.resolve("settings-voice.png").toFile());
            view.click(980, 136, 0, 0); view.scene(0, 0);
            ImageIO.write(view.render(1.5, 0, 0), "png", output.resolve("settings-keys.png").toFile());
            view.dispose();
        }
    }
    @Test void boldHeadingClippingUsesTheSameFontAsTheUploadedSprite() {
        try (var canvas = new Canvas(400, 100, true)) {
            canvas.fit("A very long heading for the glass appearance panel", 10, 30, 20, Canvas.TEXT, 160);
            var label = (Canvas.Label) canvas.finish().commands().getFirst();
            assertTrue(label.bold());
            var sprite = SpriteRasterizer.render(new SpriteRasterizer.Key("text", label.text(), 0, 20, 0, 0, 1, null, 0));
            assertTrue(sprite.width() <= 166, "Bold text must stay within the fitted width, plus texture padding");
        }
    }
    @Test void glyphTexturesContainOnlyWhiteCoverageEvenAtSmallSizes() {
        for (int size : new int[]{9, 12, 13, 17, 26}) {
            for (int bold : new int[]{0, 1}) {
                var glyphs = SpriteRasterizer.render(new SpriteRasterizer.Key("text", "背景模糊 Aa8", 0, size, 0, 0, bold, null, 0));
                boolean ink = false, antialiased = false;
                for (int pixel : glyphs.rgba()) {
                    assertEquals(0xffffff, pixel & 0xffffff, "Glyphs and transparent padding must not contain dark RGB that bleeds under filtering");
                    int alpha = pixel >>> 24;
                    ink |= alpha > 200; antialiased |= alpha > 0 && alpha < 255;
                }
                assertTrue(ink); assertTrue(antialiased);
            }
        }
    }
    @Test void blurSourceAndSwitchPositionChangeOnTheVeryNextScene() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            app.glassStyle = GlassStyle.DEFAULT.withEnabled(true);
            var view = new MusicView(app, covers, HOST);
            view.scene(0, 0); view.click(100, 220, 0, 0); view.scene(0, 0);
            for (boolean expected : new boolean[]{false, true, false, true}) {
                view.click(1029, 471, 0, 0);
                var scene = view.scene(1029, 471);
                assertTrue(scene.commands().stream().filter(Canvas.LiquidGlass.class::isInstance)
                        .map(Canvas.LiquidGlass.class::cast).allMatch(p -> p.style().respectBackgroundBlur() == expected));
                assertTrue(scene.commands().stream().anyMatch(c -> c instanceof Canvas.Round r
                        && r.x() == (expected ? 1008 : 991) && r.y() == 464 && r.w() == 14));
                assertFalse(scene.commands().stream().anyMatch(c -> c instanceof Canvas.Line l
                        && l.y() == 326 && l.yy() == 326), "Preview button must not draw the former horizontal highlight");
                view.release(1029, 471);
            }
            view.dispose();
        }
    }
    private static void writeGlassPreview(Canvas.Scene scene, String name) throws Exception {
        Path output = Path.of("build/previews");
        var panes = scene.commands().stream().filter(Canvas.LiquidGlass.class::isInstance).map(Canvas.LiquidGlass.class::cast).toList();
        var foreground = new Canvas.Scene(scene.width(), scene.height(), scene.commands().stream().filter(c -> !(c instanceof Canvas.LiquidGlass) && !(c instanceof Canvas.Round r && (r.color() == 0x18040d1c || r.color() == 0x20000000))).toList());
        ImageIO.write(foreground.rasterize(1), "png", output.resolve(name + "-foreground.png").toFile());
        var data = java.util.Map.of("width", scene.width(), "height", scene.height(), "panes", panes);
        Files.writeString(output.resolve(name + ".glass.json"), new com.google.gson.Gson().toJson(data));
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
