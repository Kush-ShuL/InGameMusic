package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import top.mc_plfd_host.ingamemusic.ui.UiMotion;
import static org.junit.jupiter.api.Assertions.*;

class UiMotionTest {
    @Test void hoverAndReleaseEaseWithoutOvershootOrDoubleStepping() {
        UiMotion motion = new UiMotion();
        motion.begin(1_000_000_000L); assertEquals(0, motion.value("button", false));
        motion.begin(1_016_000_000L); double hovered = motion.value("button", true);
        assertTrue(hovered > 0 && hovered < 1);
        assertEquals(hovered, motion.value("button", true));
        motion.begin(1_032_000_000L); double released = motion.value("button", false);
        assertTrue(released > 0 && released < hovered);
        motion.begin(2_000_000_000L); assertEquals(0, motion.value("button", false));
        assertEquals(0x00187b70, UiMotion.opacity(0x80187b70, 0));
        assertEquals(0x80187b70, UiMotion.opacity(0x80187b70, 1));
    }
    @Test void hoverProgressIsIndependentOfFrameRate() {
        double[] result = new double[2]; int[] rates = {30, 240};
        for (int run = 0; run < rates.length; run++) {
            UiMotion motion = new UiMotion();
            motion.begin(1_000_000_000L); motion.value("button", true);
            for (int frame = 1; frame <= rates[run] / 5; frame++) {
                motion.begin(1_000_000_000L + Math.round(frame * 1_000_000_000.0 / rates[run]));
                result[run] = motion.value("button", true);
            }
        }
        assertEquals(result[0], result[1], 1e-8);
    }
}
