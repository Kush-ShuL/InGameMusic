package top.mc_plfd_host.ingamemusic.audio;

import top.mc_plfd_host.ingamemusic.library.Track;
import javax.sound.sampled.*;
import java.util.concurrent.*;

/** Streaming PCM on one dedicated worker; no complete-song buffers and no native codecs. */
public final class AudioPlayer implements AutoCloseable {
    public enum Status { STOPPED, LOADING, PLAYING, PAUSED, ENDED, ERROR }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InGameMusic-audio"); t.setDaemon(true); return t;
    });
    private static final class Session {
        final Track track; final double offset;
        volatile boolean cancelled, paused;
        volatile SourceDataLine line;
        volatile AudioInputStream input;
        volatile VoicePcmBuffer voice;
        volatile double position;
        Session(Track track, double offset, boolean paused) { this.track = track; this.offset = offset; this.paused = paused; position = offset; }
        void cancel() {
            cancelled = true;
            SourceDataLine output = line;
            if (output != null) { output.stop(); output.flush(); output.close(); }
            AudioInputStream stream = input;
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }

        }
    }
    private volatile Session session;
    private volatile long revision;
    public long revision() { return revision; }
    private volatile Status status = Status.STOPPED;
    private volatile double volume = .65;
    private volatile String error = "";
    private volatile float level;
    public Track track() { Session s = session; return s == null ? null : s.track; }
    public Status status() { return status; }
    public String error() { return error; }
    public float level() { return level; }
    public double volume() { return volume; }
    public short[] voiceFrame(double gain) {
        Session s = session;
        if (s == null) return null;
        VoicePcmBuffer voice = s.voice;
        SourceDataLine line = s.line;
        if (voice == null) return null;
        if (s.cancelled || s.paused || status != Status.PLAYING || line == null || !line.isOpen()) {
            voice.resetCursor(); return null;
        }
        short[] result = voice.read(line.getMicrosecondPosition() / 1_000_000.0, gain);
        return session == s && !s.cancelled && !s.paused ? result : null;
    }
    public void resetVoiceCursor() { Session s = session; if (s != null && s.voice != null) s.voice.resetCursor(); }
    public void volume(double value) { volume = Math.clamp(value, 0, 1); }
    public double position() {
        Session s = session; if (s == null) return 0;
        SourceDataLine line = s.line;
        return line != null && line.isOpen() ? s.offset + line.getMicrosecondPosition() / 1_000_000.0 : s.position;
    }
    public synchronized void play(Track track) { start(track, 0, false); }
    private synchronized void start(Track track, double seconds, boolean paused) {
        if (session != null) session.cancel();
        Session next = new Session(track, seconds, paused); session = next; revision++;
        status = Status.LOADING; error = "";
        worker.submit(() -> stream(next));
    }
    public synchronized void seek(double seconds) {
        Session s = session;
        if (s != null) start(s.track, Math.clamp(seconds, 0, Math.max(0, s.track.duration() - .05)), s.paused);
    }
    public synchronized void toggle() {
        Session s = session; if (s == null) return;
        if (status == Status.ENDED || status == Status.ERROR || status == Status.STOPPED) { play(s.track); return; }
        s.paused = !s.paused;
        if (s.line != null) { if (s.paused) s.line.stop(); else s.line.start(); }
        if (status != Status.LOADING) status = s.paused ? Status.PAUSED : Status.PLAYING;
    }
    public synchronized void stop() { if (session != null) session.cancel(); session = null; revision++; status = Status.STOPPED; level = 0; }
    /** Called only on the file worker, so Windows releases the playing file before rename/trash. */
    public void awaitStopped() throws Exception {
        if (session == null) worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }
    private void stream(Session s) {
        if (s.cancelled) return;
        try (AudioInputStream pcm = JavaDecoder.open(s.track.file())) {
            s.input = pcm;
            if (s.cancelled) return;
            AudioFormat format = pcm.getFormat();
            int frame = format.getFrameSize();
            byte[] buffer = new byte[2048 * frame];
            long remaining = (long) (s.offset * format.getFrameRate()) * frame;
            // Decode-and-discard gives deterministic seek for every supported compressed format.
            while (remaining > 0 && !s.cancelled) {
                int read = pcm.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break; remaining -= read;
            }
            if (s.cancelled) return;
            s.voice = new VoicePcmBuffer(format.getSampleRate(), format.getChannels());
            SourceDataLine line = AudioSystem.getSourceDataLine(format); s.line = line;
            try {
                line.open(format, Math.max(frame * 2048, (int) (format.getFrameRate() * .12) * frame));
                if (s.cancelled) return;
                if (!s.paused) line.start();
                update(s, s.paused ? Status.PAUSED : Status.PLAYING);
                while (!s.cancelled) {
                    if (s.paused) { Thread.sleep(15); continue; }
                    int read = pcm.read(buffer, 0, buffer.length);
                    if (read < 0) break;
                    s.voice.append(buffer, read);
                    float peak = applyVolume(buffer, read, volume);
                    if (session == s) level = peak;
                    int written = 0;
                    while (written < read && !s.cancelled) {
                        if (s.paused) { Thread.sleep(15); continue; }
                        int available = line.available() / frame * frame;
                        if (available == 0) { Thread.sleep(3); continue; }
                        written += line.write(buffer, written, Math.min(available, read - written));
                    }
                    s.position = s.offset + line.getMicrosecondPosition() / 1_000_000.0;
                }
                if (!s.cancelled) {
                    while (!s.cancelled && line.getBufferSize() - line.available() > 0) Thread.sleep(10);
                    s.position = s.offset + line.getMicrosecondPosition() / 1_000_000.0;
                    update(s, Status.ENDED);
                }
            } finally { line.stop(); line.close(); }
        } catch (Exception e) {
            if (!s.cancelled && session == s) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); update(s, Status.ERROR); }
        } finally { s.input = null; }
    }
    private synchronized void update(Session s, Status next) { if (session == s && !s.cancelled) status = next; }
    public static float applyVolume(byte[] data, int length, double gain) {
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((data[i] & 255) | data[i + 1] << 8);
            sample = (int) Math.round(sample * Math.clamp(gain, 0, 1));
            peak = Math.max(peak, Math.abs(sample));
            data[i] = (byte) sample; data[i + 1] = (byte) (sample >> 8);
        }
        return peak / 32768f;
    }
    @Override public void close() { stop(); worker.shutdownNow(); }
}
