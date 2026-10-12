package top.mc_plfd_host.ingamemusic;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the deferred glass hook against the actual mapped Minecraft renderer. */
class GlassRenderHookTest {
    @Test void blurBoundaryExistsBetweenTheTwoGuiDrawPasses() throws Exception {
        try (var input = getClass().getResourceAsStream("/net/minecraft/client/gui/render/GuiRenderer.class")) {
            assertNotNull(input);
            var renderer = new ClassNode();
            new ClassReader(input).accept(renderer, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var method = renderer.methods.stream().filter(m -> m.name.equals("renderPreparedDraws")).findFirst().orElseThrow();
            assertEquals("(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", method.desc);
            int before = 0, after = 0, blurCalls = 0;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("net/minecraft/client/render/GameRenderer") && call.name.equals("renderBlur")) {
                    assertEquals("()V", call.desc); blurCalls++;
                }
                if (call.owner.equals(renderer.name) && call.name.equals("render")) {
                    if (blurCalls == 0) before++; else after++;
                }
            }
            assertEquals(1, blurCalls, "Both injection points must address one actual GUI blur boundary");
            assertEquals(1, before);
            assertEquals(1, after, "Glass must finish before the GUI pass samples its textures");
        }
    }
}
