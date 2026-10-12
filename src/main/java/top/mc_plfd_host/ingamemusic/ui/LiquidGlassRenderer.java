package top.mc_plfd_host.ingamemusic.ui;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryStack;
import top.mc_plfd_host.ingamemusic.InGameMusic;
import java.nio.ByteBuffer;
import java.util.*;
import static top.mc_plfd_host.ingamemusic.i18n.Messages.tr;

/** GPU-only separable blur and rounded refractive surfaces. No framebuffer readback. */
final class LiquidGlassRenderer implements AutoCloseable {
    private static final RenderPipeline BLUR = pipeline("glass_blur");
    private static final RenderPipeline SURFACE = pipeline("glass_surface");
    private static RenderPipeline pipeline(String fragment) {
        return RenderPipeline.builder().withLocation(Identifier.of("ingamemusic", "pipeline/" + fragment))
                .withVertexShader(Identifier.of("ingamemusic", "core/glass_fullscreen"))
                .withFragmentShader(Identifier.of("ingamemusic", "core/" + fragment))
                .withSampler("Background").withUniform("GlassData", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
                .withCull(false).withoutBlend().build();
    }
    private static final class Target extends AbstractTexture {
        final int width, height;
        Target(String name, int width, int height) {
            this.width = width; this.height = height;
            var device = RenderSystem.getDevice();
            glTexture = device.createTexture(name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    TextureFormat.RGBA8, width, height, 1, 1);
            glTextureView = device.createTextureView(glTexture);
            sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }
    private record Pane(Identifier id, Target target, Canvas.LiquidGlass command, int x, int y, double scale) { }
    private final List<Pane> panes = new ArrayList<>();
    private Target horizontal, vertical;
    private GpuBuffer uniforms;
    private boolean pending, respectBlur, failed;
    private int screenWidth, screenHeight;
    private double blur;

    boolean prepare(List<Canvas.LiquidGlass> commands, double sx, double sy, double ox, double oy, boolean backgroundBlurExpected) {
        pending = false;
        if (commands.isEmpty()) { releaseTargets(); return false; }
        if (failed) return false;
        try {
            var device = RenderSystem.getDevice();
            if (!device.precompilePipeline(BLUR).isValid() || !device.precompilePipeline(SURFACE).isValid())
                throw new IllegalStateException("Liquid glass shader compilation failed");
            var framebuffer = MinecraftClient.getInstance().getFramebuffer();
            screenWidth = framebuffer.textureWidth; screenHeight = framebuffer.textureHeight;
            double scale = Math.min(sx, sy);
            blur = commands.getFirst().style().blur() * scale;
            // Native resolution avoids half-resolution phase changes when moving the blur slider.
            int bw = screenWidth, bh = screenHeight;
            if (horizontal == null || horizontal.width != bw || horizontal.height != bh) {
                if (horizontal != null) horizontal.close(); if (vertical != null) vertical.close();
                horizontal = new Target("InGameMusic blur horizontal", bw, bh);
                vertical = new Target("InGameMusic blur vertical", bw, bh);
            }
            if (uniforms == null) uniforms = device.createBuffer(() -> "InGameMusic glass uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
            var textures = MinecraftClient.getInstance().getTextureManager();
            for (int i = 0; i < commands.size(); i++) {
                var command = commands.get(i);
                int x = (int) Math.round(ox + command.x() * sx), y = (int) Math.round(oy + command.y() * sy);
                int w = Math.max(1, (int) Math.round(ox + (command.x() + command.w()) * sx) - x);
                int h = Math.max(1, (int) Math.round(oy + (command.y() + command.h()) * sy) - y);
                Identifier id = Identifier.of("ingamemusic", "dynamic/liquid_glass_" + i);
                Target target = i < panes.size() ? panes.get(i).target : null;
                if (target == null || target.width != w || target.height != h) {
                    if (target != null) textures.destroyTexture(id);
                    target = new Target("InGameMusic glass panel " + i, w, h);
                    textures.registerTexture(id, target);
                }
                var pane = new Pane(id, target, command, x, y, scale);
                if (i < panes.size()) panes.set(i, pane); else panes.add(pane);
            }
            while (panes.size() > commands.size()) textures.destroyTexture(panes.removeLast().id);
            respectBlur = commands.getFirst().style().respectBackgroundBlur();
            // Both choices sample the same frame, around the actual vanilla blur pass.
            // Sampling during GUI extraction can capture last frame's GUI in menus.
            if (backgroundBlurExpected) pending = true;
            else renderSurfaces();
            return !failed;
        } catch (RuntimeException failure) { fail(failure); return false; }
    }
    void beforeBackgroundBlur() {
        if (!respectBlur) renderPending();
    }
    void afterBackgroundBlur() {
        if (respectBlur) renderPending();
    }
    private void renderPending() {
        if (!pending) return;
        pending = false;
        try { renderSurfaces(); }
        catch (RuntimeException failure) {
            fail(failure);
            // The GUI already holds these textures. Keep this frame readable on failure.
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (var pane : panes) encoder.clearColorTexture(pane.target.getGlTexture(), pane.command.tint());
        }
    }
    private void renderSurfaces() {
        var background = MinecraftClient.getInstance().getFramebuffer().getColorAttachmentView();
        if (background == null) throw new IllegalStateException("Missing scene color attachment");
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(64);
            data.putFloat(0, 1f / screenWidth); data.putFloat(8, (float) blur);
            pass(BLUR, background, horizontal, data);
            data.putFloat(0, 0); data.putFloat(4, 1f / screenHeight);
            pass(BLUR, horizontal.getGlTextureView(), vertical, data);
            for (var pane : panes) {
                data.clear();
                data.putFloat(screenWidth).putFloat(screenHeight).putFloat(0).putFloat(0);
                data.putFloat(pane.x).putFloat(pane.y).putFloat(pane.target.width).putFloat(pane.target.height);
                data.putFloat((float) (pane.command.radius() * pane.scale)).putFloat((float) pane.command.style().refraction())
                        .putFloat((float) pane.scale).putFloat((float) pane.command.style().maskOpacity());
                data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
                data.flip(); pass(SURFACE, vertical.getGlTextureView(), pane.target, data);
            }
        }
    }
    private void pass(RenderPipeline pipeline, GpuTextureView input, Target output, ByteBuffer data) {
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(uniforms.slice(), data);
        try (var pass = encoder.createRenderPass(() -> "InGameMusic liquid glass", output.getGlTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline); pass.setUniform("GlassData", uniforms);
            pass.bindTexture("Background", input, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }
    void draw(int index, DrawContext context) {
        var pane = panes.get(index);
        // Render targets have bottom-left UV origin, whereas the GUI uses top-left.
        context.drawTexturedQuad(pane.id, pane.x, pane.y, pane.x + pane.target.width, pane.y + pane.target.height, 0, 1, 1, 0);
    }
    private void fail(RuntimeException failure) {
        pending = false; failed = true;
        InGameMusic.LOG.error("Liquid glass unavailable; using opaque panels", failure);
        var app = InGameMusic.controller();
        if (app != null) app.notice = tr("error.glass");
    }
    private void releaseTargets() {
        pending = false;
        var manager = MinecraftClient.getInstance().getTextureManager();
        for (var pane : panes) manager.destroyTexture(pane.id);
        panes.clear();
        if (horizontal != null) { horizontal.close(); horizontal = null; }
        if (vertical != null) { vertical.close(); vertical = null; }
        if (uniforms != null) { uniforms.close(); uniforms = null; }
    }
    @Override public void close() { releaseTargets(); failed = false; }
}
