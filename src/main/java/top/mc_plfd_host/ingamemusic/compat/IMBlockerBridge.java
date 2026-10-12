package top.mc_plfd_host.ingamemusic.compat;

import java.lang.reflect.*;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import top.mc_plfd_host.ingamemusic.ui.TextEdit;

/** Optional IMBlocker focus adapter. No IMBlocker classes are linked when it is absent. */
public final class IMBlockerBridge implements AutoCloseable {
    private static final String ROOT = "io.github.reserveword.imblocker.common.";
    private final Object container, widget;
    private final Method request, remove, switchFocus, containerScale, owner, updateCaret, updateFont;
    private final Field tracking;
    private final Constructor<?> rectangle, point;
    private volatile Layout layout;
    private boolean registered;
    private volatile boolean failed;
    private record Layout(TextEdit.Geometry field, double left, double top, double scale) { }

    public static IMBlockerBridge connect(ClassLoader loader) {
        try { return new IMBlockerBridge(loader); }
        catch (ClassNotFoundException absent) { return null; }
        catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            LoggerFactory.getLogger(IMBlockerBridge.class).warn("Cannot initialize IMBlocker integration", error);
            return null;
        }
    }
    private IMBlockerBridge(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> widgetType = Class.forName(ROOT + "gui.FocusableWidget", false, loader);
        Class<?> containerType = Class.forName(ROOT + "gui.FocusContainer", false, loader);
        Class<?> focusType = Class.forName(ROOT + "gui.FocusManager", false, loader);
        Class<?> managerType = Class.forName(ROOT + "IMManager", false, loader);
        request = containerType.getMethod("requestFocus", widgetType);
        remove = containerType.getMethod("removeFocus", widgetType);
        switchFocus = containerType.getMethod("switchFocus", widgetType);
        containerScale = containerType.getMethod("getGuiScale");
        owner = focusType.getMethod("getFocusOwner");
        tracking = focusType.getField("isTrackingFocus");
        Method caret;
        // 6.0.3 uses updateCompositionWindowPos; newer releases renamed this method.
        try { caret = managerType.getMethod("updateCompositionWindowPos"); }
        catch (NoSuchMethodException newer) { caret = managerType.getMethod("updateCaretPosition"); }
        updateCaret = caret;
        updateFont = managerType.getMethod("updateCompositionFontSize");
        rectangle = Class.forName(ROOT + "gui.Rectangle", false, loader).getConstructor(int.class, int.class, int.class, int.class);
        point = Class.forName(ROOT + "gui.Point", false, loader).getConstructor(int.class, int.class);
        container = containerType.getField("MINECRAFT").get(null);
        widget = Proxy.newProxyInstance(loader, new Class<?>[]{widgetType}, this::invoke);
    }
    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "InGameMusic input";
        };
        Layout current = layout;
        var field = current == null ? new TextEdit.Geometry(0, 0, 0, 0, 0, 0) : current.field;
        double base = ((Number) containerScale.invoke(container)).doubleValue();
        double scale = base * (current == null ? 1 : current.scale);
        return switch (method.getName()) {
            case "getFocusContainer" -> container;
            case "getPreferredState", "isRenderable" -> current != null && !failed;
            case "getPreferredEnglishState" -> false;
            case "getGuiScale" -> scale;
            case "getFontHeight" -> TextEdit.FONT_SIZE;
            case "getBoundsAbs" -> rectangle.newInstance(
                    pixel((current == null ? 0 : current.left) * base + field.x() * scale),
                    pixel((current == null ? 0 : current.top) * base + field.y() * scale),
                    pixel(field.width() * scale), pixel(field.height() * scale));
            case "getCaretPos" -> point.newInstance(pixel(field.caretX() * scale), pixel(field.caretY() * scale));
            default -> {
                if (!method.isDefault()) throw new UnsupportedOperationException(method.toString());
                Object result = InvocationHandler.invokeDefault(proxy, method, args == null ? new Object[0] : args);
                if (method.getName().equals("deliverFocus")) { updateCaret.invoke(null); updateFont.invoke(null); }
                yield result;
            }
        };
    }
    private static int pixel(double value) { return (int) Math.round(value); }

    /** Called after input and layout, on the client thread. Never changes OS/window focus. */
    public void sync(TextEdit.Geometry field, double left, double top, double scale) {
        if (failed) return;
        Layout next = field == null ? null : new Layout(field, left, top, scale);
        boolean changed = !Objects.equals(layout, next);
        layout = next;
        try {
            if (next == null) {
                if (registered) { registered = false; remove.invoke(container, widget); }
            } else {
                if (!registered) { registered = true; request.invoke(container, widget); changed = true; }
                if (changed && owner.invoke(null) == widget) {
                    updateCaret.invoke(null); updateFont.invoke(null);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { disable(error); }
    }
    /** IMBlocker's NUL probe is a focus query, never a text edit. */
    public boolean consumeFocusProbe() {
        if (failed) return false;
        try {
            if (!tracking.getBoolean(null)) return false;
            if (layout != null && registered) switchFocus.invoke(container, widget);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { disable(error); return false; }
    }
    private void disable(Throwable error) {
        failed = true; layout = null;
        try { if (registered) remove.invoke(container, widget); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError cleanup) { error.addSuppressed(cleanup); }
        registered = false;
        LoggerFactory.getLogger(IMBlockerBridge.class).warn("IMBlocker integration disabled after an API error", error);
    }
    @Override public void close() { sync(null, 0, 0, 1); }
}
