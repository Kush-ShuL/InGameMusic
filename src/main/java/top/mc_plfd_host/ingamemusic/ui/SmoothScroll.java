package top.mc_plfd_host.ingamemusic.ui;

/** Pixel-based, frame-rate independent scrolling. Dragging bypasses easing to follow the pointer. */
public final class SmoothScroll {
    private static final double FRICTION = 8.5, MAX_SPEED = 6000;
    private double position, target, maximum, velocity;
    public double position() { return position; }
    public double target() { return target; }
    public double velocity() { return velocity; }
    public void bounds(double content, double viewport) {
        maximum = Math.max(0, content - viewport);
        position = Math.clamp(position, 0, maximum); target = Math.clamp(target, 0, maximum);
        velocity = (target - position) * FRICTION;
    }
    public void wheel(double pixels) {
        if (!Double.isFinite(pixels) || pixels == 0) return;
        if (Math.signum(pixels) != Math.signum(velocity)) velocity = 0;
        velocity = Math.clamp(velocity + pixels * FRICTION, -MAX_SPEED, MAX_SPEED);
        target = Math.clamp(position + velocity / FRICTION, 0, maximum);
        velocity = (target - position) * FRICTION;
    }
    public void animateTo(double pixels) { target = Math.clamp(pixels, 0, maximum); velocity = (target - position) * FRICTION; }
    public void jump(double pixels) { position = target = Math.clamp(pixels, 0, maximum); velocity = 0; }
    public void reset() { position = target = velocity = 0; }
    public void advance(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return;
        double decay = Math.exp(-FRICTION * seconds);
        position = Math.clamp(position + velocity * (1 - decay) / FRICTION, 0, maximum);
        velocity *= decay;
        if (Math.abs(velocity) < .4 || position == 0 && velocity < 0 || position == maximum && velocity > 0) { position = target; velocity = 0; }
    }
}
