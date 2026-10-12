package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.images.ArtworkFactory;
import top.mc_plfd_host.ingamemusic.library.Metadata;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
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
}
