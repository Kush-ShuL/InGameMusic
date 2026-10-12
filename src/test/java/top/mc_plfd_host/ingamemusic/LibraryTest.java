package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.mc_plfd_host.ingamemusic.library.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryTest {
    @TempDir Path home;
    private Track track(Path file) throws Exception {
        Files.write(file, new byte[]{1, 2, 3});
        return new Track(file.toString(), Track.stem(file.getFileName().toString()), "artist", "composer", "album", 100, "", 0);
    }
    @Test void renamePersistsAndUpdatesEveryPlaylist() throws Exception {
        var library = new Library(home.resolve("config"));
        Track a = track(home.resolve("甲.mp3")), b = track(home.resolve("乙.flac")); library.upsert(List.of(a, b));
        String id = library.createPlaylist("旅途"); library.addToPlaylist(id, List.of(a.path(), b.path(), a.path()));
        library.rename(List.of(a.path(), b.path()), "{n} - {name}");
        var loaded = new Library(home.resolve("config")).snapshot();
        assertEquals(2, loaded.playlists.getFirst().tracks.size());
        assertTrue(loaded.playlists.getFirst().tracks.getFirst().endsWith("01 - 甲.mp3"));
        assertEquals("01 - 甲", loaded.tracks.getFirst().title());
        assertTrue(Files.exists(home.resolve("02 - 乙.flac")));
    }
    @Test void batchCollisionAndPathTraversalDoNotTouchOriginals() throws Exception {
        var library = new Library(home.resolve("config"));
        Track a = track(home.resolve("a.mp3")), b = track(home.resolve("b.mp3")); library.upsert(List.of(a, b));
        assertThrows(Exception.class, () -> library.rename(List.of(a.path(), b.path()), "same"));
        assertTrue(Files.exists(a.file())); assertTrue(Files.exists(b.file()));
        assertThrows(Exception.class, () -> library.rename(List.of(a.path()), "../escape"));
        assertTrue(Files.exists(a.file()));
    }
    @Test void trashIsRecoverableAndRemovesAllReferences() throws Exception {
        var library = new Library(home.resolve("config")); Track a = track(home.resolve("a.wav")); library.upsert(List.of(a));
        String id = library.createPlaylist("fav"); library.addToPlaylist(id, List.of(a.path()));
        assertEquals(1, library.trash(List.of(a.path())));
        assertFalse(Files.exists(a.file())); assertTrue(library.tracks().isEmpty());
        assertTrue(library.snapshot().playlists.getFirst().tracks.isEmpty());
        try (var files = Files.list(home.resolve(".ingamemusic-trash"))) {
            var all = files.toList(); assertEquals(2, all.size());
            Path audio = all.stream().filter(p -> p.toString().endsWith(".wav")).findFirst().orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(audio));
        }
    }
    @Test void removingFromPlaylistDoesNotDeleteTheFileOrLibraryEntry() throws Exception {
        var library = new Library(home.resolve("config")); Track a = track(home.resolve("a.wav")); library.upsert(List.of(a));
        String id = library.createPlaylist("fav"); library.addToPlaylist(id, List.of(a.path())); library.remove(List.of(a.path()), id);
        assertTrue(Files.exists(a.file())); assertEquals(1, library.tracks().size()); assertTrue(library.snapshot().playlists.getFirst().tracks.isEmpty());
    }
}
