package top.mc_plfd_host.ingamemusic.audio;

/** Visual clock bridges quantized device samples without running away when the device stalls. */
public final class PlaybackClock {
    private long revision = Long.MIN_VALUE, lastTime, sampleTime;
    private double lastSample, shown;
    private boolean wasPlaying;
    public double sample(long session, double measured, double duration, boolean playing, long now) {
        measured = Double.isFinite(measured) ? Math.max(0, measured) : 0;
        double dt = Math.max(0, (now - lastTime) / 1_000_000_000.0);
        if (session != revision || !playing || !wasPlaying || dt > .5 || Math.abs(measured - lastSample) > .75) {
            shown = measured; sampleTime = now;
        } else {
            if (measured != lastSample) sampleTime = now;
            double predicted = measured + Math.min(.15, Math.max(0, (now - sampleTime) / 1_000_000_000.0));
            double candidate = shown + dt;
            shown = Math.min(measured + .15, candidate + (predicted - candidate) * -Math.expm1(-12 * dt));
        }
        revision = session; lastSample = measured; lastTime = now; wasPlaying = playing;
        shown = Math.clamp(shown, 0, duration > 0 ? duration : Double.MAX_VALUE);
        return shown;
    }
}
