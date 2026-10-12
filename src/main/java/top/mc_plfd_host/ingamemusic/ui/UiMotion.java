package top.mc_plfd_host.ingamemusic.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small bounded animation state; effects change GPU geometry/opacity, never texture contents. */
public final class UiMotion {
    private static final class State { double value; long frame = -1; }
    private final Map<String, State> states = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, State> entry) { return size() > 512; }
    };
    private long time, frame;
    private double elapsed;
    public void begin(long now) {
        elapsed = time == 0 ? 0 : Math.max(0, (now - time) / 1_000_000_000.0);
        time = now; frame++;
    }
    public double value(String key, boolean active) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.frame != frame) {
            double target = active ? 1 : 0;
            state.value += (target - state.value) * -Math.expm1(-22 * elapsed);
            if (Math.abs(state.value - target) < .002) state.value = target;
            state.frame = frame;
        }
        return state.value;
    }
    public static int opacity(int color, double fraction) {
        return color & 0xffffff | (int) Math.round((color >>> 24) * Math.clamp(fraction, 0, 1)) << 24;
    }
}
