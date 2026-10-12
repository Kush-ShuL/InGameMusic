package top.mc_plfd_host.ingamemusic.ui;

import static org.lwjgl.glfw.GLFW.*;

/** Unicode-aware editor shared by search, directory entry and dialogs. */
public final class TextEdit {
    private final UiMotion motion = new UiMotion();
    public static final int FONT_SIZE = 14;
    public record Geometry(int x, int y, int width, int height, int caretX, int caretY) { }
    private int x, y, width, height;
    public Geometry geometry() {
        int caret = Canvas.measure(value.substring(0, cursor), FONT_SIZE);
        int offset = Math.max(0, caret - width + 30);
        return new Geometry(x, y, width, height, 11 + caret - offset, height / 2 + 5 - FONT_SIZE);
    }
    public String value;
    public int cursor, anchor;
    public TextEdit(String value) { this.value = value; cursor = anchor = value.length(); }
    public void insert(String text) {
        if (!text.isEmpty()) {
            text = text.replaceAll("[\\p{Cntrl}]", "");
            if (text.isEmpty()) return;
        }
        int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
        if (value.length() - (end - start) + text.length() > 4096) return;
        value = value.substring(0, start) + text + value.substring(end); cursor = anchor = start + text.length();
    }
    public boolean key(int key, int modifiers, MusicView.Host host) {
        boolean ctrl = (modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0, shift = (modifiers & GLFW_MOD_SHIFT) != 0;
        if (ctrl && key == GLFW_KEY_A) { anchor = 0; cursor = value.length(); return true; }
        if (ctrl && (key == GLFW_KEY_C || key == GLFW_KEY_X)) {
            host.clipboard(value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)));
            if (key == GLFW_KEY_X) insert(""); return true;
        }
        if (ctrl && key == GLFW_KEY_V) { insert(host.clipboard()); return true; }
        if (key == GLFW_KEY_BACKSPACE || key == GLFW_KEY_DELETE) {
            if (cursor != anchor) insert("");
            else if (key == GLFW_KEY_BACKSPACE && cursor > 0) { anchor = value.offsetByCodePoints(cursor, -1); insert(""); }
            else if (key == GLFW_KEY_DELETE && cursor < value.length()) { anchor = value.offsetByCodePoints(cursor, 1); insert(""); }
            return true;
        }
        if (key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT || key == GLFW_KEY_HOME || key == GLFW_KEY_END) {
            cursor = switch (key) {
                case GLFW_KEY_HOME -> 0; case GLFW_KEY_END -> value.length();
                case GLFW_KEY_LEFT -> cursor > 0 ? value.offsetByCodePoints(cursor, -1) : 0;
                default -> cursor < value.length() ? value.offsetByCodePoints(cursor, 1) : value.length();
            };
            if (!shift) anchor = cursor; return true;
        }
        return false;
    }
    private int prefixAt(int width) {
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (Canvas.measure(value.substring(0, middle), FONT_SIZE) <= width) low = middle;
            else high = middle - 1;
        }
        if (low > 0 && low < value.length() && Character.isLowSurrogate(value.charAt(low))) low--;
        return low;
    }
    public void paint(Canvas c, int x, int y, int w, int h, String placeholder, boolean focused) {
        this.x = x; this.y = y; this.width = w; this.height = h;
        motion.begin(System.nanoTime());
        double focusAmount = motion.value("focus", focused);
        c.round(x - 2, y - 2, w + 4, h + 4, 14, UiMotion.opacity(0x36187b70, focusAmount));
        c.glass(x, y, w, h, 12, Canvas.PANEL);
        c.round(x + 1, y + 1, w - 2, h - 2, 11, UiMotion.opacity(0x0c187b70, focusAmount));
        c.clip(x + 10, y + 3, w - 20, h - 6);
        c.text("", 0, 0, 14, Canvas.TEXT);
        int caretX = Canvas.measure(value.substring(0, cursor), 14);
        int offset = Math.max(0, caretX - w + 30);
        if (focused && cursor != anchor) {
            int start = Canvas.measure(value.substring(0, Math.min(cursor, anchor)), 14);
            int end = Canvas.measure(value.substring(0, Math.max(cursor, anchor)), 14);
            c.round(x + 11 + start - offset, y + 7, end - start, h - 14, 2, 0xffc3e2d9);
        }
        if (value.isEmpty()) c.text(placeholder, x + 11, y + h / 2.0 + 5, FONT_SIZE, Canvas.MUTED);
        else {
            int start = prefixAt(offset), end = prefixAt(offset + w);
            if (end < value.length()) end = value.offsetByCodePoints(end, 1);
            int prefix = Canvas.measure(value.substring(0, start), FONT_SIZE);
            c.text(value.substring(start, end), x + 11 + prefix - offset, y + h / 2.0 + 5, FONT_SIZE, Canvas.TEXT);
        }
        if (focused && System.currentTimeMillis() % 1000 < 650) c.line(x + 11 + caretX - offset, y + 9, x + 11 + caretX - offset, y + h - 9, 1, Canvas.ACCENT);
        c.unclip();
    }
}
