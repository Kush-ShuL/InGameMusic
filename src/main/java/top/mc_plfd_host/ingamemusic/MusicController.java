package top.mc_plfd_host.ingamemusic;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import top.mc_plfd_host.ingamemusic.audio.AudioPlayer;
import top.mc_plfd_host.ingamemusic.library.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MusicController implements AutoCloseable {
    public final Library library;
    public final AudioPlayer player = new AudioPlayer();
    public final top.mc_plfd_host.ingamemusic.compat.VoiceRedirect voiceRedirect = new top.mc_plfd_host.ingamemusic.compat.VoiceRedirect(player);
    private final top.mc_plfd_host.ingamemusic.audio.PlaybackClock displayClock = new top.mc_plfd_host.ingamemusic.audio.PlaybackClock();
    public double displayPosition(long now) {
        Track track = player.track();
        return displayClock.sample(player.revision(), player.position(), track == null ? 0 : track.duration(), player.status() == AudioPlayer.Status.PLAYING, now);
    }
    private final Metadata metadata;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InGameMusic-library"); t.setDaemon(true); return t;
    });
    public volatile MusicState state;
    public volatile String notice = tr("status.welcome");
    public volatile boolean busy;
    public volatile long revision;
    private List<String> queue = List.of();
    private final Random random = new Random();
    public MusicController(Path home) throws IOException {
        library = new Library(home); metadata = new Metadata(home);
        refresh(); player.volume(state.volume);
        voiceRedirect.enabled(state.simpleVoiceChatRedirect); voiceRedirect.gain(state.svcGain);
    }
    public void refresh() { state = library.snapshot(); revision++; }
    @FunctionalInterface public interface Job { void run() throws Exception; }
    public void submit(String label, Job job) {
        io.submit(() -> {
            busy = true; notice = label;
            try { job.run(); refresh(); notice = tr("status.done", label); }
            catch (Exception e) { refresh(); notice = tr("status.failed", Objects.toString(e.getMessage(), e.getClass().getSimpleName())); }
            finally { busy = false; revision++; }
        });
    }
    public void edit(String label, Consumer<MusicState> action) { submit(label, () -> library.edit(action)); }
    public void toggleVoiceRedirect() {
        boolean enabled = !voiceRedirect.enabled(); voiceRedirect.enabled(enabled);
        edit(tr("action.svc_redirect"), s -> s.simpleVoiceChatRedirect = enabled);
    }
    public void persistVoiceGain() {
        double gain = voiceRedirect.gain(); edit(tr("action.svc_gain"), s -> s.svcGain = gain);
    }
    public void importPaths(Collection<Path> inputs, boolean rememberFolders) {
        var snapshot = List.copyOf(inputs);
        submit(tr("action.import"), () -> {
            var files = new LinkedHashSet<Path>();
            var folders = new ArrayList<String>();
            var warnings = new ArrayList<String>();
            for (Path input : snapshot) {
                Path path = input.toAbsolutePath().normalize();
                if (Files.isDirectory(path)) {
                    folders.add(path.toString());
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                            return dir.getFileName() != null && dir.getFileName().toString().equals(".ingamemusic-trash") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && Track.supported(file)) files.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path file, IOException error) { warnings.add(file.toString()); return FileVisitResult.CONTINUE; }
                    });
                } else if (Files.isRegularFile(path) && Track.supported(path)) files.add(path);
                else warnings.add(path.toString());
            }
            int done = 0; var batch = new ArrayList<Track>();
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) break;
                notice = tr("status.scanning", ++done, files.size(), file.getFileName());
                try {
                    Track known = library.find(file.toRealPath().toString());
                    batch.add(known != null && known.modified() == Files.getLastModifiedTime(file).toMillis() ? known : metadata.read(file));
                } catch (Exception e) { warnings.add(file.getFileName() + ": " + e.getMessage()); }
                if (batch.size() == 25) { library.upsert(batch); batch.clear(); refresh(); }
            }
            library.upsert(batch);
            if (rememberFolders) library.edit(s -> folders.forEach(f -> { if (!s.folders.contains(f)) s.folders.add(f); }));
            if (!warnings.isEmpty()) throw new IOException(tr("error.import_partial", warnings.size(), warnings.getFirst()));
        });
    }
    public synchronized void play(Track track, List<Track> context) {
        queue = context.stream().map(Track::path).toList(); player.play(track);
    }
    public synchronized void toggle() {
        if (player.track() == null && !state.tracks.isEmpty()) play(state.tracks.getFirst(), state.tracks);
        else player.toggle();
    }
    public synchronized void next(boolean previous, boolean automatic) {
        var available = queue.stream().map(library::find).filter(Objects::nonNull).toList();
        if (available.isEmpty()) { player.stop(); return; }
        Track current = player.track();
        int index = current == null ? -1 : java.util.stream.IntStream.range(0, available.size()).filter(i -> available.get(i).path().equals(current.path())).findFirst().orElse(-1);
        if (automatic && "ONE".equals(state.mode) && index >= 0) { player.play(available.get(index)); return; }
        int target;
        if ("SHUFFLE".equals(state.mode) && available.size() > 1) {
            target = random.nextInt(available.size() - 1); if (target >= index && index >= 0) target++;
        } else target = index + (previous ? -1 : 1);
        if (automatic && "ORDER".equals(state.mode) && target >= available.size()) { player.stop(); return; }
        player.play(available.get(Math.floorMod(target, available.size())));
    }
    public void tick() { if (player.status() == AudioPlayer.Status.ENDED) next(false, true); }
    public void cycleMode() {
        String next = switch (state.mode) { case "ORDER" -> "LOOP"; case "LOOP" -> "ONE"; case "ONE" -> "SHUFFLE"; default -> "ORDER"; };
        edit(tr("action.mode"), s -> s.mode = next);
    }
    public String modeLabel() { return switch (state.mode) { case "LOOP" -> tr("mode.loop"); case "ONE" -> tr("mode.one"); case "SHUFFLE" -> tr("mode.shuffle"); default -> tr("mode.order"); }; }
    public void persistVolume() { double value = player.volume(); edit(tr("action.volume"), s -> s.volume = value); }
    public void stopIfSelected(Collection<String> selection) {
        Track track = player.track(); if (track != null && selection.contains(track.path())) player.stop();
    }
    @Override public void close() {
        voiceRedirect.enabled(false);
        if (top.mc_plfd_host.ingamemusic.compat.VoiceRedirect.active == voiceRedirect)
            top.mc_plfd_host.ingamemusic.compat.VoiceRedirect.active = null;
        player.close(); io.shutdownNow();
    }
}
