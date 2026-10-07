package org.lwjgl.opengl;

/** 仅用于离线自检的存根，不是真的 LWJGL。 */
public class GL11 {
    public static void glFinish() {
    }

    public static int glGetError() {
        return 0;
    }
}
