package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import top.mc_plfd_host.ingamemusic.audio.PlaybackClock;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackClockTest {
    @Test void interpolatesQuantizedDeviceSamplesAndStopsAtPause() {
        var clock = new PlaybackClock(); long now = 1_000_000_000L;
        assertEquals(10, clock.sample(1, 10, 180, true, now));
        double last = 10;
        for (int frame = 1; frame <= 30; frame++) {
            double measured = 10 + (frame / 6) * .1;
            double value = clock.sample(1, measured, 180, true, now + Math.round(frame * 1_000_000_000.0 / 60));
            assertTrue(value > last); assertTrue(value - last < .04); last = value;
        }
        assertEquals(10.5, clock.sample(1, 10.5, 180, false, now + 520_000_000L));
        assertEquals(10.5, clock.sample(1, 10.5, 180, false, now + 900_000_000L));
    }
    @Test void seekAndTrackChangeResetWhileStalledDevicesCannotRunAway() {
        var clock = new PlaybackClock(); long now = 1_000_000_000L;
        clock.sample(1, 20, 180, true, now);
        for (int frame = 1; frame <= 120; frame++) {
            double value = clock.sample(1, 20, 180, true, now + frame * 16_000_000L);
            assertTrue(value >= 20 && value <= 20.15);
        }
        assertEquals(80, clock.sample(2, 80, 180, false, now + 2_000_000_000L));
        assertEquals(0, clock.sample(3, 0, 120, true, now + 2_010_000_000L));
        assertEquals(120, clock.sample(4, 125, 120, false, now + 2_020_000_000L));
    }
}
