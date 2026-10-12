package top.mc_plfd_host.ingamemusic.library;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** All state and disk mutations are serialized by this repository. */
public final class Library {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path home, stateFile;
    private MusicState state;
    public Library(Path home) throws IOException {
        this.home = home; Files.createDirectories(home);
        stateFile = home.resolve("library.json");
        state = new MusicState();
        if (Files.exists(stateFile)) {
            try (Reader reader = Files.newBufferedReader(stateFile)) {
                MusicState loaded = JSON.fromJson(reader, MusicState.class);
                if (loaded == null || loaded.tracks == null || loaded.playlists == null || loaded.folders == null)
                    throw new IOException(tr("error.library_structure"));
                state = loaded;
                state.tracks.removeIf(Objects::isNull);
                state.playlists.removeIf(p -> p == null || p.id == null || p.name == null || p.tracks == null);
                if (!Double.isFinite(state.volume)) state.volume = .65;
                state.volume = Math.clamp(state.volume, 0, 1);
                if (!Double.isFinite(state.svcGain)) state.svcGain = .35;
                state.svcGain = Math.clamp(state.svcGain, 0, 2);
            } catch (Exception e) {
                Files.copy(stateFile, home.resolve("library-corrupt-" + System.currentTimeMillis() + ".json"));
                throw new IOException(tr("error.library_read", e.getMessage()), e);
            }
        }
    }
    public Path home() { return home; }
    public synchronized MusicState snapshot() { return JSON.fromJson(JSON.toJson(state), MusicState.class); }
    public synchronized List<Track> tracks() { return List.copyOf(state.tracks); }
    public synchronized Track find(String path) {
        return state.tracks.stream().filter(t -> t.path().equals(path)).findFirst().orElse(null);
    }
    public synchronized void edit(Consumer<MusicState> action) throws IOException { action.accept(state); save(); }
    public synchronized void upsert(Collection<Track> tracks) throws IOException {
        var byPath = new LinkedHashMap<String, Track>();
        state.tracks.forEach(t -> byPath.put(t.path(), t));
        tracks.forEach(t -> byPath.put(t.path(), t));
        state.tracks = new ArrayList<>(byPath.values()); save();
    }
    public synchronized void save() throws IOException {
        Path temporary = home.resolve("library.json.tmp");
        Files.writeString(temporary, JSON.toJson(state), StandardCharsets.UTF_8);
        try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING); }
    }
    public synchronized String createPlaylist(String name) throws IOException {
        if (name.isBlank()) throw new IOException(tr("error.playlist_empty"));
        var p = new MusicState.Playlist(name.strip()); state.playlists.add(p); save(); return p.id;
    }
    public synchronized void addToPlaylist(String id, Collection<String> paths) throws IOException {
        var playlist = state.playlists.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
        for (String p : paths) if (find(p) != null && !playlist.tracks.contains(p)) playlist.tracks.add(p);
        save();
    }
    public synchronized void remove(Collection<String> paths, String playlist) throws IOException {
        if (playlist == null) {
            state.tracks.removeIf(t -> paths.contains(t.path()));
            state.playlists.forEach(p -> p.tracks.removeIf(paths::contains));
        } else state.playlists.stream().filter(p -> p.id.equals(playlist)).forEach(p -> p.tracks.removeIf(paths::contains));
        save();
    }
    /** Preflight every target before touching a file. Never overwrite a file. */
    public synchronized Map<Path, Path> rename(Collection<String> paths, String pattern) throws IOException {
        if (pattern.isBlank()) throw new IOException(tr("error.filename_empty"));
        var plan = new LinkedHashMap<Path, Path>(); int n = 1;
        for (String path : paths) {
            Path source = Path.of(path);
            if (find(path) == null || !Files.isRegularFile(source)) throw new IOException(tr("error.file_missing", source));
            String stem = pattern.replace("{name}", Track.stem(source.getFileName().toString())).replace("{n}", String.format(Locale.ROOT, "%02d", n++));
            if (stem.matches(".*[\\\\/:*?\"<>|].*") || stem.equals(".") || stem.equals("..") || stem.endsWith(".") || stem.endsWith(" "))
                throw new IOException(tr("error.filename_invalid"));
            Path target = source.resolveSibling(stem + "." + Track.extension(source));
            if (target.equals(source)) continue;
            if (Files.exists(target) || plan.containsValue(target)) throw new IOException(tr("error.file_collision", target.getFileName()));
            plan.put(source, target);
        }
        var moved = new ArrayList<Map.Entry<Path, Path>>();
        try {
            for (var entry : plan.entrySet()) { Files.move(entry.getKey(), entry.getValue()); moved.add(entry); }
        } catch (IOException failure) {
            Collections.reverse(moved);
            for (var entry : moved) {
                try { Files.move(entry.getValue(), entry.getKey()); }
                catch (IOException rollback) { failure.addSuppressed(rollback); relocate(entry.getKey(), entry.getValue()); }
            }
            save(); throw failure;
        }
        plan.forEach(this::relocate); save(); return plan;
    }
    private void relocate(Path source, Path target) {
        for (int i = 0; i < state.tracks.size(); i++) {
            if (state.tracks.get(i).path().equals(source.toString())) state.tracks.set(i, state.tracks.get(i).relocated(target));
        }
        state.playlists.forEach(p -> p.tracks.replaceAll(s -> s.equals(source.toString()) ? target.toString() : s));
    }
    /** The adjacent recycle directory keeps moves on the same filesystem, including custom drives. */
    public synchronized int trash(Collection<String> paths) throws IOException {
        int count = 0;
        for (String path : paths) {
            Path source = Path.of(path);
            if (find(path) == null) continue;
            if (Files.exists(source)) {
                Path folder = source.getParent().resolve(".ingamemusic-trash"); Files.createDirectories(folder);
                Path target = folder.resolve(UUID.randomUUID() + "_" + source.getFileName());
                Path record = target.resolveSibling(target.getFileName() + ".restore.json");
                Files.writeString(record, JSON.toJson(Map.of("original", source.toString(), "trashed", target.toString())));
                try { Files.move(source, target); } catch (IOException e) { Files.deleteIfExists(record); throw e; }
            }
            remove(List.of(path), null); count++;
        }
        return count;
    }
}
