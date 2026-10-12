package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mc_plfd_host.ingamemusic.library.Track;
import top.mc_plfd_host.ingamemusic.ui.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class UiTest {
    @TempDir Path home;
    private static final class Host implements MusicView.Host {
        String clipboard = ""; boolean closed;
        public void close() { closed = true; } public void editHud() { }
        public String binding(String action) { return switch (action) { case "open" -> "M"; case "pause" -> "P"; case "next" -> "]"; case "hud" -> "H"; default -> "Right Alt"; }; }
        public String clipboard() { return clipboard; } public void clipboard(String value) { clipboard = value; }
    }
    @Test void editsUnicodeAndClipboardWithoutSplittingSurrogatePairs() {
        Host host = new Host(); TextEdit field = new TextEdit("你好🎵");
        field.key(GLFW_KEY_BACKSPACE, 0, host); assertEquals("你好", field.value);
        field.key(GLFW_KEY_A, GLFW_MOD_CONTROL, host); field.key(GLFW_KEY_C, GLFW_MOD_CONTROL, host);
        field.insert("\u0000"); assertEquals("你好", field.value);
        field.insert("音乐"); assertEquals("音乐", field.value);
        field.key(GLFW_KEY_END, 0, host); field.key(GLFW_KEY_V, GLFW_MOD_CONTROL, host); assertEquals("音乐你好", field.value);
    }
    @Test void scrollbarsReachBothEndsAndKeepThumbGrabOffset() {
        Scrollbar bar = new Scrollbar(316, 258, 100, 5, 30);
        assertEquals(30, bar.offsetAt(bar.thumbTop() + 7, 7), 1e-8);
        assertEquals(0, bar.offsetAt(-100, 7));
        assertEquals(95, bar.offsetAt(2000, 7));
        assertEquals(0, new Scrollbar(10, 200, 2, 5, 0).offsetAt(100, 5));
    }
    @Test void draggingLibraryScrollbarChangesVisibleRowsAndReleases() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            var tracks = new ArrayList<Track>();
            for (int i = 0; i < 30; i++) tracks.add(new Track(home.resolve(i + ".wav").toString(), "track-" + i, "artist", "composer", "album", 180, "", 0));
            app.library.upsert(tracks); app.refresh();
            var view = new MusicView(app, covers, new Host());
            var before = labels(view.scene(0, 0));
            view.click(813, 214, 0, 0); view.drag(813, 900);
            var after = labels(view.scene(813, 900));
            assertNotEquals(before, after);
            view.drag(813, -100); assertEquals(before, labels(view.scene(0, 0)));
            view.release(813, 316); view.drag(813, 900); assertEquals(before, labels(view.scene(0, 0)));
            view.click(700, 45, 0, 0); assertNotNull(view.focusedInput());
            view.character("中文"); assertTrue(view.focusedInput().caretX() > 11);
            view.key(GLFW_KEY_ESCAPE, 0); assertNull(view.focusedInput());
            view.dispose();
        }
    }
    @Test void animatedRowsKeepHitAreasInsideViewport() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            var tracks = new ArrayList<Track>();
            for (int i = 0; i < 30; i++) tracks.add(new Track(home.resolve(i + ".wav").toString(), "track-" + i, "artist", "composer", "album", 180, "", 0));
            app.library.upsert(tracks); app.refresh();
            var view = new MusicView(app, covers, new Host());
            long clock = 1_000_000_000L;
            var start = view.scene(0, 0, clock);
            double initial = firstRowBaseline(start);
            view.scroll(400, 300, -1);
            assertEquals(initial, firstRowBaseline(view.scene(0, 0, clock)));
            var midway = view.scene(0, 0, clock + 25_000_000L);
            double moved = firstRowBaseline(midway);
            assertTrue(moved < initial && moved > initial - 54);
            view.click(410, 205, 0, 0); view.key(GLFW_KEY_F2, 0);
            assertNull(view.focusedInput(), "Clipped row must not receive clicks above the viewport");
            view.click(410, 214, 0, 0); view.key(GLFW_KEY_F2, 0);
            assertNotNull(view.focusedInput(), "Visible part of a clipped row remains selectable");
            view.dispose();
        }
    }
    private static double firstRowBaseline(Canvas.Scene scene) {
        return scene.commands().stream().filter(c -> c instanceof Canvas.Label label && label.text().equals("track-0"))
                .map(c -> ((Canvas.Label) c).baseline()).findFirst().orElseThrow();
    }
    private static List<String> labels(Canvas.Scene scene) {
        return scene.commands().stream().filter(c -> c instanceof Canvas.Label).map(c -> ((Canvas.Label) c).text()).toList();
    }
    @Test void longInputOnlyDrawsVisibleTextAndKeepsCaretInsideField() {
        TextEdit field = new TextEdit("中文🎵".repeat(800));
        try (Canvas canvas = new Canvas(600, 100)) {
            field.paint(canvas, 20, 20, 300, 36, "", true);
            String drawn = String.join("", labels(canvas.finish()));
            assertTrue(drawn.length() < 100);
            assertFalse(Character.isLowSurrogate(drawn.charAt(0)));
            assertTrue(field.geometry().caretX() >= 11 && field.geometry().caretX() < 290);
        }
    }
    @Test void textSpritesHaveNativePixelBoundsAndTransparentPadding() {
        var key = new SpriteRasterizer.Key("text", "中文 Music", 0, 28, 0, 0, 0, null, 0);
        var bitmap = SpriteRasterizer.render(key);
        assertTrue(bitmap.width() > 28); assertTrue(bitmap.height() > 28);
        assertEquals(bitmap.width() * bitmap.height(), bitmap.rgba().length);
        assertEquals(0, bitmap.rgba()[0]); assertTrue(bitmap.anchorY() > 3);
        assertTrue(Arrays.stream(bitmap.rgba()).anyMatch(p -> (p >>> 24) > 200));
    }
    @Test void lightMaterialIsOpaqueWithOnlyASubtleGradient() {
        var bitmap = SpriteRasterizer.render(new SpriteRasterizer.Key("glass", "", 240, 100, 18, Canvas.PANEL, 0, null, 0));
        assertEquals(0, bitmap.rgba()[0] >>> 24);
        int alpha = bitmap.rgba()[50 * 240 + 120] >>> 24;
        assertEquals(255, alpha);
        int top = bitmap.rgba()[20 * 240 + 120], bottom = bitmap.rgba()[80 * 240 + 120];
        assertNotEquals(top, bottom);
        for (int shift : new int[]{0, 8, 16}) assertTrue(Math.abs((top >> shift & 255) - (bottom >> shift & 255)) <= 5);
        try (var canvas = new Canvas(240, 100)) {
            canvas.glass(0, 0, 240, 100, 18, Canvas.PANEL);
            var image = canvas.finish().rasterize(1);
            assertEquals(alpha, image.getRGB(120, 50) >>> 24);
        }
    }
    @Test void rendersActualSceneAndConfirmDialog() throws Exception {
        try (var app = new MusicController(home); var covers = new CoverCache()) {
            String[] names = {"远方的风", "暮色森林", "星河漫游", "雨落之前", "山海之间", "新的旅途", "晴空", "沿途的光", "夜航"};
            var tracks = new ArrayList<Track>();
            for (int i = 0; i < names.length; i++) tracks.add(new Track(home.resolve(names[i] + ".flac").toString(), names[i], "声屿 · 演示艺术家", "林间", "旅途手记", 186 + i * 23, "", 0));
            for (Track track : tracks) Files.writeString(track.file(), "preview-" + track.title());
            app.library.upsert(tracks); app.library.createPlaylist("旅途手记"); app.library.createPlaylist("深夜与星空"); app.refresh();
            Host host = new Host(); MusicView view = new MusicView(app, covers, host);
            Path output = Path.of("build", "previews"); Files.createDirectories(output);
            var image = view.render(1.5, 0, 0); assertEquals(1680, image.getWidth());
            ImageIO.write(image, "png", output.resolve("player.png").toFile());
            view.click(410, 230, 0, 0); view.render(1, 0, 0);
            view.key(GLFW_KEY_DELETE, 0);
            ImageIO.write(view.render(1.5, 0, 0), "png", output.resolve("delete-confirmation.png").toFile());
            view.key(GLFW_KEY_ESCAPE, 0); assertFalse(host.closed);
            view.key(GLFW_KEY_ESCAPE, 0); assertTrue(host.closed);
            for (boolean compact : new boolean[]{false, true}) {
                var scene = HudView.scene(app, covers, compact);
                assertEquals(compact ? 58 : 100, scene.height());
                ImageIO.write(scene.rasterize(2), "png", output.resolve(compact ? "hud-compact.png" : "hud-full.png").toFile());
            }
            view.scene(0, 0); view.click(100, 220, 0, 0);
            ImageIO.write(view.render(1.5, 0, 0), "png", output.resolve("settings.png").toFile());
            view.click(980, 307, 0, 0);
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (!app.state.generatedCovers && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(app.state.generatedCovers);
            for (Track track : tracks) {
                while (covers.get(track, true) == null && System.nanoTime() < deadline) Thread.sleep(10);
                assertNotNull(covers.get(track, true));
            }
            view.scene(0, 0); view.click(100, 130, 0, 0); view.scene(0, 0); view.click(410, 230, 0, 0);
            ImageIO.write(view.render(1.5, 0, 0), "png", output.resolve("player-generated.png").toFile());
            view.dispose();
        }
    }
}
