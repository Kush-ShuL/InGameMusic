package top.mc_plfd_host.ingamemusic.audio;

/** Bounded, pre-volume mono PCM timeline. Input chunks share one resampling phase. */
public final class VoicePcmBuffer {
    public static final int RATE = 48000, FRAME = 960;
    private final short[] ring = new short[RATE];
    private final double step;
    private final int channels;
    private long inputFrames, outputFrames, cursor = -1;
    private double previous;

    public VoicePcmBuffer(double sampleRate, int channels) {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0 || channels < 1 || channels > 2)
            throw new IllegalArgumentException("Invalid PCM format");
        step = sampleRate / RATE; this.channels = channels;
    }

    public synchronized void append(byte[] pcm, int length) {
        int stride = channels * 2;
        for (int offset = 0; offset + stride <= length; offset += stride) {
            double mono = 0;
            for (int channel = 0; channel < channels; channel++) {
                int i = offset + channel * 2;
                mono += (short) ((pcm[i] & 255) | pcm[i + 1] << 8);
            }
            mono /= channels;
            while (outputFrames * step <= inputFrames) {
                double fraction = inputFrames == 0 ? 1 : outputFrames * step - (inputFrames - 1);
                ring[(int) (outputFrames % ring.length)] = (short) Math.round(previous + (mono - previous) * fraction);
                outputFrames++;
            }
            previous = mono; inputFrames++;
        }
    }

    /** Follow the local device clock, retaining a continuous cursor between 20 ms callbacks. */
    public synchronized short[] read(double seconds, double gain) {
        long playhead = Math.max(0, (long) (seconds * RATE));
        if (cursor < 0 || Math.abs(cursor - playhead) > FRAME * 2) cursor = playhead;
        long oldest = Math.max(0, outputFrames - ring.length);
        if (cursor < oldest) cursor = Math.max(oldest, playhead);
        if (cursor >= outputFrames) return null;
        gain = Double.isFinite(gain) ? Math.clamp(gain, 0, 2) : 0;
        short[] result = new short[FRAME];
        for (int i = 0; i < FRAME && cursor + i < outputFrames; i++) {
            double value = ring[(int) ((cursor + i) % ring.length)] * gain;
            result[i] = (short) Math.clamp(Math.round(value), Short.MIN_VALUE, Short.MAX_VALUE);
        }
        cursor += FRAME;
        return result;
    }

    public synchronized void resetCursor() { cursor = -1; }
}
