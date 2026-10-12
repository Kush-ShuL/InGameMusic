package top.mc_plfd_host.ingamemusic;

import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.mc_plfd_host.ingamemusic.ui.*;
import static org.lwjgl.glfw.GLFW.*;

public final class InGameMusic implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("InGameMusic");
    private static MusicController controller;
    private static CoverCache covers;
    private static MusicHud hud;
    private static CachedGuiRenderer renderer;
    public static KeyBinding openKey, pauseKey, nextKey, hudKey, interactKey;
    public static MusicController controller() { return controller; }
    public static CoverCache covers() { return covers; }
    public static CachedGuiRenderer renderer() { return renderer; }
    public static MusicHud hud() { return hud; }
    @Override public void onInitializeClient() {
        top.mc_plfd_host.ingamemusic.i18n.Messages.install(net.minecraft.client.resource.language.I18n::translate);
        try { controller = new MusicController(FabricLoader.getInstance().getConfigDir().resolve("ingamemusic")); }
        catch (Exception e) { throw new IllegalStateException(tr("error.initialize"), e); }
        top.mc_plfd_host.ingamemusic.compat.VoiceRedirect.active = controller.voiceRedirect;
        covers = new CoverCache(FabricLoader.getInstance().getConfigDir().resolve("ingamemusic/covers/generated")); renderer = new CachedGuiRenderer(); hud = new MusicHud(controller, covers);
        var category = KeyBinding.Category.create(Identifier.of("ingamemusic", "music"));
        openKey = bind("open", GLFW_KEY_M, category); pauseKey = bind("pause", GLFW_KEY_P, category);
        nextKey = bind("next", GLFW_KEY_RIGHT_BRACKET, category); hudKey = bind("hud", GLFW_KEY_H, category);
        interactKey = bind("interact", GLFW_KEY_RIGHT_ALT, category);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controller.tick();
            while (openKey.wasPressed()) {
                Screen current = client.currentScreen;
                if (current instanceof MusicScreen screen) screen.close();
                else if (current == null) client.setScreen(new MusicScreen(null));
            }
            while (pauseKey.wasPressed()) if (client.currentScreen == null) controller.toggle();
            while (nextKey.wasPressed()) if (client.currentScreen == null) controller.next(false, false);
            while (hudKey.wasPressed()) if (client.currentScreen == null) controller.edit(tr("action.hud"), s -> s.hud = !s.hud);
            while (interactKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new HudScreen(null));
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("ingamemusic", "player"), (context, tickCounter) -> hud.render(context, false, hud.x(), hud.y()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { controller.close(); hud.close(); covers.close(); renderer.close(); });
        LOG.info("InGame Music ready: client-only, pure Java audio decoders");
    }
    private static KeyBinding bind(String action, int key, KeyBinding.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.ingamemusic." + action, InputUtil.Type.KEYSYM, key, category));
    }
}
