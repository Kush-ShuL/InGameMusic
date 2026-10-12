package top.mc_plfd_host.ingamemusic;

import io.github.jseproject.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.mc_plfd_host.ingamemusic.audio.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DecoderTest {
    @TempDir Path home;
    static AudioInputStream sine(int bits) {
        int frames = 48000, frameSize = 2 * bits / 8;
        byte[] data = new byte[frames * frameSize];
        for (int i = 0; i < frames; i++) {
            int value = (int) (Math.sin(i * 2 * Math.PI * 440 / 48000) * ((1 << bits - 1) - 1) * .25);
            for (int channel = 0; channel < 2; channel++) for (int b = 0; b < bits / 8; b++) data[i * frameSize + channel * bits / 8 + b] = (byte) (value >> (8 * b));
        }
        return new AudioInputStream(new ByteArrayInputStream(data), new AudioFormat(48000, bits, 2, true, false), frames);
    }
    static Path fixture(Path directory, String extension, int bits) throws Exception {
        Path file = directory.resolve("tone." + extension);
        if (extension.equals("aac")) {
            // Hand-authored AAC-LC stereo silence raw_data_block, 44.1 kHz ADTS framing.
            byte[] frame = java.util.HexFormat.of().parseHex("fff1508001bffc211004608c1c");
            try (var out = Files.newOutputStream(file)) { for (int i = 0; i < 44; i++) out.write(frame); }
            return file;
        }
        if (extension.equals("flac") && bits == 24) {
            // Avoid the SPI writer's 24-bit byte-order bug; feed signed samples to the Java encoder.
            var encoder = new org.xiph.flac.StreamEncoder();
            encoder.set_channels(2); encoder.set_bits_per_sample(24); encoder.set_sample_rate(48000);
            encoder.set_total_samples_estimate(48000);
            assertEquals(org.xiph.flac.StreamEncoder.FLAC__STREAM_ENCODER_INIT_STATUS_OK, encoder.init_file(file.toString(), null));
            try (var original = sine(24)) {
                byte[] raw = original.readAllBytes(); int[] samples = new int[raw.length / 3];
                for (int i = 0, j = 0; i < raw.length; i += 3) samples[j++] = (raw[i] & 255) | (raw[i + 1] & 255) << 8 | raw[i + 2] << 16;
                assertTrue(encoder.process_interleaved(samples, 48000)); assertTrue(encoder.finish());
            } finally { encoder.delete(); }
            return file;
        }
        javasound.sampled.spi.AudioCompressionWriter writer = switch (extension) {
            case "mp3" -> new Mp3AudioCompressionWriter(); case "flac" -> new FlacAudioCompressionWriter();
            case "ogg" -> new VorbisAudioCompressionWriter(); case "opus" -> new OpusAudioCompressionWriter(); default -> null;
        };
        try (var source = sine(bits)) {
            if (writer == null) AudioSystem.write(source, AudioFileFormat.Type.WAVE, file.toFile());
            else {
                var type = writer.getAudioFileTypes(source)[0];
                writer.write(source, type, java.util.Map.of(), file.toFile());
            }
        }
        return file;
    }
    @ParameterizedTest @ValueSource(strings = {"flac", "aac", "mp3", "ogg", "opus", "wav"})
    @Timeout(20)
    void decodesRealCompressedBytesToPcm(String extension) throws Exception {
        Path file = fixture(home, extension, 16);
        try (var stream = JavaDecoder.open(file)) {
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, stream.getFormat().getEncoding());
            assertEquals(16, stream.getFormat().getSampleSizeInBits()); assertFalse(stream.getFormat().isBigEndian());
            byte[] decoded = stream.readAllBytes();
            double duration = decoded.length / (double) stream.getFormat().getFrameSize() / stream.getFormat().getFrameRate();
            assertTrue(duration >= .85, extension + " too short: " + duration + "s, " + stream.getFormat());
            assertTrue(duration <= 1.2, extension + " too long: " + duration + "s");
            if (!extension.equals("aac")) {
                long energy = 0; for (int i = 0; i < decoded.length; i += 2) energy += Math.abs((short) ((decoded[i] & 255) | decoded[i + 1] << 8));
                assertTrue(energy / decoded.length > 100, extension + " unexpectedly silent");
            }
        }
    }
    @Test @Timeout(20) void decodes24BitFlacTo16BitOutput() throws Exception {
        Path file = fixture(home, "flac", 24);
        try (var stream = JavaDecoder.open(file); var original = sine(24)) {
            assertEquals(16, stream.getFormat().getSampleSizeInBits());
            byte[] decoded = stream.readAllBytes(), source = original.readAllBytes();
            byte[] expected = new byte[source.length / 3 * 2];
            for (int i = 0, j = 0; i < source.length; i += 3) { expected[j++] = source[i + 1]; expected[j++] = source[i + 2]; }
            assertArrayEquals(expected, decoded);
        }
    }
    @Test @Timeout(20) void opusIgnoresOriginalRateHintAndHonorsPreSkip() throws Exception {
        Path source = fixture(home, "opus", 16), changed = home.resolve("hint.ogg");
        byte[] baseline;
        try (var pcm = JavaDecoder.open(source)) { baseline = pcm.readAllBytes(); }
        try (var in = new org.gagravarr.opus.OpusFile(source.toFile())) {
            var info = in.getInfo(); info.setSampleRate(44100); info.setPreSkip(312);
            try (var out = new org.gagravarr.opus.OpusFile(Files.newOutputStream(changed), info, in.getTags())) {
                org.gagravarr.opus.OpusAudioData packet;
                while ((packet = in.getNextAudioPacket()) != null) out.writeAudioData(packet);
            }
        }
        try (var pcm = JavaDecoder.open(changed)) {
            assertEquals(48000, pcm.getFormat().getSampleRate());
            assertArrayEquals(java.util.Arrays.copyOfRange(baseline, 312 * 4, baseline.length), pcm.readAllBytes());
        }
    }
    @Test void softwareVolumeHandlesSignedSamples() {
        byte[] data = {0, (byte) 128, (byte) 255, 127, 0, 0};
        AudioPlayer.applyVolume(data, data.length, .5);
        assertArrayEquals(new byte[]{0, (byte) 192, 0, 64, 0, 0}, data);
        AudioPlayer.applyVolume(data, data.length, 0); assertArrayEquals(new byte[6], data);
    }
}
