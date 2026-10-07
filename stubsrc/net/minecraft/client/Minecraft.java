package net.minecraft.client;

/**
 * 离线自检存根：只有方法名和描述符有意义。
 *
 * 刻意混用两种命名，用来同时验证匹配的两条路径：
 *   av()            = notch 形态（运行时真实命中的就是这种）
 *   func_71407_l()  = SRG 形态（兜底路径）
 */
public class Minecraft {

    public void av() {
        func_71407_l();
    }

    public void func_71407_l() {
    }

    /** 不在 hook 表里，必须保持原样（用来验证没有乱注入）。 */
    public void func_99999_d() {
    }
}
