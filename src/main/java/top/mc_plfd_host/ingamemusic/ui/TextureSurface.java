package top.mc_plfd_host.ingamemusic.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

public final class TextureSurface implements AutoCloseable {
    private final Identifier id;
    private NativeImageBackedTexture texture;
    public TextureSurface(String name) { id = Identifier.of("ingamemusic", "dynamic/" + name); }
    public void update(int width, int height, int[] rgba) {
        if (texture == null || texture.getImage().getWidth() != width || texture.getImage().getHeight() != height) {
            close(); texture = new NativeImageBackedTexture(() -> id.toString(), new NativeImage(width, height, false));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        }
        MemoryUtil.memIntBuffer(texture.getImage().imageId(), rgba.length).put(rgba);
        texture.upload();
    }
    public void drawRegion(DrawContext context, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int color) {
        if (texture == null) return;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, width, height,
                regionWidth, regionHeight, texture.getImage().getWidth(), texture.getImage().getHeight(), color);
    }
    public void draw(DrawContext context, int x, int y, int width, int height) {
        if (texture == null) return;
        int tw = texture.getImage().getWidth(), th = texture.getImage().getHeight();
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, width, height, tw, th, tw, th);
    }
    @Override public void close() {
        if (texture != null) { MinecraftClient.getInstance().getTextureManager().destroyTexture(id); texture = null; }
    }
}
