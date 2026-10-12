package top.mc_plfd_host.ingamemusic.audio;

import org.xiph.flac.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;

/** Direct Java FLAC decoder: correct 8/16/24/32-bit scaling and no SPI zero-read loop. */
final class FlacPcmStream extends InputStream {
    private final InputStream input;
    private final StreamDecoder decoder = new StreamDecoder();
    private StreamInfo info;
    private byte[] block = new byte[0];
    private int position;
    private boolean closed;
    private String error;
    private FlacPcmStream(Path path) throws IOException {
        input = new BufferedInputStream(Files.newInputStream(path), 65536);
        int result = decoder.init_FILE(input, (d, frame, samples, offset) -> {
            int channels = frame.header.channels, count = frame.header.blocksize, bits = frame.header.bits_per_sample;
            block = new byte[count * channels * 2]; position = 0;
            int dest = 0;
            for (int i = 0; i < count; i++) for (int channel = 0; channel < channels; channel++) {
                int sample = samples[channel][i];
                sample = bits > 16 ? sample >> (bits - 16) : sample << (16 - bits);
                block[dest++] = (byte) sample; block[dest++] = (byte) (sample >> 8);
            }
            return StreamDecoder.FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
        }, (d, metadata) -> { if (metadata instanceof StreamInfo streamInfo) info = streamInfo; },
                (d, status) -> error = StreamDecoder.FLAC__StreamDecoderErrorStatusString[status]);
        try {
            if (result != StreamDecoder.FLAC__STREAM_DECODER_INIT_STATUS_OK || !decoder.process_until_end_of_metadata() || info == null)
                throw new IOException(tr("error.flac_header"));
            if (info.channels < 1 || info.channels > 2) throw new IOException(tr("error.channels"));
        } catch (IOException | RuntimeException e) { close(); throw e; }
    }
    static AudioInputStream open(Path path) throws IOException {
        var pcm = new FlacPcmStream(path);
        return new AudioInputStream(pcm, new AudioFormat(pcm.info.sample_rate, 16, pcm.info.channels, true, false),
                pcm.info.total_samples > 0 ? pcm.info.total_samples : AudioSystem.NOT_SPECIFIED);
    }
    @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (closed) throw new IOException(tr("error.stream_closed"));
        while (position == block.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (decoder.get_state() == StreamDecoder.FLAC__STREAM_DECODER_END_OF_STREAM) return -1;
            if (!decoder.process_single() || error != null) throw new IOException(tr("error.flac_decode", error));
        }
        int copied = Math.min(length, block.length - position);
        System.arraycopy(block, position, bytes, offset, copied); position += copied; return copied;
    }
    @Override public void close() throws IOException {
        if (!closed) { closed = true; decoder.delete(); input.close(); }
    }
}
