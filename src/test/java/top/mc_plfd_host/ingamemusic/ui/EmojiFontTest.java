package top.mc_plfd_host.ingamemusic.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class EmojiFontTest {
    private static final String[] EMOJI = {"🎵", "😀", "❤️", "👍🏽", "👩‍💻", "👨‍👩‍👧‍👦", "🇨🇳", "1️⃣"};
    @Test void bundledFontCoversAndShapesEmojiWithoutDependingOnSystemFonts() {
        assertTrue(UiFonts.EMOJI.getFamily().contains("Noto Emoji"), "The bundled font must load instead of silently falling back to a system font");
        for (String emoji : EMOJI) {
            assertTrue(UiFonts.usesEmoji(emoji), emoji);
            assertTrue(Canvas.measure(emoji, 28) > 10, emoji);
            assertTrue(Canvas.measure(emoji, 28) < 45, "Emoji sequences must shape as a single symbol: " + emoji);
        }
        assertFalse(UiFonts.usesEmoji("中")); assertFalse(UiFonts.usesEmoji("A"));
        assertFalse(UiFonts.usesEmoji("1"));
    }
    @Test void mixedTextMeasurementAndSpriteBoundsMatchWithTransparentMargins() throws Exception {
        String text = "音乐 Music  " + String.join(" ", EMOJI);
        for (int size : new int[]{12, 20, 36}) {
            var sprite = SpriteRasterizer.render(new SpriteRasterizer.Key("text", text, 0, size, 0, 0, 0, null, 0));
            assertTrue(sprite.width() >= Canvas.measure(text, size) + 6);
            for (int x = 0; x < sprite.width(); x++) {
                assertEquals(0, sprite.rgba()[x] >>> 24);
                assertEquals(0, sprite.rgba()[(sprite.height() - 1) * sprite.width() + x] >>> 24);
            }
            for (int y = 0; y < sprite.height(); y++) {
                assertEquals(0, sprite.rgba()[y * sprite.width()] >>> 24);
                assertEquals(0, sprite.rgba()[y * sprite.width() + sprite.width() - 1] >>> 24);
            }
            assertTrue(Arrays.stream(sprite.rgba()).anyMatch(p -> p >>> 24 > 200));
        }
        try (var canvas = new Canvas(950, 180)) {
            canvas.round(0, 0, 950, 180, 0, Canvas.PANEL);
            canvas.text(text, 20, 55, 28, Canvas.TEXT);
            canvas.text("歌单 🎧  喜欢 ❤️  家庭 👨‍👩‍👧‍👦  工作 👩‍💻  旅行 🇨🇳", 20, 112, 22, Canvas.ACCENT);
            Files.createDirectories(Path.of("build/previews"));
            ImageIO.write(canvas.finish().rasterize(1.5), "png", Path.of("build/previews/emoji-font.png").toFile());
        }
    }
    @Test void FittingAndEditingNeverSplitEmojiClusters() {
        for (String emoji : EMOJI) {
            String text = "A" + emoji + "B";
            try (var canvas = new Canvas(200, 40)) {
                canvas.fit(text, 0, 28, 20, Canvas.TEXT, Canvas.measure("A…", 20));
                assertEquals("A…", ((Canvas.Label) canvas.finish().commands().getFirst()).text());
            }
            var editor = new TextEdit(text);
            editor.key(GLFW_KEY_LEFT, 0, null); assertEquals(text.length() - 1, editor.cursor);
            editor.key(GLFW_KEY_LEFT, 0, null); assertEquals(1, editor.cursor);
            editor.key(GLFW_KEY_RIGHT, 0, null); assertEquals(text.length() - 1, editor.cursor);
            editor.key(GLFW_KEY_BACKSPACE, 0, null); assertEquals("AB", editor.value);
            editor = new TextEdit(text);
            editor.key(GLFW_KEY_HOME, 0, null); editor.key(GLFW_KEY_RIGHT, 0, null);
            editor.key(GLFW_KEY_DELETE, 0, null); assertEquals("AB", editor.value);
        }
    }
}
