package net.minecraft.client.renderer;

import org.lwjgl.opengl.GL11;

/**
 * 离线自检存根：方法名用 **notch 形态**（运行时真实命中的就是这种）。
 *   a(FJ)V   = updateCameraAndRender
 *   a(IFJ)V  = renderWorldPass  ← 里面那个 glFinish 就是要被替换掉的调用点
 *   b(FJ)V   = renderWorld
 */
public class EntityRenderer {

    public void a(float partialTicks, long nanoTime) {
        a(0, partialTicks, nanoTime);
    }

    public void a(int pass, float partialTicks, long nanoTime) {
        GL11.glFinish();
        b(partialTicks, nanoTime);
    }

    public void b(float partialTicks, long nanoTime) {
    }
}
