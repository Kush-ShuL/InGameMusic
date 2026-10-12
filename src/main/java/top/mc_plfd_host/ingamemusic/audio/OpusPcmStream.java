package top.mc_plfd_host.ingamemusic.audio;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.gagravarr.opus.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Path;
import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;

/** Opus always decodes at 48 kHz; the header's original sample-rate field is only a hint. */
final class OpusPcmStream extends InputStream {
    private final OpusFile file;
    private final OpusDecoder decoder;
    private final int channels;
    private final double gain;
    private OpusAudioData pending;
    private int skip, position;
    private long decodedFrames;
    private byte[] block = new byte[0];
    private boolean closed;
    private OpusPcmStream(Path path) throws Exception {
        file = new OpusFile(path.toFile());
        try {
            var info = file.getInfo(); channels = info.getNumChannels();
            if (channels < 1 || channels > 2) throw new IOException(tr("error.channels"));
            if (info.getChannelMappingFamily() != 0) throw new IOException(tr("error.opus_mapping"));
            decoder = new OpusDecoder(48000, channels); skip = info.getPreSkip();
            gain = Math.pow(10, (short) info.getOutputGain() / (256.0 * 20));
            pending = file.getNextAudioPacket();
        } catch (Exception e) { file.close(); throw e; }
    }
    static AudioInputStream open(Path path) throws Exception {
        var pcm = new OpusPcmStream(path);
        return new AudioInputStream(pcm, new AudioFormat(48000, 16, pcm.channels, true, false), AudioSystem.NOT_SPECIFIED);
    }
    @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (closed) throw new IOException(tr("error.stream_closed"));
        while (position == block.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (pending == null) return -1;
            var packet = pending; pending = file.getNextAudioPacket();
            short[] samples = new short[5760 * channels];
            int count;
            try { count = decoder.decode(packet.getData(), 0, packet.getData().length, samples, 0, 5760, false); }
            catch (OpusException e) { throw new IOException(tr("error.opus_decode", e.getMessage()), e); }
            int end = count;
            if (pending == null && packet.getGranulePosition() > 0)
                end = (int) Math.clamp(packet.getGranulePosition() - decodedFrames, 0, count);
            decodedFrames += count;
            int start = Math.min(skip, end); skip = Math.max(0, skip - count);
            block = new byte[(end - start) * channels * 2]; position = 0;
            int dest = 0;
            for (int i = start * channels; i < end * channels; i++) {
                int value = (int) Math.clamp(Math.round(samples[i] * gain), -32768, 32767);
                block[dest++] = (byte) value; block[dest++] = (byte) (value >> 8);
            }
        }
        int copied = Math.min(length, block.length - position);
        System.arraycopy(block, position, bytes, offset, copied); position += copied; return copied;
    }
    @Override public void close() throws IOException { if (!closed) { closed = true; file.close(); } }
}
