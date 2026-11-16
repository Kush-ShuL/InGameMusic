package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusTags;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.images.ArtworkFactory;
import top.mc_plfd_host.ingamemusic.library.Metadata;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.io.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class MetadataTest {
    @TempDir Path home;
    @Test void extractsComposerAlbumAndEmbeddedArtwork() throws Exception {
        Path file = DecoderTest.fixture(home, "flac", 16);
        Path cover = home.resolve("art.png");
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.CYAN); graphics.fillRect(0, 0, 64, 32); graphics.dispose();
        ImageIO.write(image, "png", cover.toFile());
        var audio = AudioFileIO.read(file.toFile()); var tag = audio.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, "旅途"); tag.setField(FieldKey.ARTIST, "测试艺术家");
        tag.setField(FieldKey.COMPOSER, "测试作曲人"); tag.setField(FieldKey.ALBUM, "测试专辑");
        tag.setField(ArtworkFactory.createArtworkFromFile(cover.toFile())); audio.commit();
        Files.delete(cover);
        var track = new Metadata(home.resolve("config")).read(file);
        assertEquals("旅途", track.title()); assertEquals("测试作曲人", track.composer()); assertEquals("测试专辑", track.album());
        assertTrue(track.duration() > .9); assertFalse(track.cover().isBlank());
        BufferedImage cached = ImageIO.read(Path.of(track.cover()).toFile());
        assertEquals(512, cached.getWidth()); assertEquals(512, cached.getHeight()); assertEquals(Color.CYAN.getRGB(), cached.getRGB(256, 256));
    }
    @ParameterizedTest @ValueSource(strings = {"opus", "ogg", "oga"})
    void readsOpusTagsAndPrefersFrontCoverAcrossOggExtensions(String extension) throws Exception {
        var tags = new OpusTags();
        tags.addComment("title", "海风"); tags.addComment("ARTIST", "演奏者");
        tags.addComment("COMPOSER", "作曲者"); tags.addComment("ALBUM", "旅途");
        tags.addComment("METADATA_BLOCK_PICTURE", "broken base64");
        tags.addComment("METADATA_BLOCK_PICTURE", picture(4, Color.MAGENTA));
        tags.addComment("metadata_block_picture", picture(3, Color.CYAN));
        Path file = opus(tags, extension);
        var track = new Metadata(home.resolve("config")).read(file);
        assertEquals("海风", track.title()); assertEquals("演奏者", track.artist());
        assertEquals("作曲者", track.composer()); assertEquals("旅途", track.album());
        assertTrue(track.duration() > .9);
        assertCover(track.cover(), Color.CYAN);
    }
    @Test void supportsLegacyOpusArtworkAndRefreshesUnchangedLibraryEntries() throws Exception {
        var tags = new OpusTags();
        tags.addComment("METADATA_BLOCK_PICTURE", "invalid");
        tags.addComment("COVERART", Base64.getEncoder().encodeToString(image(Color.ORANGE)));
        Path file = opus(tags, "opus").toRealPath();
        try (var app = new MusicController(home.resolve("config"))) {
            app.library.upsert(java.util.List.of(new top.mc_plfd_host.ingamemusic.library.Track(file.toString(), "old", "", "", "", 1, "", Files.getLastModifiedTime(file).toMillis())));
            app.refresh(); app.importPaths(java.util.List.of(file), false);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (app.library.find(file.toString()).cover().isBlank() && System.nanoTime() < deadline) Thread.sleep(10);
            assertCover(app.library.find(file.toString()).cover(), Color.ORANGE);
        }
    }
    private Path opus(OpusTags tags, String extension) throws Exception {
        Path source = DecoderTest.fixture(home, "opus", 16), target = home.resolve("tagged." + extension);
        try (var input = new OpusFile(source.toFile()); var output = new OpusFile(Files.newOutputStream(target), input.getInfo(), tags)) {
            org.gagravarr.opus.OpusAudioData packet;
            while ((packet = input.getNextAudioPacket()) != null) output.writeAudioData(packet);
        }
        return target;
    }
    private static byte[] image(Color color) throws IOException {
        var image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(color); g.fillRect(0, 0, 64, 32); g.dispose();
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static String picture(int type, Color color) throws IOException {
        byte[] image = image(color), mime = "image/png".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(type); out.writeInt(mime.length); out.write(mime); out.writeInt(0);
            out.writeInt(64); out.writeInt(32); out.writeInt(24); out.writeInt(0);
            out.writeInt(image.length); out.write(image);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private static void assertCover(String path, Color color) throws IOException {
        assertFalse(path.isBlank());
        var cached = ImageIO.read(Path.of(path).toFile());
        assertEquals(512, cached.getWidth()); assertEquals(512, cached.getHeight());
        assertEquals(color.getRGB(), cached.getRGB(256, 256));
    }
}
