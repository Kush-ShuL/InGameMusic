package top.mc_plfd_host.ingamemusic.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import top.mc_plfd_host.ingamemusic.InGameMusic;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/** Menu screens do not enqueue vanilla key bindings. Handle the opening press directly. */
public final class MenuShortcut {
    private MenuShortcut() { }
    public static boolean handle(MinecraftClient client, int action, KeyInput input) {
        if (action != GLFW_PRESS || InGameMusic.openKey == null || !InGameMusic.openKey.matchesKey(input)
                || client.getOverlay() != null) return false;
        var screen = client.currentScreen;
        if (screen instanceof MusicScreen music) {
            if (music.hasTextFocus()) return false;
            music.close();
            return true;
        }
        if (client.world != null || screen == null || screen instanceof KeybindsScreen || editingText(screen)) return false;
        client.setScreen(new MusicScreen(screen));
        return true;
    }
    private static boolean editingText(Element element) {
        if (element instanceof TextFieldWidget || element instanceof EditBoxWidget) return true;
        return element instanceof ParentElement parent && parent.getFocused() != null && editingText(parent.getFocused());
    }
}
