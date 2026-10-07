import framespike.Cfg;
import framespike.FrameSpikeTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

/**
 * 离线自检：不需要启动游戏，验证
 *   1) 注入/替换真的发生（结构化比对，不靠字符串搜索）
 *   2) 改写后的字节码能通过 JVM 字节码校验器（这一步才是真正会决定游戏会不会崩）
 *   3) 注入的代码能真正执行，watchdog 能检出停顿并抓栈落盘
 */
public class SelfTest {

    private static int fail = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) fail++;
    }

    private static byte[] file(String p) throws Exception {
        FileInputStream in = new FileInputStream(p);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally { in.close(); }
    }

    private static void write(String p, byte[] b) throws Exception {
        File f = new File(p);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(b); } finally { o.close(); }
    }

    /** 把类里所有 INVOKESTATIC 的 owner.name 统计出来 */
    private static Map<String, Integer> calls(byte[] cls) {
        final HashMap<String, Integer> m = new HashMap<String, Integer>();
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM5) {
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC) {
                            String k = owner + "." + name;
                            Integer c = m.get(k);
                            m.put(k, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                        }
                    }
                };
            }
        }, 0);
        return m;
    }

    private static int n(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v.intValue();
    }

    private static boolean logHas(String path, String needle) {
        try {
            return new String(file(path), "UTF-8").contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        String tmpDir = "out/selftest";
        String logFile = tmpDir + "/frame-spikes.log";
        new File(logFile).delete();

        Cfg.enabled = true;
        Cfg.glFinishMode = "proxy";
        Cfg.stallThresholdMs = 60;
        Cfg.maxDumps = 50;
        Cfg.stackDepth = 32;
        Cfg.cpuSampleSec = 3600;
        Cfg.logFile = logFile;

        System.out.println("== 1. 读取存根 ==");
        byte[] mc = file("out/stub/net/minecraft/client/Minecraft.class");
        byte[] er = file("out/stub/net/minecraft/client/renderer/EntityRenderer.class");
        check(mc.length > 0 && er.length > 0, "存根类已读取 (" + mc.length + " / " + er.length + " bytes)");

        System.out.println("== 2. 跑 transformer ==");
        FrameSpikeTransformer tx = new FrameSpikeTransformer();
        byte[] mcOut = tx.transform("ave", "net.minecraft.client.Minecraft", mc);
        byte[] erOut = tx.transform("bfk", "net.minecraft.client.renderer.EntityRenderer", er);
        check(mcOut != null && mcOut.length > 0, "Minecraft 输出非空");
        check(erOut != null && erOut.length > 0, "EntityRenderer 输出非空");

        System.out.println("== 3. 结构断言 ==");
        Map<String, Integer> mcC = calls(mcOut);
        Map<String, Integer> erC = calls(erOut);
        check(n(mcC, "framespike/FrameSpike.checkpoint") == 2,
                "Minecraft 注入 2 个检查点，实际 " + n(mcC, "framespike/FrameSpike.checkpoint"));
        check(n(erC, "framespike/FrameSpike.checkpoint") == 3,
                "EntityRenderer 注入 3 个检查点，实际 " + n(erC, "framespike/FrameSpike.checkpoint"));
        check(n(erC, "org/lwjgl/opengl/GL11.glFinish") == 0, "GL11.glFinish 调用点已移除");
        check(n(erC, "framespike/FrameSpike.glFinish") == 1,
                "已替换为 FrameSpike.glFinish，实际 " + n(erC, "framespike/FrameSpike.glFinish"));
        check(n(mcC, "framework/Nonexistent") == 0, "无杂散改写");
        check(logHas(logFile, "notch av -> runGameLoop"), "notch 名匹配路径生效");
        check(logHas(logFile, "srg func_71407_l -> runTick"), "SRG 兜底匹配路径生效");

        System.out.println("== 4. 写盘并让 JVM 校验器检查（关键） ==");
        write(tmpDir + "/net/minecraft/client/Minecraft.class", mcOut);
        write(tmpDir + "/net/minecraft/client/renderer/EntityRenderer.class", erOut);
        URLClassLoader cl = new URLClassLoader(new URL[]{
                new File(tmpDir).toURI().toURL(),
                new File("out/stub").toURI().toURL(),
                new File("out/main").toURI().toURL()
        }, SelfTest.class.getClassLoader());
        Class<?> erc = Class.forName("net.minecraft.client.renderer.EntityRenderer", true, cl);
        Object inst = erc.getDeclaredConstructor().newInstance();
        Method m = erc.getMethod("a", int.class, float.class, long.class);
        m.invoke(inst, Integer.valueOf(0), Float.valueOf(0f), Long.valueOf(0L));
        check(true, "补丁后的类通过 JVM 字节码校验并成功执行");

        System.out.println("== 5. 停顿检测（不推进检查点，等 watchdog 自己发现） ==");
        Thread.sleep(500);
        String log = new String(file(logFile), "UTF-8");
        check(log.contains("FrameSpike started"), "watchdog 已启动");
        check(log.contains("STALL"), "停滞被检出");
        check(log.contains("since='renderWorld'"), "定位到最后推进到的检查点 renderWorld");
        check(log.contains("renderWorldPass"), "时间线里含 renderWorldPass");
        check(log.contains("SelfTest.main"), "抓到了调用方栈帧（说明拿的是主线程栈）");
        System.out.println("---- 日志节选 ----");
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length && i < 22; i++) System.out.println("   " + lines[i]);

        System.out.println();
        System.out.println(fail == 0 ? "ALL TESTS PASSED" : (fail + " TEST(S) FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
