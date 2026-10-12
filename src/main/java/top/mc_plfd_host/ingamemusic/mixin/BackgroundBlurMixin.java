package top.mc_plfd_host.ingamemusic.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.mc_plfd_host.ingamemusic.InGameMusic;

/** Bracket the GUI's blur boundary, even when another mod skips the blur itself. */
@Mixin(GuiRenderer.class)
abstract class BackgroundBlurMixin {
    @Inject(method = "renderPreparedDraws", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderBlur()V"))
    private void ingamemusic$beforeBackgroundBlur(GpuBufferSlice fog, CallbackInfo callback) {
        var renderer = InGameMusic.renderer();
        if (renderer != null) renderer.beforeBackgroundBlur();
    }
    @Inject(method = "renderPreparedDraws", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;renderBlur()V", shift = At.Shift.AFTER))
    private void ingamemusic$afterBackgroundBlur(GpuBufferSlice fog, CallbackInfo callback) {
        var renderer = InGameMusic.renderer();
        if (renderer != null) renderer.afterBackgroundBlur();
    }
}
