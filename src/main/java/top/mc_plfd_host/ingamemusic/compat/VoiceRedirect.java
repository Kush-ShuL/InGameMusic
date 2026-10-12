package top.mc_plfd_host.ingamemusic.compat;

import top.mc_plfd_host.ingamemusic.audio.AudioPlayer;
import java.util.function.DoubleFunction;

/** Optional integration state without references to SVC classes, safe when SVC is absent. */
public final class VoiceRedirect {
    public static volatile VoiceRedirect active;
    public static volatile boolean available, connected, disabled;
    private final DoubleFunction<short[]> source;
    private final Runnable reset;
    private volatile boolean enabled;
    private volatile double gain = .35;

    public VoiceRedirect(AudioPlayer player) { this(player::voiceFrame, player::resetVoiceCursor); }
    VoiceRedirect(DoubleFunction<short[]> source, Runnable reset) { this.source = source; this.reset = reset; }
    public boolean enabled() { return enabled; }
    public void enabled(boolean value) { enabled = value; reset.run(); }
    public double gain() { return gain; }
    public void gain(double value) { gain = Double.isFinite(value) ? Math.clamp(value, 0, 2) : .35; }
    public short[] frame() {
        if (!enabled || !available || !connected || disabled || gain == 0) {
            reset.run(); return null;
        }
        return source.apply(gain);
    }
    public String statusKey() {
        if (!available) return "svc.unavailable";
        if (!connected) return "svc.disconnected";
        if (disabled) return "svc.disabled";
        return "svc.ready";
    }
}
