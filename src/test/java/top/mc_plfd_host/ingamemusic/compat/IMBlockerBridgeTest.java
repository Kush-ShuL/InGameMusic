package top.mc_plfd_host.ingamemusic.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.*;
import javax.tools.ToolProvider;
import top.mc_plfd_host.ingamemusic.ui.TextEdit;
import static org.junit.jupiter.api.Assertions.*;

class IMBlockerBridgeTest {
    @TempDir Path home;
    private static final String ROOT = "io.github.reserveword.imblocker.common.";
    // Isolated focus protocol fixture: exercises reflective/default-method dispatch without OS IME calls.
    private URLClassLoader fixture(String updateMethod) throws Exception {
        Path dir = home.resolve(updateMethod); Files.createDirectories(dir);
        String[] types = {
            "gui.FocusableObject", "public interface FocusableObject { default void deliverFocus() { FocusManager.focus = this; } default void lostFocus() { FocusManager.focus = null; } boolean getPreferredState(); boolean getPreferredEnglishState(); Rectangle getBoundsAbs(); Point getCaretPos(); int getFontHeight(); double getGuiScale(); }",
            "gui.FocusableWidget", "public interface FocusableWidget extends FocusableObject { FocusContainer getFocusContainer(); boolean isRenderable(); }",
            "gui.FocusManager", "public class FocusManager { public static Object focus; public static boolean isTrackingFocus; public static boolean isFocusLocated; public static Object getFocusOwner() { return focus; } }",
            "gui.FocusContainer", "public class FocusContainer { public static final FocusContainer MINECRAFT = new FocusContainer(); public static boolean probe; public FocusableWidget candidate; public int requests; public double getGuiScale() { return 4; } public void requestFocus(FocusableWidget w) { candidate = w; requests++; if (!probe) switchFocus(w); } public void removeFocus(FocusableWidget w) { if (candidate == w) { w.lostFocus(); candidate = null; } } public void switchFocus(FocusableWidget w) { if (candidate != w) throw new AssertionError(); w.deliverFocus(); if (FocusManager.isTrackingFocus) FocusManager.isFocusLocated = true; } }",
            "gui.Point", "public record Point(int x, int y) {}",
            "gui.Rectangle", "public record Rectangle(int x, int y, int width, int height) {}",
            "IMManager", "public class IMManager { public static int caretUpdates, fontUpdates; public static void " + updateMethod + "() { caretUpdates++; } public static void updateCompositionFontSize() { fontUpdates++; } }"
        };
        var args = new java.util.ArrayList<String>(); args.add("-d"); args.add(dir.toString());
        for (int i = 0; i < types.length; i += 2) {
            String name = ROOT + types[i]; int dot = name.lastIndexOf('.');
            Path source = dir.resolve(name.substring(dot + 1) + ".java");
            Files.writeString(source, "package " + name.substring(0, dot) + "; " + types[i + 1]); args.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }
    @Test void absentModNeedsNoDependency() { assertNull(IMBlockerBridge.connect(new ClassLoader(null) {})); }
    @Test void supports603FocusCaretProbeAndCleanup() throws Exception { exercise("updateCompositionWindowPos"); }
    @Test void acceptsNewerCaretMethodName() throws Exception { exercise("updateCaretPosition"); }
    private void exercise(String method) throws Exception {
        try (var loader = fixture(method); var bridge = IMBlockerBridge.connect(loader)) {
            assertNotNull(bridge);
            var focus = loader.loadClass(ROOT + "gui.FocusManager");
            var containerType = loader.loadClass(ROOT + "gui.FocusContainer");
            var widgetType = loader.loadClass(ROOT + "gui.FocusableWidget");
            Object container = containerType.getField("MINECRAFT").get(null);
            var geometry = new TextEdit.Geometry(236, 30, 534, 36, 39, 9);
            bridge.sync(geometry, 10, 20, .5);
            Object widget = focus.getMethod("getFocusOwner").invoke(null);
            assertNotNull(widget); assertEquals(true, widgetType.getMethod("getPreferredState").invoke(widget));
            assertEquals(2.0, widgetType.getMethod("getGuiScale").invoke(widget));
            Object bounds = widgetType.getMethod("getBoundsAbs").invoke(widget);
            assertEquals(512, bounds.getClass().getMethod("x").invoke(bounds));
            assertEquals(140, bounds.getClass().getMethod("y").invoke(bounds));
            Object caret = widgetType.getMethod("getCaretPos").invoke(widget);
            assertEquals(78, caret.getClass().getMethod("x").invoke(caret));
            bridge.sync(geometry, 10, 20, .5);
            assertEquals(1, containerType.getField("requests").getInt(container));
            assertFalse(bridge.consumeFocusProbe());
            bridge.close(); assertNull(focus.getField("focus").get(null));
            assertNull(containerType.getField("candidate").get(container));
            containerType.getField("probe").setBoolean(null, true);
            bridge.sync(geometry, 10, 20, .5);
            assertNull(focus.getField("focus").get(null));
            focus.getField("isTrackingFocus").setBoolean(null, true);
            assertTrue(bridge.consumeFocusProbe());
            assertSame(widget, focus.getField("focus").get(null));
            assertTrue(focus.getField("isFocusLocated").getBoolean(null));
            bridge.close(); assertNull(focus.getField("focus").get(null));
        }
    }
    @Test void suppliedJarExposesRequiredAbiWithoutStartingMinecraft() throws Exception {
        String path = System.getProperty("imblocker.testJar");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null, "Optional local IMBlocker JAR ABI check");
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(path).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> widget = loader.loadClass(ROOT + "gui.FocusableWidget");
            Class<?> container = loader.loadClass(ROOT + "gui.FocusContainer");
            for (String method : new String[]{"requestFocus", "removeFocus", "switchFocus"}) assertNotNull(container.getMethod(method, widget));
            assertNotNull(container.getMethod("getGuiScale")); assertNotNull(container.getField("MINECRAFT"));
            var focus = loader.loadClass(ROOT + "gui.FocusManager");
            assertNotNull(focus.getField("isTrackingFocus")); assertNotNull(focus.getMethod("getFocusOwner"));
            assertNotNull(loader.loadClass(ROOT + "gui.Rectangle").getConstructor(int.class, int.class, int.class, int.class));
            assertNotNull(loader.loadClass(ROOT + "gui.Point").getConstructor(int.class, int.class));
            var manager = loader.loadClass(ROOT + "IMManager");
            assertNotNull(manager.getMethod("updateCompositionWindowPos")); assertNotNull(manager.getMethod("updateCompositionFontSize"));
        }
    }
}
