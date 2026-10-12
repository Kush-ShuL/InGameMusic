package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import top.mc_plfd_host.ingamemusic.ui.SmoothScroll;
import static org.junit.jupiter.api.Assertions.*;

class SmoothScrollTest {
    @Test void preservesFractionalWheelInputAndConvergesWithoutOvershoot() {
        var scroll = new SmoothScroll(); scroll.bounds(1200, 300);
        scroll.wheel(72.5); assertEquals(0, scroll.position());
        scroll.advance(1.0 / 60);
        assertTrue(scroll.position() > 0 && scroll.position() < 72.5);
        scroll.wheel(12.25); assertEquals(84.75, scroll.target(), 1e-9);
        for (int i = 0; i < 120; i++) scroll.advance(1.0 / 60);
        assertEquals(84.75, scroll.position(), 1e-9);
    }
    @Test void wheelMomentumContinuesAndReverseInputBrakesImmediately() {
        var scroll = new SmoothScroll(); scroll.bounds(5000, 300); scroll.jump(1000);
        scroll.wheel(100); scroll.advance(.05);
        double position = scroll.position(), speed = scroll.velocity();
        assertTrue(speed > 0);
        scroll.advance(.1);
        assertTrue(scroll.position() > position); assertTrue(scroll.velocity() > 0 && scroll.velocity() < speed);
        scroll.wheel(-20); assertTrue(scroll.velocity() < 0);
        position = scroll.position(); scroll.advance(.02); assertTrue(scroll.position() < position);
        scroll.jump(300); assertEquals(0, scroll.velocity());
        scroll.animateTo(600); assertEquals(300, scroll.position());
        scroll.advance(.1); assertTrue(scroll.position() > 300 && scroll.position() < 600);
    }
    @Test void sameElapsedTimeAtDifferentFrameRatesHasSamePosition() {
        var slow = new SmoothScroll(); var fast = new SmoothScroll();
        for (var s : new SmoothScroll[]{slow, fast}) { s.bounds(3000, 300); s.wheel(900); }
        for (int i = 0; i < 6; i++) slow.advance(1.0 / 30);
        for (int i = 0; i < 48; i++) fast.advance(1.0 / 240);
        assertEquals(slow.position(), fast.position(), 1e-9);
    }
    @Test void draggingAndShrinkingContentCancelPendingMotion() {
        var scroll = new SmoothScroll(); scroll.bounds(1200, 300); scroll.wheel(900);
        scroll.advance(.05); scroll.jump(100); scroll.advance(1);
        assertEquals(100, scroll.position()); assertEquals(100, scroll.target());
        scroll.wheel(900); scroll.bounds(350, 300);
        assertEquals(50, scroll.position()); assertEquals(50, scroll.target());
        scroll.bounds(0, 300); assertEquals(0, scroll.position());
        scroll.wheel(Double.NaN); scroll.advance(Double.POSITIVE_INFINITY);
        assertEquals(0, scroll.position());
    }
}
