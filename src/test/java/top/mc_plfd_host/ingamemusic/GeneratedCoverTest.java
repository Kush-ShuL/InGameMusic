package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.mc_plfd_host.ingamemusic.library.*;
import top.mc_plfd_host.ingamemusic.ui.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedCoverTest {
    @TempDir Path home;
    private Path file(String name, String text) throws Exception { return Files.writeString(home.resolve(name), text); }
    private Track track(Path path, String cover) { return new Track(path.toString(), "track", "", "", "", 180, cover, 0); }
    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()); }
    @Test void fileContentDeterminesCoverAndBlurIsContinuous() throws Exception {
        Path a = file("a.wav", "music-one"), renamed = file("renamed.wav", "music-one"), b = file("b.wav", "music-two");
        byte[] hash = GeneratedCover.hash(a);
        assertArrayEquals(hash, GeneratedCover.hash(renamed));
        var image = GeneratedCover.render(hash);
        assertArrayEquals(pixels(image), pixels(GeneratedCover.render(GeneratedCover.hash(renamed))));
        assertFalse(Arrays.equals(pixels(image), pixels(GeneratedCover.render(GeneratedCover.hash(b)))));
        assertTrue(Arrays.stream(pixels(image)).distinct().count() > 1000);
        for (int y = 1; y < image.getHeight(); y++) for (int x = 1; x < image.getWidth(); x++) {
            int center = image.getRGB(x, y); assertEquals(255, center >>> 24);
            for (int neighbor : new int[]{image.getRGB(x - 1, y), image.getRGB(x, y - 1)})
                for (int shift : new int[]{0, 8, 16}) assertTrue(Math.abs((center >> shift & 255) - (neighbor >> shift & 255)) < 15);
        }
        Path previews = Path.of("build/previews"); Files.createDirectories(previews);
        ImageIO.write(image, "png", previews.resolve("generated-cover.png").toFile());
    }
    private BufferedImage await(CoverCache cache, Track track, boolean enabled) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        BufferedImage result;
        while ((result = cache.get(track, enabled)) == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotNull(result); return result;
    }
    @Test void switchDoesNotReplaceOriginalArtAndGeneratedCacheSurvivesReopen() throws Exception {
        Path source = file("music.wav", "sample-music-content");
        Path directory = home.resolve("generated"); Track missing = track(source, "");
        Files.createDirectories(directory);
        Path stale = directory.resolve("v1-" + HexFormat.of().formatHex(GeneratedCover.hash(source)) + ".png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", stale.toFile());
        int[] first;
        try (var cache = new CoverCache(directory)) {
            assertNull(cache.get(missing, false));
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
            var generated = await(cache, missing, true);
            assertEquals(GeneratedCover.SIZE, generated.getWidth(), "Old style cache must not be reused");
            first = pixels(generated);
            assertNull(cache.get(missing, false));
            var original = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB); original.setRGB(0, 0, 0xff1268a4);
            Path art = home.resolve("original.png"); ImageIO.write(original, "png", art.toFile());
            assertArrayEquals(pixels(original), pixels(await(cache, track(source, art.toString()), true)));
        }
        try (var cache = new CoverCache(directory)) { assertArrayEquals(first, pixels(await(cache, missing, true))); }
        try (var files = Files.list(directory)) { assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("v2-")).count()); }
        var library = new Library(home.resolve("library"));
        assertFalse(library.snapshot().generatedCovers);
        library.edit(state -> state.generatedCovers = true);
        assertTrue(new Library(home.resolve("library")).snapshot().generatedCovers);
    }
}
