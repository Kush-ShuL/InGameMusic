package top.mc_plfd_host.ingamemusic.library;

import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusTags;
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

/** OpusTags uses Vorbis comments; artwork is a base64-encoded FLAC picture block. */
record OpusMetadata(String title, String artist, String composer, String album, List<byte[]> pictures) {
    private record Picture(int type, byte[] data) { }
    static OpusMetadata read(Path path) throws IOException {
        try (var file = new OpusFile(path.toFile())) {
            var tags = file.getTags();
            var pictures = new ArrayList<Picture>();
            for (String encoded : tags.getComments("METADATA_BLOCK_PICTURE")) {
                try {
                    byte[] block = decode(encoded);
                    var picture = new MetadataBlockDataPicture(ByteBuffer.wrap(block));
                    if (!picture.isImageUrl()) pictures.add(new Picture(picture.getPictureType(), picture.getImageData()));
                } catch (Exception ignored) { /* A damaged picture must not hide later artwork or text tags. */ }
            }
            // Prefer the front cover over back covers, booklets and artist photos.
            pictures.sort(Comparator.comparingInt(p -> p.type() == 3 ? 0 : 1));
            for (String encoded : tags.getComments("COVERART")) {
                try { pictures.add(new Picture(0, decode(encoded))); }
                catch (IllegalArgumentException ignored) { }
            }
            return new OpusMetadata(value(tags, "TITLE"), value(tags, "ARTIST"), value(tags, "COMPOSER"),
                    value(tags, "ALBUM"), pictures.stream().map(Picture::data).toList());
        }
    }
    private static String value(OpusTags tags, String key) {
        return tags.getComments(key).stream().filter(value -> !value.isBlank()).findFirst().orElse("");
    }
    private static byte[] decode(String value) {
        if (value.length() > 28_000_000) throw new IllegalArgumentException("Artwork exceeds the cover size limit");
        byte[] data = Base64.getDecoder().decode(value);
        if (data.length > 20_000_000) throw new IllegalArgumentException("Artwork exceeds the cover size limit");
        return data;
    }
}
