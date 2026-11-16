package top.mc_plfd_host.ingamemusic.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.mc_plfd_host.ingamemusic.ui.MenuShortcut;

@Mixin(Keyboard.class)
abstract class KeyboardMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void ingamemusic$openFromMenus(long window, int action, KeyInput input, CallbackInfo callback) {
        if (window == client.getWindow().getHandle() && MenuShortcut.handle(client, action, input)) callback.cancel();
    }
}
