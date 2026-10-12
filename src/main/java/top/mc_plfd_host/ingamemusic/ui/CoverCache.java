package top.mc_plfd_host.ingamemusic.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import top.mc_plfd_host.ingamemusic.library.Track;

public final class CoverCache implements AutoCloseable {
    private final Map<String, BufferedImage> cache = Collections.synchronizedMap(new LinkedHashMap<>(32, .75f, true));
    private final Set<String> pending = ConcurrentHashMap.newKeySet(), failed = ConcurrentHashMap.newKeySet();
    private final ExecutorService io = worker("InGameMusic-covers"), generator = worker("InGameMusic-generated-covers");
    private final Path generatedDirectory;
    private volatile boolean closed;
    public CoverCache() { this(null); }
    public CoverCache(Path generatedDirectory) { this.generatedDirectory = generatedDirectory; }
    private static ExecutorService worker(String name) {
        return Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; });
    }
    public BufferedImage get(Track track, boolean generate) {
        if (track == null || closed) return null;
        String cover = track.cover();
        if (cover != null && !cover.isEmpty()) {
            BufferedImage original = get(cover);
            if (original != null || !failed.contains(cover)) return original;
        }
        if (!generate) return null;
        String key = "generated:" + track.path() + ":" + track.modified();
        return load(key, generator, () -> {
            byte[] hash = GeneratedCover.hash(track.file());
            Path file = generatedDirectory == null ? null : generatedDirectory.resolve("v2-" + HexFormat.of().formatHex(hash) + ".png");
            if (file != null && Files.isRegularFile(file)) {
                try { var stored = ImageIO.read(file.toFile()); if (stored != null) return stored; }
                catch (java.io.IOException ignored) { /* Rebuild a damaged cache entry. */ }
            }
            var image = GeneratedCover.render(hash);
            if (file != null && !closed) {
                Path temporary = null;
                try {
                    Files.createDirectories(generatedDirectory);
                    temporary = Files.createTempFile(generatedDirectory, "cover-", ".tmp");
                    ImageIO.write(image, "png", temporary.toFile());
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException ignored) { /* A read-only cache must not hide the generated image. */ }
                finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { } }
            }
            return image;
        });
    }
    public BufferedImage get(String path) {
        if (path == null || path.isEmpty()) return null;
        return load(path, io, () -> ImageIO.read(Path.of(path).toFile()));
    }
    @FunctionalInterface private interface ImageLoader { BufferedImage read() throws Exception; }
    private BufferedImage load(String key, ExecutorService worker, ImageLoader loader) {
        if (closed || failed.contains(key)) return null;
        BufferedImage result = cache.get(key);
        if (result == null && pending.add(key)) {
            try { worker.submit(() -> {
                try {
                    BufferedImage image = loader.read();
                    if (image != null && !closed) synchronized (cache) {
                        cache.put(key, image); while (cache.size() > 48) cache.remove(cache.keySet().iterator().next());
                    } else if (!closed) failed.add(key);
                } catch (Exception e) { if (!closed) failed.add(key); }
                finally { pending.remove(key); }
            }); } catch (RejectedExecutionException stopped) { pending.remove(key); }
        }
        return result;
    }
    @Override public void close() { closed = true; io.shutdownNow(); generator.shutdownNow(); cache.clear(); pending.clear(); failed.clear(); }
}
