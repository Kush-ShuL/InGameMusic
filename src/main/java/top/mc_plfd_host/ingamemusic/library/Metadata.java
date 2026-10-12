package top.mc_plfd_host.ingamemusic.library;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import top.mc_plfd_host.ingamemusic.audio.JavaDecoder;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Metadata {
    private final Path covers;
    public Metadata(Path home) throws IOException { covers = home.resolve("covers"); Files.createDirectories(covers); }
    public Track read(Path input) throws Exception {
        Path path = input.toRealPath();
        long modified = Files.getLastModifiedTime(path).toMillis();
        String title = Track.stem(path.getFileName().toString()), artist = "", composer = "", album = "", cover = "";
        double duration = 0;
        try {
            var file = AudioFileIO.read(path.toFile());
            duration = file.getAudioHeader().getPreciseTrackLength();
            Tag tag = file.getTag();
            if (tag != null) {
                title = value(tag, FieldKey.TITLE, title); artist = value(tag, FieldKey.ARTIST, artist);
                composer = value(tag, FieldKey.COMPOSER, composer); album = value(tag, FieldKey.ALBUM, album);
                var artwork = tag.getFirstArtwork();
                if (artwork != null && artwork.getBinaryData() != null && artwork.getBinaryData().length <= 20_000_000)
                    cover = storeCover(new ByteArrayInputStream(artwork.getBinaryData()), path + ":" + modified);
            }
        } catch (Exception ignored) { /* AAC ADTS and untagged files are still playable. */ }
        if (duration <= 0) {
            try {
                var info = JavaDecoder.inspect(path);
                Object micros = info.properties().get("duration");
                if (micros instanceof Number number) duration = number.doubleValue() / 1_000_000;
                else if (info.getFrameLength() > 0 && info.getFormat().getFrameRate() > 0)
                    duration = info.getFrameLength() / (double) info.getFormat().getFrameRate();
                Object name = info.properties().get("title"); if (name != null) title = name.toString();
                Object author = info.properties().get("author"); if (author != null) artist = author.toString();
            } catch (Exception ignored) { /* Report decoder errors at playback instead of discarding local files. */ }
        }
        if (cover.isEmpty()) {
            for (String name : new String[]{"cover.jpg", "cover.png", "folder.jpg", "folder.png"}) {
                Path candidate = path.getParent().resolve(name);
                if (Files.isRegularFile(candidate) && Files.size(candidate) < 20_000_000) {
                    try (var stream = Files.newInputStream(candidate)) {
                        cover = storeCover(stream, candidate + ":" + Files.getLastModifiedTime(candidate).toMillis());
                    } catch (Exception ignored) { }
                    if (!cover.isEmpty()) break;
                }
            }
        }
        return new Track(path.toString(), title, artist, composer, album, Double.isFinite(duration) ? Math.max(0, duration) : 0, cover, modified);
    }
    private String value(Tag tag, FieldKey field, String fallback) {
        try { String value = tag.getFirst(field); return value == null || value.isBlank() ? fallback : value; }
        catch (Exception e) { return fallback; }
    }
    private String storeCover(InputStream stream, String key) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path target = covers.resolve(hash + ".png");
        if (Files.exists(target)) return target.toString();
        try (var input = ImageIO.createImageInputStream(stream)) {
            if (input == null) return "";
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) return "";
            var reader = readers.next();
            try {
                reader.setInput(input); int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 100_000_000) return "";
                var params = reader.getDefaultReadParam(); int sample = Math.max(1, Math.min(width, height) / 768);
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage original = reader.read(0, params);
                BufferedImage output = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = output.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int side = Math.min(original.getWidth(), original.getHeight());
                int x = (original.getWidth() - side) / 2, y = (original.getHeight() - side) / 2;
                g.drawImage(original, 0, 0, 512, 512, x, y, x + side, y + side, null); g.dispose();
                ImageIO.write(output, "png", target.toFile()); return target.toString();
            } finally { reader.dispose(); }
        }
    }
}
