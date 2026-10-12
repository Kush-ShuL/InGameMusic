package top.mc_plfd_host.ingamemusic.library;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public record Track(String path, String title, String artist, String composer, String album,
                    double duration, String cover, long modified) {
    public static final Set<String> EXTENSIONS = Set.of("flac", "aac", "mp3", "ogg", "oga", "opus", "wav");
    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    public static boolean supported(Path path) { return EXTENSIONS.contains(extension(path)); }
    public String displayArtist() { return artist == null || artist.isBlank() ? tr("metadata.unknown_artist") : artist; }
    public String displayComposer() { return composer == null || composer.isBlank() ? tr("metadata.unknown_composer") : composer; }
    public String displayAlbum() { return album == null || album.isBlank() ? tr("metadata.unknown_album") : album; }
    public Path file() { return Path.of(path); }
    public String format() { return extension(file()).toUpperCase(Locale.ROOT); }
    public boolean matches(String query) {
        return (title + " " + artist + " " + composer + " " + album + " " + file().getFileName())
                .toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }
    public Track relocated(Path target) {
        String oldName = file().getFileName().toString();
        String newName = target.getFileName().toString();
        return new Track(target.toString(), title.equals(stem(oldName)) ? stem(newName) : title,
                artist, composer, album, duration, cover, modified);
    }
    public static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
