/*
 * FrameSpike - Minecraft 帧时间尖峰与卡顿分析 coremod
 * Author: Trusler    版本与署名见 FrameSpike.MOD_VERSION / MOD_AUTHOR
 *
 * 多版本适配：注入点写成「名字候选表」，运行时按实际出现的名字命中 ——
 *   1.8.9  运行时是 notch 混淆名（av / s / a / b）
 *   1.12.2 Forge 会把 notch remap 成 SRG 名（func_71411_J ...）
 *   1.16.5 Fabric/Mojang 官方名（run / runTick / GameRenderer.render ...）
 * 描述符也按版本给候选；空串表示「只看名字，不限签名」。
 */
package framespike;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * 两类改写：
 *  1) 在若干「每帧必经」的方法入口注入 FrameSpike.checkpoint(String)
 *  2) 把部分类里每帧一次的 GL11.glFinish() 换成 FrameSpike.glFinish()
 *     （仅在 glFinishMode=proxy 时；off 时完全不碰字节码）
 *
 * 所有目标名都是**运行时名**。多版本靠候选表：一个位置给出各版本可能的名字，
 * 命中哪个就用哪个，并把命中的名字类型（notch / srg / named）上报到 /fs version。
 */
public class FrameSpikeTransformer implements IClassTransformer {

    /**
     * {类名(容器类，各版本一致), 方法名候选(逗号分隔), 描述符候选(逗号分隔，"" = 不限), 标签}
     *
     * 为什么一堆候选：同一个逻辑位置在不同环境的运行时名字完全不同 ——
     *   1.8.9 Lunar/Forge：notch 名（av、s、a、b）
     *   1.12.2 Forge：SRG 名（func_71411_J ...）
     *   1.16.5 Fabric（官方映射）：run / runTick / GameRenderer.render / renderLevel
     * 名字猜错不会报错，只会静默不命中，所以宁可多留候选，命中项都写日志。
     */
    private static final String[][] HOOKS = {
            /* ---- 1.8.9 / 1.12.2：客户端主循环与渲染 ---- */
            {"net.minecraft.client.Minecraft", "func_71411_J,av,run", "()V", "runGameLoop"},
            {"net.minecraft.client.Minecraft", "func_71407_l,t,s,runTick", "()V", "runTick"},
            /* t = 1.12.2 的 runTick 混淆名（mcp_config-1.12.2 tsrg: t ()V func_71407_l）。
               放在 s 前面：1.12.2 命中 t；1.8.9 的 ave 没有 t（已 javap 核实），落到 s。 */
            {"net.minecraft.client.renderer.EntityRenderer", "func_181560_a,a,updateCameraAndRender", "(FJ)V", "updateCameraAndRender"},
            {"net.minecraft.client.renderer.EntityRenderer", "func_78471_a,b,renderWorld", "(FJ)V", "renderWorld"},
            {"net.minecraft.client.renderer.EntityRenderer", "func_175068_a,a,renderWorldPass", "(IFJ)V", "renderWorldPass"},
            {"net.minecraftforge.client.GuiIngameForge", "func_175180_a,a,render", "(F)V", "hudForge"},
            {"net.minecraft.client.gui.GuiIngame", "func_175180_a,a,render", "(F)V", "hudVanilla"},
            /* ---- 1.13+ / 1.16.5（官方名）：渲染搬到 GameRenderer ---- */
            {"net.minecraft.client.renderer.GameRenderer", "render", "(FJZ)V", "grender"},
            {"net.minecraft.client.renderer.GameRenderer", "renderLevel", "(FJLcom/mojang/blaze3d/matrix/MatrixStack;)V", "renderLevel"},
            {"net.minecraft.client.renderer.GameRenderer", "renderHand", "(FLcom/mojang/blaze3d/matrix/MatrixStack;)V", "renderHand"},
    };

    private static final String GL = "org/lwjgl/opengl/GL11";
    private static final String GLFIN_NAME = "glFinish";
    private static final String GLFIN_DESC = "()V";

    /** glFinish 代理要改写的类（各版本都可能不同，多写几个不存在的名字没关系） */
    private static final String[] GLFIN_REMOVE = {
            "net.minecraft.client.renderer.EntityRenderer",
            "net.minecraft.client.renderer.GameRenderer",
    };
    /** 只统计不替换的类（看调用点分布） */
    private static final String[] GLFIN_REPORT = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.renderer.RenderGlobal",
            "net.minecraft.client.renderer.LevelRenderer",
    };

    private static boolean announced = false;

    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (!Cfg.enabled) return basicClass;

            String tn = transformedName != null ? transformedName : name;
            if (tn == null) return basicClass;
            String internal = tn.replace('.', '/');

            List<String[]> hooks = new ArrayList<String[]>();
            for (int i = 0; i < HOOKS.length; i++) {
                if (HOOKS[i][0].replace('.', '/').equals(internal)) hooks.add(HOOKS[i]);
            }
            boolean doRemove = Cfg.proxyGlFinish() && contains(GLFIN_REMOVE, internal);
            boolean doReport = contains(GLFIN_REPORT, internal);

            if (hooks.isEmpty() && !doRemove && !doReport) return basicClass;
            if (!announced) {
                announced = true;
                FrameSpike.note("=== FrameSpike transformer active === config: " + Cfg.summary());
            }

            ClassReader cr = new ClassReader(basicClass);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            Stats st = new Stats();
            cr.accept(new CV(cw, internal, hooks, doRemove, doReport, st), 0);

            String line = "patch " + internal + ": hooks=" + st.injected + "/" + hooks.size()
                    + " glFinishFound=" + st.found + " glFinishDropped=" + st.dropped;
            if ((!hooks.isEmpty() && st.injected == 0) || (doRemove && st.found == 0)) {
                line = line + "   <<< WARNING: expected target not found (方法名/描述符可能变了)";
            }
            FrameSpike.note(line);
            if (!st.matched.isEmpty()) {
                FrameSpike.note("        命中: " + st.matched);
                /* 把命中的名字类型攒起来，/fs version 里能看到"这份环境是 notch 还是 SRG 还是官方名" */
                StringBuilder kinds = new StringBuilder();
                for (int i = 0; i < st.kinds.size(); i++) {
                    if (kinds.length() > 0) kinds.append('+');
                    kinds.append(st.kinds.get(i));
                }
                FrameSpike.reportEnv(st.envLabel(), kinds.toString());
            }
            if (!hooks.isEmpty() && st.injected == 0) {
                // 一次启动就能查出真实方法名，不用再猜一轮
                StringBuilder sb = new StringBuilder("        该类实际方法名(最多 60 个):");
                for (int i = 0; i < st.seen.size(); i++) {
                    sb.append("\n          ").append(st.seen.get(i));
                }
                FrameSpike.note(sb.toString());
            }
            // 无论 off/proxy 都上报看到的 glFinish 调用点数（取各轮次最大值），
            // 这样 /fs status 在 off 模式下也能看到真实情况
            FrameSpike.reportGlFinishSites(st.found);
            if (doRemove && st.found == 0) {
                FrameSpike.note("        [glfinish] 在 " + internal
                        + " 里这一轮没找到 GL11.glFinish 调用点 -> 看到的是补丁前的字节码。"
                        + "若所有轮次都是 0，proxy 就无法接管。");
            }
            return cw.toByteArray();
        } catch (Throwable t) {
            FrameSpike.note("transform FAILED for " + name + " (" + transformedName + "): " + t
                    + " -> 该类保持原样");
            return basicClass;
        }
    }

    private static boolean contains(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) if (arr[i].replace('.', '/').equals(s)) return true;
        return false;
    }

    /** 按名字形态判断这是哪套命名：notch(短名) / SRG(func_12345_) / 官方名 */
    static String kindOf(String methodName) {
        if (methodName == null) return "?";
        if (methodName.matches("func_\\d{5}_.*")) return "srg";
        if (methodName.length() <= 3 && methodName.matches("[a-z][a-zA-Z0-9_]*")) return "notch";
        return "named";
    }

    private static class Stats {
        int injected;
        int found;
        int dropped;
        final List<String> matched = new ArrayList<String>();
        final List<String> seen = new ArrayList<String>();
        final List<String> kinds = new ArrayList<String>();
        boolean notch = false, srg = false, named = false;

        void hit(String kind, String methodName, String label) {
            matched.add(kind + " " + methodName + " -> " + label);
            if (!kinds.contains(kind)) kinds.add(kind);
            if ("notch".equals(kind)) notch = true;
            else if ("srg".equals(kind)) srg = true;
            else named = true;
        }

        /** 环境一句话：1.8.9 是 notch，1.12.2 Forge 是 SRG，1.13+ 官方名 */
        String envLabel() {
            if (notch && !srg) return "1.8.9 型（notch 混淆名）";
            if (srg && !notch) return "1.12.2 型（Forge SRG 名）";
            if (named && !notch && !srg) return "1.13+ 型（官方映射名）";
            return "混合（多套名字都命中）";
        }
    }

    private static class CV extends ClassVisitor {
        private final String internal;
        private final List<String[]> hooks;
        private final boolean remove;
        private final boolean report;
        private final Stats st;

        CV(ClassVisitor cv, String internal, List<String[]> hooks, boolean remove, boolean report, Stats st) {
            super(Opcodes.ASM5, cv);
            this.internal = internal;
            this.hooks = hooks;
            this.remove = remove;
            this.report = report;
            this.st = st;
        }

        public MethodVisitor visitMethod(int access, String mname, String mdesc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, mname, mdesc, sig, ex);
            if (mv == null) return null;
            if (!hooks.isEmpty() && st.seen.size() < 60) {
                st.seen.add(mname + mdesc);
            }
            String label = null;
            for (int i = 0; i < hooks.size(); i++) {
                String[] h = hooks.get(i);
                String descs = h[2];
                if (descs.length() > 0 && !containsToken(descs, mdesc)) continue;
                String[] names = h[1].split(",");
                for (int j = 0; j < names.length; j++) {
                    if (!names[j].equals(mname)) continue;
                    label = h[3];
                    st.hit(kindOf(mname), mname, label);
                    break;
                }
                if (label != null) break;
            }
            if (label == null && !remove && !report) return mv;
            return new MV(mv, label, remove, report, st);
        }

        private static boolean containsToken(String csv, String v) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) if (parts[i].trim().equals(v)) return true;
            return false;
        }
    }

    private static class MV extends MethodVisitor {
        private final String label;
        private final boolean remove;
        private final boolean report;
        private final Stats st;

        MV(MethodVisitor mv, String label, boolean remove, boolean report, Stats st) {
            super(Opcodes.ASM5, mv);
            this.label = label;
            this.remove = remove;
            this.report = report;
            this.st = st;
        }

        public void visitCode() {
            super.visitCode();
            if (label != null) {
                super.visitLdcInsn(label);
                // 栈上多了 1，ClassWriter(COMPUTE_MAXS) 会自动修 maxStack
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "framespike/FrameSpike",
                        "checkpoint", "(Ljava/lang/String;)V", false);
                st.injected++;
            }
        }

        public void visitMethodInsn(int opcode, String owner, String iname, String idesc, boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC && GL.equals(owner)
                    && GLFIN_NAME.equals(iname) && GLFIN_DESC.equals(idesc)) {
                st.found++;
                if (remove) {
                    st.dropped++;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "framespike/FrameSpike",
                            "glFinish", "()V", false);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, iname, idesc, itf);
        }
    }
}
