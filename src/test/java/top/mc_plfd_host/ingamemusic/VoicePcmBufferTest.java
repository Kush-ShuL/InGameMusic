package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import top.mc_plfd_host.ingamemusic.audio.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class VoicePcmBufferTest {
    private static byte[] pcm(int frames, int left, int right) {
        byte[] data = new byte[frames * 4];
        for (int i = 0; i < data.length; i += 4) {
            data[i] = (byte) left; data[i + 1] = (byte) (left >> 8);
            data[i + 2] = (byte) right; data[i + 3] = (byte) (right >> 8);
        }
        return data;
    }
    @Test void downmixAndGainAreIndependentOfLocalVolumeAndClipSafely() {
        var buffer = new VoicePcmBuffer(48000, 2);
        byte[] data = pcm(4800, 20000, 10000);
        buffer.append(data, data.length);
        AudioPlayer.applyVolume(data, data.length, 0);
        assertEquals(0, data[0]);
        for (short sample : buffer.read(0, .35)) assertEquals(5250, sample);
        for (short sample : buffer.read(.02, 2)) assertEquals(30000, sample);
        var clipped = new VoicePcmBuffer(48000, 2);
        data = pcm(1920, -30000, -30000); clipped.append(data, data.length);
        for (short sample : clipped.read(0, 2)) assertEquals(Short.MIN_VALUE, sample);
    }
    @Test void resamplingHasContinuousPhaseAcrossArbitraryDecoderChunks() {
        for (int rate : new int[]{22050, 44100, 48000, 96000, 192000}) {
            byte[] data = new byte[rate * 2];
            for (int i = 0; i < rate; i++) {
                int sample = (int) (12000 * Math.sin(2 * Math.PI * 440 * i / rate));
                data[i * 2] = (byte) sample; data[i * 2 + 1] = (byte) (sample >> 8);
            }
            var whole = new VoicePcmBuffer(rate, 1);
            var split = new VoicePcmBuffer(rate, 1);
            whole.append(data, data.length);
            for (int i = 0; i < data.length; i += 274) {
                byte[] chunk = Arrays.copyOfRange(data, i, Math.min(data.length, i + 274));
                split.append(chunk, chunk.length);
            }
            for (int i = 0; i < 50; i++) {
                short[] actual = split.read(i * .02, 1);
                assertArrayEquals(whole.read(i * .02, 1), actual);
                assertEquals(960, actual.length);
                for (int j = 0; j < 958; j++)
                    assertEquals(12000 * Math.sin(2 * Math.PI * 440 * (i * 960 + j) / 48000), actual[j], 30);
            }
            assertNull(split.read(1, 1));
        }
    }
    @Test void resumeAndLateCallbacksFollowCurrentPlaybackWithoutReplayingBacklog() {
        var buffer = new VoicePcmBuffer(48000, 2);
        byte[] old = pcm(48000, 1000, 1000), recent = pcm(48000, 7000, 7000);
        buffer.append(old, old.length); buffer.read(0, 1);
        buffer.append(recent, recent.length);
        for (short sample : buffer.read(1.5, 1)) assertEquals(7000, sample);
        buffer.resetCursor();
        for (short sample : buffer.read(1.9, 1)) assertEquals(7000, sample);
        assertNull(buffer.read(3, 1));
        assertNull(new VoicePcmBuffer(48000, 1).read(0, 1));
    }
}
