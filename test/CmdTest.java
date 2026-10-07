import framespike.Cfg;
import framespike.Cmd;
import framespike.FrameSpike;
import framespike.FrameSpikeTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端指令系统的离线自检：不开游戏，用假类（stubsrc 下）
 * 把「建 Proxy -> 注册 -> 形态分派 -> 派发 -> 聊天回显」整条链跑通，
 * 另外验证 glFinish 两模式与 ctrl 文件降级通道。
 */
public class CmdTest {

    private static int fail = 0;
    private static final List<String> sent = new ArrayList<String>();

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

    private static void writeFile(String p, String txt) throws Exception {
        File f = new File(p);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(txt.getBytes("UTF-8")); } finally { o.close(); }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append('\n');
        return sb.toString();
    }

    private static Object makeSender(final List<String> sink) {
        try {
            Class<?> iface = Class.forName("net.minecraft.command.ICommandSender");
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        public Object invoke(Object p, Method m, Object[] a) {
                            if (m.getParameterTypes().length == 1 && m.getReturnType() == void.class) {
                                sink.add(String.valueOf(a[0]));
                                return null;
                            }
                            if (m.getReturnType() == String.class) return "tester";
                            if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                            if (m.getReturnType() == int.class) return Integer.valueOf(0);
                            return null;
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按「参数个数 + 返回类型」的形态调用代理上的方法，验证形态分派 */
    private static Object invokeShaped(Object proxy, int paramCount, Class<?> returnType) throws Exception {
        Class<?> iface = proxy.getClass().getInterfaces()[0];
        for (Method m : iface.getMethods()) {
            if (m.getParameterTypes().length == paramCount && m.getReturnType().equals(returnType)) {
                Object[] args = new Object[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    Class<?> pt = m.getParameterTypes()[i];
                    args[i] = pt.isPrimitive() ? Integer.valueOf(0) : (pt.equals(String[].class) ? new String[0] : null);
                }
                return m.invoke(proxy, args);
            }
        }
        return null;
    }

    private static void invokeProcessCommand(Object proxy, Object sender, String[] argv) throws Exception {
        Class<?> iface = proxy.getClass().getInterfaces()[0];
        for (Method m : iface.getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[1].equals(String[].class) && m.getReturnType() == void.class) {
                m.invoke(proxy, sender, argv);
                return;
            }
        }
        throw new IllegalStateException("没找到 processCommand 形态的方法");
    }

    private static Object invokeTabComplete(Object proxy, Object sender, String[] parts) throws Exception {
        Class<?> iface = proxy.getClass().getInterfaces()[0];
        for (Method m : iface.getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 3 && pt[1].equals(String[].class) && List.class.isAssignableFrom(m.getReturnType())) {
                return m.invoke(proxy, sender, parts, null);
            }
        }
        throw new IllegalStateException("没找到 addTabCompletionOptions 形态的方法");
    }

    private static int calls(byte[] cls, final String target) {
        final int[] n = new int[1];
        new ClassReader(cls).accept(new ClassVisitor(Opcodes.ASM5) {
            public MethodVisitor visitMethod(int a, String mn, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (op == Opcodes.INVOKESTATIC && (owner + "." + name).equals(target)) n[0]++;
                    }
                };
            }
        }, 0);
        return n[0];
    }

    private static long glSkipped() throws Exception {
        Field f = FrameSpike.class.getDeclaredField("glSkipped");
        f.setAccessible(true);
        return ((AtomicLong) f.get(null)).get();
    }

    private static void callGlFinish() throws Exception {
        Method m = FrameSpike.class.getMethod("glFinish");
        m.invoke(null);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File("out/cmdtest");
        dir.mkdirs();
        String log = new File(dir, "frame-spikes.log").getAbsolutePath();
        String ctrl = new File(dir, "frame-spikes.ctrl").getAbsolutePath();
        new File(log).delete();
        new File(ctrl).delete();

        Cfg.enabled = true;
        Cfg.stallThresholdMs = 60;
        Cfg.maxDumps = 20;
        Cfg.stackDepth = 16;
        Cfg.cpuSampleSec = 3600;
        Cfg.cpuTopN = 3;
        Cfg.logFile = log;
        Cfg.ctrlFile = ctrl;
        Cfg.glFinishMode = "off";
        Cfg.cmdName = "fs";
        Cfg.cmdAliases = "framespike,fspike";
        Cmd.resetForTest();

        ClassLoader cl = CmdTest.class.getClassLoader();
        Class<?> cch = Class.forName("net.minecraftforge.client.ClientCommandHandler", false, cl);

        System.out.println("== 1. 反射注册 ==");
        check(Cmd.tryRegister(cl) && Cmd.registered(), "客户端命令注册成功");
        check("fs".equals(Cmd.activeName), "生效命令名 = fs（实际 " + Cmd.activeName + "）");
        check(Cmd.state().contains("func_71560_a"), "命中的注册方法为 func_71560_a: " + Cmd.state());

        Object inst = cch.getField("instance").get(null);
        Map<?, ?> map = (Map<?, ?>) cch.getMethod("func_71555_a").invoke(inst);
        Object cmd = map.get("fs");
        check(cmd != null, "命令已进入 handler 命令表");

        System.out.println("== 2. 形态分派 ==");
        check("fs".equals(invokeShaped(cmd, 0, String.class)), "()String -> 命令名");
        Object aliases = invokeShaped(cmd, 0, List.class);
        check(aliases instanceof List && ((List<?>) aliases).contains("framespike"), "()List -> 别名 " + aliases);
        check(Boolean.TRUE.equals(invokeShaped(cmd, 1, boolean.class)), "单参 boolean -> true");
        check(Integer.valueOf(0).equals(invokeShaped(cmd, 1, int.class)), "compareTo 形态 -> 0");
        check(Boolean.TRUE.equals(cmd.equals(cmd)) && Boolean.FALSE.equals(cmd.equals("x")), "equals 已显式处理");
        check(cmd.hashCode() != 0, "hashCode 已显式处理");

        System.out.println("== 2b. Tab 补全（走 addTabCompletionOptions 形态） ==");
        List<?> tcAll = (List<?>) invokeTabComplete(cmd, null, new String[]{"fs"});
        check(tcAll != null && tcAll.size() == 17 && tcAll.contains("start") && tcAll.contains("glfinish")
                        && tcAll.contains("chat") && tcAll.contains("config") && tcAll.contains("reload"),
                "/fs <TAB> -> 全部 17 个子命令（实际 " + tcAll + "）");
        List<?> tcSt = (List<?>) invokeTabComplete(cmd, null, new String[]{"st"});
        check(tcSt != null && tcSt.size() == 3 && tcSt.contains("start") && tcSt.contains("stop")
                        && tcSt.contains("status"),
                "/fs st<TAB> -> start/stop/status（实际 " + tcSt + "）");
        List<?> tcRaw = (List<?>) invokeTabComplete(cmd, null, new String[]{"fs", "st"});
        check(tcRaw != null && tcRaw.size() == 3,
                "带命令名的原始形态也能剥掉并补全（实际 " + tcRaw + "）");
        List<?> tcGf = (List<?>) invokeTabComplete(cmd, null, new String[]{"glfinish", "o"});
        check(tcGf != null && tcGf.size() == 2 && tcGf.contains("on") && tcGf.contains("off"),
                "/fs glfinish o<TAB> -> on/off（实际 " + tcGf + "）");
        List<?> tcNone = (List<?>) invokeTabComplete(cmd, null, new String[]{"zzz"});
        check(tcNone != null && tcNone.isEmpty(), "无匹配时返回空列表");
        List<?> tcEmpty = (List<?>) invokeTabComplete(cmd, null, new String[]{""});
        check(tcEmpty != null && tcEmpty.size() == 17, "/fs <空格><TAB> 也给全部子命令");

        System.out.println("== 3. 派发 + 聊天回显 ==");
        Object sender = makeSender(sent);
        check(sender != null, "假 sender 构造成功");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"status"});
        check(sent.size() >= 4, "status 回显 " + sent.size() + " 行");
        check(join(sent).contains("threshold"), "回显含 threshold");
        check(join(sent).contains("log="), "回显含日志路径");
        check(join(sent).contains("glFinishMode"), "回显含 glFinishMode");
        check(join(sent).contains("[FrameSpike]"), "回显带前缀（说明真的走了聊天通道）");

        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"start"});
        check(join(sent).contains("已开始记录"), "start 可用");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"mark", "对局开始"});
        check(join(sent).contains("对局开始"), "mark 可用");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"threshold", "150"});
        check(Cfg.stallThresholdMs == 150, "threshold 热改生效");
        invokeProcessCommand(cmd, sender, new String[]{"threshold", "99999"});
        check(Cfg.stallThresholdMs == 5000, "threshold 上限被 clamp 到 5000");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"dump"});
        check(join(sent).contains("已抓取"), "dump 可用");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"stop"});
        check(join(sent).contains("分档"), "stop 输出分档统计");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"glfinish", "x"});
        check(join(sent).contains("glFinishMode=off"), "未开 proxy 时给出明确提示");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"nosuchsub"});
        check(join(sent).contains("未知子命令") || join(sent).contains("没有这个子命令"),
                "未知子命令有回显（" + join(sent).replace("\n", " / ").substring(0, 60) + "）");

        System.out.println("== 4. 重名预检自动换名 ==");
        Cmd.resetForTest();
        Cfg.cmdName = "fsdemo";
        Cfg.cmdAliases = "fsdemoalias";
        cch.getMethod("preload", String.class).invoke(inst, "fsdemo");
        check(Cmd.tryRegister(cl) && "fsdemoalias".equals(Cmd.activeName),
                "名称被占用时自动改用别名（实际 " + Cmd.activeName + "）");

        System.out.println("== 5. glFinish 两模式（字节码层） ==");
        FrameSpikeTransformer tx = new FrameSpikeTransformer();
        byte[] erIn = file("out/stub/net/minecraft/client/renderer/EntityRenderer.class");
        Cfg.glFinishMode = "off";
        byte[] offOut = tx.transform("rt/Obf", "net.minecraft.client.renderer.EntityRenderer", erIn);
        check(calls(offOut, "org/lwjgl/opengl/GL11.glFinish") == 1, "off 模式保留 glFinish 调用点");
        check(calls(offOut, "framespike/FrameSpike.glFinish") == 0, "off 模式不注入代理");
        check(calls(offOut, "framespike/FrameSpike.checkpoint") == 3, "off 模式仍然注入 3 个检查点");
        Cfg.glFinishMode = "proxy";
        byte[] proxyOut = tx.transform("rt/Obf", "net.minecraft.client.renderer.EntityRenderer", erIn);
        check(calls(proxyOut, "org/lwjgl/opengl/GL11.glFinish") == 0, "proxy 模式移除 glFinish");
        check(calls(proxyOut, "framespike/FrameSpike.glFinish") == 1, "proxy 模式换成 FrameSpike.glFinish");
        check(FrameSpike.getGlFinishSites() == 1,
                "transformer 报告 glFinish 调用点 = 1（实际 " + FrameSpike.getGlFinishSites() + "）");

        System.out.println("== 6. glFinish 运行时热切 ==");
        FrameSpike.prepareGlFinish();
        check(Cfg.glFinishMode.equals("proxy"), "proxy 模式没被降级（stub GL11 可解析）");
        long s0 = glSkipped();
        callGlFinish();
        callGlFinish();
        check(glSkipped() == s0, "passThrough=true 时走真实调用、不计数");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"glfinish", "on"});
        callGlFinish();
        check(glSkipped() == s0 + 1, "glfinish on 之后开始跳过并计数");
        check(join(sent).contains("已跳过"), "on 有回显");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"glfinish", "off"});
        callGlFinish();
        check(glSkipped() == s0 + 1, "glfinish off 之后恢复透传");
        check(join(sent).contains("恢复原生"), "off 有回显");

        System.out.println("== 7. ctrl 文件降级通道 ==");
        Cmd.gaveUp = true;
        FrameSpike.checkpoint("runTick");
        writeFile(ctrl, "# 测试\nthreshold 137\n");
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 3000 && Cfg.stallThresholdMs != 137) {
            Thread.sleep(100);
        }
        check(Cfg.stallThresholdMs == 137, "ctrl 文件里的 threshold 137 生效（实际 " + Cfg.stallThresholdMs + "）");
        String lg = new String(file(log), "UTF-8");
        check(lg.contains("[ctrl] 收到: threshold 137"), "日志记录了 ctrl 命令");
        check(lg.contains("FrameSpike started"), "watchdog 已启动");
        check(lg.contains("SNAPSHOT") || lg.contains("STALL"), "停顿/快照已落盘");

        System.out.println("== 8. 停顿时长结算（回归：曾经把 500ms 记成 60ms） ==");
        invokeProcessCommand(cmd, null, new String[]{"threshold", "60"});
        invokeProcessCommand(cmd, null, new String[]{"start"});
        Thread.sleep(150);
        FrameSpike.checkpoint("runTick");
        Thread.sleep(300);                 // 故意不推进检查点 -> watchdog 会在 ~60ms 处检测并抓栈
        Thread.sleep(250);                 // 让它先"检测"完
        FrameSpike.checkpoint("runTick");  // 下一个检查点 -> 在这里结算真实时长
        Thread.sleep(200);
        String lg2 = new String(file(log), "UTF-8");
        long total = -1;
        long detected = -1;
        for (String line : lg2.split("\n")) {
            java.util.regex.Matcher m1 = java.util.regex.Pattern
                    .compile("STALL-END\\s+total=(\\d+)ms\\s+detected@(\\d+)ms").matcher(line);
            if (m1.find()) {
                total = Long.parseLong(m1.group(1));
                detected = Long.parseLong(m1.group(2));
            }
        }
        check(total >= 450, "真实时长被结算为 " + total + "ms（应 >=450）");
        check(detected >= 60 && detected <= 250,
                "检测时刻是刚越过阈值（实际 " + detected + "ms）");
        check(total > detected, "结算时长(" + total + ") 明显大于检测时刻(" + detected + ")");

        System.out.println("== 9. 卡顿聊天提示 ==");
        // 第 8 节跑了两次停顿：第一条（~1s）应当推了聊天提示，
        // 紧接着的第二条（551ms）应当被 1 秒冷却挡住 —— 两条都要断言
        check(join(sent).contains("[FS]"), "停顿结算后推了聊天提示（sent 里 " + sent.size() + " 条）");
        check(!join(sent).contains("551ms"), "1 秒内的第二次停顿被冷却挡住");
        sent.clear();
        check("写文件".equals(framespike.FrameSpike.classify(stk("java.io.FileOutputStream", "writeBytes"))),
                "分类: 写文件");
        check("类加载/remap".equals(framespike.FrameSpike.classify(
                stk("com.moonsworth.lunar.genesis.ROOCCIHRCCHORHCCOIIRIHHHIIHHHO", "findClass"))), "分类: 类加载/remap");
        check("窗口合成".equals(framespike.FrameSpike.classify(
                stk("org.lwjgl.opengl.WindowsDisplay", "nUpdate"))), "分类: 窗口合成");
        check("GL驱动".equals(framespike.FrameSpike.classify(
                stk("org.lwjgl.opengl.GL11", "nglDrawArrays"))), "分类: GL驱动");
        check("音效".equals(framespike.FrameSpike.classify(
                stk("paulscode.sound.SoundSystem", "CommandQueue"))), "分类: 音效");
        check("字体缓存".equals(framespike.FrameSpike.classify(
                stk("net.minecraft.client.gui.FontRenderer", "tick"))), "分类: 字体缓存");
        check("小地图".equals(framespike.FrameSpike.classify(
                stk("xaero.common.settings.ModSettings", "loadAllWaypoints"))), "分类: 小地图");
        check("OptiFine".equals(framespike.FrameSpike.classify(
                stk("net.optifine.CustomSkyLayer", "renderSide"))), "分类: OptiFine");
        check(framespike.FrameSpike.classify(stk("com.example.Thing", "doIt")).startsWith("Thing.doIt"),
                "分类兜底: 栈顶短名");
        check("未知".equals(framespike.FrameSpike.classify(new StackTraceElement[0])), "分类: 空栈 -> 未知");
        check("读文件".equals(framespike.FrameSpike.classify(
                stk("java.io.WinNTFileSystem", "getBooleanAttributes"))), "分类: 读文件");
        check("Mixin 注入".equals(framespike.FrameSpike.classify(
                stk("com.moonsworth.lunar.genesis.LateApplyingInject", "wrap"))), "分类: Mixin 注入");
        check("区块渲染".equals(framespike.FrameSpike.classify(
                stk("net.minecraft.client.renderer.chunk.RenderChunk", "func_178578_b"))), "分类: 区块渲染");
        check("直接内存".equals(framespike.FrameSpike.classify(
                stk("jdk.internal.misc.Unsafe", "allocateMemory0"))), "分类: 直接内存");
        check("等待/限速".equals(framespike.FrameSpike.classify(
                stk("sun.misc.Unsafe", "park"))), "分类: 等待/限速");
        check("JDK 内部".equals(framespike.FrameSpike.classify(
                stk("java.lang.StringLatin1", "replace"))), "分类: JDK 内部");
        check("压缩/解压".equals(framespike.FrameSpike.classify(
                stk("com.github.luben.zstd.Zstd", "nZSTD_compress"))), "分类: 压缩/解压");
        check("图像".equals(framespike.FrameSpike.classify(
                stk("java.awt.image.BufferedImage", "getRGB"))), "分类: 图像");
        check("序列化/解析".equals(framespike.FrameSpike.classify(
                stk("com.google.protobuf.MapEntry", "newBuilderForType"))), "分类: 序列化/解析");
        check("Lunar 混淆类".equals(framespike.FrameSpike.classify(
                stk("com.moonsworth.lunar.ichor.util.IHCCRCCOHRCCIORHHCROORROCICRHI", "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH"))),
                "分类: 混淆类归到 Lunar（不再把乱码当类别名）");
        check("游戏自身".equals(framespike.FrameSpike.classify(
                stk("net.minecraft.client.entity.AbstractClientPlayer", "func_175149_v"))), "分类: 游戏自身");

        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"tail", "5"});
        String tl = join(sent);
        check(!tl.contains("\u8fd8\u6ca1\u6709 STALL") && tl.contains("ms"),
                "fs tail 回显真实停顿行（" + (tl.length() > 90 ? tl.substring(0, 90) : tl) + "）");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"chat", "on"});
        check(join(sent).contains("已开启"), "chat on 有回显");
        invokeProcessCommand(cmd, sender, new String[]{"chatmin", "150"});
        check(Cfg.chatMinMs == 150, "chatmin 150 生效");
        invokeProcessCommand(cmd, sender, new String[]{"chatmin", "1"});
        check(Cfg.chatMinMs == 50, "chatmin 下限 clamp 到 50");
        sent.clear();
        framespike.Cmd.chat("\u00a7e[FS]\u00a7r 312ms 写文件");
        check(join(sent).contains("312ms"), "Cmd.chat 走到了聊天通道（" + sent.size() + " 条）");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"chat", "off"});
        check(!Cfg.chatEnabled, "chat off 生效");
        invokeProcessCommand(cmd, sender, new String[]{"status"});
        check(join(sent).contains("chat="), "status 里能看到 chat 设置");

        System.out.println("== 10. 帮助体系 ==");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{});
        check(join(sent).contains("Minecraft") && join(sent).contains("Java") && join(sent).contains("help"), "裸 /fs -> 版本三行");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help"});
        String h = join(sent);
        check(h.contains("记录") && h.contains("取证") && h.contains("报告")
                        && h.contains("调参") && h.contains("其它"),
                "分组全表五个分组都在");
        check(h.contains("当前") && h.contains("ms"), "帮助带 threshold 当前值");
        check(h.contains("Trusler"), "帮助里有署名");
        check(h.contains("开始记录") && h.contains("生成 HTML 报告"), "帮助描述是一句话");
        check(h.contains("version"), "分组全表里有 version");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"version"});
        String vv = join(sent);
        check(vv.contains(framespike.FrameSpike.MOD_VERSION) && vv.contains("Trusler"), "fs version 报版本与作者");
        check(vv.contains("coremod") && vv.contains("\u65e5\u5fd7="), "fs version 带运行环境");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help", "version"});
        check(join(sent).contains("\u8fd0\u884c\u73af\u5883"), "help version 有单条详情");
        check(h.split("\n").length >= 15, "分组全表行数够（" + h.split("\n").length + " 行）");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help", "tail"});
        check(join(sent).contains("读日志尾部") && join(sent).contains("上限 20"), "help tail 给单条详情");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help", "glfinish"});
        check(join(sent).contains("glFinish"), "help glfinish 给单条详情");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help", "nosuch"});
        check(join(sent).contains("没有这个子命令"), "help 未知子命令有兜底");

        System.out.println("== 11. 报告与本地服务 ==");
        String fake = "=== FrameSpike v0.2.0  by Trusler ===\n"
                + "03:01:00.000  STALL-DETECT 61ms  since='hudForge'  frames=100  ticks=20  stall#1  reason=写文件  glFinishSkipped=0\n"
                + "  timeline:\n"
                + "    runTick              t+   0.412ms\n"
                + "    hudForge             t+  33.221ms\n"
                + "  stack of \"Client thread\":\n"
                + "    java.io.FileOutputStream.writeBytes(Native Method)\n"
                + "    com.moonsworth.lunar.client.AAA.BBB(Unknown Source)\n"
                + "  STALL-END    total=372ms  detected@61ms  since='hudForge'  next='runGameLoop'  reason=写文件\n"
                + "\n"
                + "03:02:00.000  STALL-DETECT 62ms  since='runGameLoop'  frames=200  ticks=40  stall#2  reason=类加载/remap  glFinishSkipped=0\n"
                + "  stack of \"Client thread\":\n"
                + "    java.lang.ClassLoader.defineClass1(Native Method)\n"
                + "    com.moonsworth.lunar.genesis.ZZZ.findClass(Unknown Source)\n"
                + "  STALL-END    total=1589ms  detected@62ms  since='runGameLoop'  next='runTick'  reason=类加载/remap\n"
                + "\n"
                + "12:00:00.000  [CPU] 60.1s window, 8 cores, machine busy=8.2%  fps\u2248112.0  tps\u224820.0  frames=4513  ticks=903\n";
        String json = framespike.Report.buildJson(fake);
        check(json.contains("\"stalls\":2"), "JSON: 解析出 2 条停顿");
        check(json.contains("\"totalMs\":1961"), "JSON: 累计 1961ms");
        check(json.contains("\"maxMs\":1589"), "JSON: 最长 1589ms");
        check(json.contains("\"buckets\":[0,0,1,1]"), "JSON: 分档 [0,0,1,1]");
        check(json.contains("\"fps\":112.0") && json.contains("\"tps\":20.0"), "JSON: CPU 行解析出 fps/tps");
        check(json.contains("FileOutputStream.writeBytes") && json.contains("defineClass1"),
                "JSON: 火焰图里两个栈顶都在");
        check(json.contains("\"since\":\"hudForge\""), "JSON: since 解析正确");
        check(json.contains("[\"runTick\",0.41]"), "JSON: 时间线解析正确");

        String html = framespike.Report.buildHtml(fake, "test");
        check(!html.contains("__DATA__") && !html.contains("__TITLE__") && !html.contains("__SOURCE__"),
                "HTML: 占位符全部被替换");
        check(html.contains("var DATA = {") && html.contains("FrameSpike"), "HTML: 注入了数据与签名");
        check(html.contains("Trusler") && html.contains(framespike.FrameSpike.MOD_VERSION),
                "HTML: 带上版本与作者（" + framespike.FrameSpike.MOD_VERSION + "）");

        // 本地服务：起、抓一次、关
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"serve", "18731"});
        check(join(sent).contains("http://127.0.0.1:18731/"), "serve 给出地址（" + join(sent).trim() + "）");
        String page = httpGet("http://127.0.0.1:18731/");
        check(page != null && page.contains("var DATA = {"), "本地服务真的能返回报告页");
        String logp = httpGet("http://127.0.0.1:18731/log");
        check(logp != null && logp.contains("STALL-END"), "/log 能返回原始日志");
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"status"});
        check(join(sent).contains("服务运行中"), "status 里能看到服务在跑");
        invokeProcessCommand(cmd, sender, new String[]{"serve", "off"});
        check(!framespike.Report.serverRunning(), "serve off 真的关掉了");

        // 报告文件：后台线程生成（按 mtime 找新文件，避免拿到上一轮跑出来的旧报告）
        File rdir = new File(dir, "reports");
        rdir.mkdirs();
        Cfg.reportDir = rdir.getAbsolutePath();
        final long t0r = System.currentTimeMillis();
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"report"});
        check(join(sent).contains("正在后台生成"), "report 立即回显");
        File newest = null;
        while (System.currentTimeMillis() - t0r < 6000) {
            File[] rs = rdir.listFiles();
            if (rs != null) {
                for (int i = 0; i < rs.length; i++) {
                    if (rs[i].lastModified() >= t0r) newest = rs[i];
                }
            }
            if (newest != null) break;
            Thread.sleep(120);
        }
        check(newest != null && newest.length() > 3000,
                "后台生成了报告文件（" + (newest == null ? "没等到"
                        : newest.getName() + " " + newest.length() + "B") + "）");
        if (newest != null) {
            String saved = new String(file(newest.getAbsolutePath()), "UTF-8");
            check(saved.contains("var DATA = {") && saved.contains("FrameSpike"),
                    "报告文件内容完整（" + saved.length() + " 字符）");
        }
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"help", "report"});
        check(join(sent).contains("火焰图"), "help report 有详情");

        System.out.println("== 12. stop 只停记录（聊天播报继续） ==");
        invokeProcessCommand(cmd, sender, new String[]{"chat", "on"});
        sent.clear();
        invokeProcessCommand(cmd, sender, new String[]{"stop"});
        check(join(sent).contains("聊天播报仍在跑"), "stop 回显新语义（聊天继续）");
        int detBefore = countOf(new String(file(log), "UTF-8"), "STALL-DETECT");
        int endBefore = countOf(new String(file(log), "UTF-8"), "STALL-END");
        sent.clear();
        // 触发停顿（recording=false）：看门狗必须在两次检查点之间抓到，时序敏感 -> 最多试 3 次
        for (int attempt = 0; attempt < 3 && !join(sent).contains("[FS]"); attempt++) {
            FrameSpike.checkpoint("runTick");
            Thread.sleep(400);                 // 不推进检查点 -> watchdog 在 ~60ms 处检测
            FrameSpike.checkpoint("runTick");  // 结算 -> 只走聊天，不写日志
            Thread.sleep(250);
        }
        String lg3 = new String(file(log), "UTF-8");
        int detAfter = countOf(lg3, "STALL-DETECT");
        int endAfter = countOf(lg3, "STALL-END");
        check(detAfter == detBefore,
                "stop 之后日志不新增 STALL 记录（" + detBefore + " -> " + detAfter + "）");
        check(endAfter == endBefore, "stop 之后结算也不落盘（" + endBefore + " -> " + endAfter + "）");
        check(join(sent).contains("[FS]"), "stop 之后聊天照发（sent " + sent.size() + " 条）");
        invokeProcessCommand(cmd, sender, new String[]{"start"});   // 恢复录制

        System.out.println();
        System.out.println(fail == 0 ? "ALL CMD TESTS PASSED" : (fail + " CMD TEST(S) FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }

    private static int countOf(String s, String sub) { return s.split(java.util.regex.Pattern.quote(sub), -1).length - 1; }

    /** 极简 HTTP GET，用来验证本地报告服务真的可用 */
    private static String httpGet(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            System.out.println("      (httpGet 失败: " + t + ")");
            return null;
        }
    }

    /** 造一个假栈（只需前两帧，分类只看前 6 帧的字符串） */
    private static StackTraceElement[] stk(String cls, String m) {
        return new StackTraceElement[]{
                new StackTraceElement(cls, m, "F.java", 1),
                new StackTraceElement("net.minecraft.client.Minecraft", "func_71411_J", "Minecraft.java", 1033),
        };
    }
}
