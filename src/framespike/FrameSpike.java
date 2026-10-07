/*
 * FrameSpike - Minecraft 1.8.9 / Lunar Client 帧暂停取证 coremod
 * Author: Trusler    版本与署名见 FrameSpike.MOD_VERSION / MOD_AUTHOR
 * 改动请同步 build.py 里的 Implementation-Version 与 CHANGELOG.md
 *
 * 注：本文件的部分方法体是在一次误删后从已构建的 jar 反编译恢复的（行为与原文一致），
 *     注释按原设计补回；改动时请以测试（SelfTest / CmdTest / RoundTrip）为准。
 */
package framespike;

import com.sun.management.GarbageCollectionNotificationInfo;
import framespike.Cfg;
import framespike.Cmd;
import framespike.Report;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * FrameSpike 运行时。
 *
 * 被 ASM 注入到游戏类里的是 checkpoint(String) 和 glFinish()。
 * 两个后台线程：
 *   FrameSpike-Watchdog  监测「主线程多久没推进到任何检查点」，超阈值抓主线程栈；兼管 ctrl 文件与 ini 热重载
 *   FrameSpike-Cpu       定期输出全线程 CPU 增量表（getThreadCpuTime，不需要 safepoint），兼管 fps 骤降检测
 * 客户端线程只做两次 nanoTime + 一个 volatile 写；日志只由非客户端线程写
 * （/fs 的回显写日志除外，那是用户主动触发的一次性动作）。
 */
public final class FrameSpike {
    public static final String MOD_NAME = "FrameSpike";
    public static final String MOD_CN = "\u5e27\u523a";
    /** 运行时游戏版本段。加载器（Fabric / NeoForge）启动时覆盖它；1.8.9 coremod 保持默认。 */
    public static String MC_VER = "1.8.9";
    /** 非 final：final 会被编译期内联，加载器就没法按实际游戏版本重算标题了 */
    public static String MOD_TITLE = "Minecraft " + MC_VER + " \u5e27\u65f6\u95f4\u5c16\u5cf0\u4e0e\u5361\u987f\u5206\u6790 mod";
    public static final String MOD_VERSION = "0.7.0";
    public static final String MOD_AUTHOR = "Trusler";
    /** 加载器描述（如 "NeoForge 47.1.106" / "Fabric 0.19.5" / "Forge coremod"），由加载器启动时设置 */
    public static String LOADER = "";
    public static String SIGN = MOD_NAME + " " + MOD_CN + " v" + MOD_VERSION + "  by " + MOD_AUTHOR;
    /**
     * 加载器在首次触碰本类之前调用：设置游戏版本段并重算标题/署名。
     * 1.8.9 的 coremod 入口不调用它，默认值就是 1.8.9，老行为不变。
     */
    public static void setGameVersion(String v) {
        if (v == null) return;
        v = v.trim();
        if (v.length() == 0) return;
        Cfg.mcVersion = v;
        MC_VER = v;
        MOD_TITLE = "Minecraft " + v + " 帧时间尖峰与卡顿分析 mod";
        SIGN = MOD_NAME + " " + MOD_CN + " v" + MOD_VERSION + "  by " + MOD_AUTHOR;
    }

    private static final int HIST = 16;
    private static final long[] hTime = new long[16];
    private static final String[] hLabel = new String[16];
    private static final AtomicInteger seq = new AtomicInteger();
    private static int hHead = 0;
    private static final long CMD_FIRST_TICK = 60L;
    private static final long CMD_RETRY_TICKS = 600L;
    private static volatile long lastNs = 0L;
    private static volatile String lastLabel = "none";
    private static final AtomicBoolean captured = new AtomicBoolean(false);
    private static final AtomicLong frames = new AtomicLong();
    private static final AtomicLong ticks = new AtomicLong();
    private static final AtomicLong stalls = new AtomicLong();
    private static final AtomicLong glSkipped = new AtomicLong();
    private static volatile Thread clientThread;
    private static volatile boolean running;
    private static volatile boolean pendingStall;
    private static volatile long pendingFromNs;
    private static volatile long pendingDetectMs;
    private static volatile String pendingLabel;
    private static volatile String pendingReason;
    private static volatile StackTraceElement[] pendingStack;
    private static volatile long lastChatMs;
    private static volatile String pendingChat;
    /** 这条聊天如果有可点链接，放这儿（客户端线程发完清掉） */
    private static volatile String pendingClick;
    private static final Set<String> seenCrashes;
    private static volatile long lastCrashScan;
    private static final List<GcEvent> gcEvents;
    private static final int FPS_K = 6;
    private static final List<Double> fpsHist;
    private static volatile long lastFpsAlertMs;
    /** /fs tail 用的最近停顿摘要：内存环形表，不看日志体积、也不受栈全量输出影响 */
    private static final java.util.ArrayDeque<String> recentStalls = new java.util.ArrayDeque<String>();
    private static final int RECENT_MAX = 60;

    /** 运行时环境的命名风格（transformer 命中后上报）：notch / SRG / 官方名 */
    private static volatile String envName = "";
    private static volatile String envKinds = "";

    /** transformer 命中后调：记录这份环境用的是哪套命名，/fs version 里显示 */
    public static void reportEnv(String label, String kinds) {
        if (label == null || label.length() == 0) return;
        envName = label;
        envKinds = kinds == null ? "" : kinds;
    }

    private static volatile long lastIniMs = 0L;
    private static int wdTick;
    private static volatile long bootMs;
    private static volatile String worldCtx;
    private static volatile long lastWorldTry;
    private static volatile boolean recording;
    private static volatile long segStartNs;
    private static volatile long segStall0;
    private static volatile long segFrame0;
    private static volatile long segTick0;
    private static volatile long segGl0;
    private static volatile long maxStallMs;
    private static final long[] segBucket;
    private static volatile boolean glPassThrough;
    private static volatile boolean glHandleTried;
    private static volatile Method realGlFinish;
    private static volatile String glState;
    private static volatile int glFinishSites;
    private static volatile Map<Long, Long> cpuSnapshot;
    private static volatile long cpuSnapshotNs;
    private static final AtomicBoolean cpuRequest;
    private static volatile long sampleFrame0;
    private static volatile long sampleTick0;
    private static ClassLoader mcLoader;
    private static volatile boolean ctrlChecked;
    private static volatile int ctrlPollTick;
    private static final Object logLock;
    private static PrintWriter out;
    private static final SimpleDateFormat TS;
    private static final String[] SUBCOMMANDS;
    private static final AtomicLong tabCalls;

    private static void reportAsync(final String string) {
        reportAsync(string, false);
    }

    /** whole = true 时统计整份日志（/fs report all） */
    private static void reportAsync(final String string, final boolean bl) {
        Thread thread = new Thread(new Runnable(){

            @Override
            public void run() {
                try {
                    File file;
                    File file2 = new File(Cfg.logFile);
                    File file3 = null;
                    if (Cfg.reportDir != null && Cfg.reportDir.trim().length() > 0) {
                        file3 = new File(Cfg.reportDir.trim());
                    }
                    if ((file = Report.write(file2, file3, bl)) == null) {
                        pendingChat = "\u00a7c\u62a5\u544a\u751f\u6210\u5931\u8d25\u00a7r  \u770b\u65e5\u5fd7\u91cc\u7684 [report] \u884c";
                    } else {
                        pendingChat = string + "\u00a77" + file.getAbsolutePath() + "\u00a7r";
                        pendingClick = "file:///" + file.getAbsolutePath().replace('\\', '/');
                    }
                }
                catch (Throwable throwable) {
                    pendingChat = "\u00a7c\u62a5\u544a\u751f\u6210\u51fa\u9519: " + throwable;
                }
            }
        }, "FrameSpike-Report");
        thread.setDaemon(true);
        thread.start();
    }

    public static void reportGlFinishSites(int n) {
        if (n > glFinishSites) {
            glFinishSites = n;
        }
    }

    /** 给加载器自检用：已经收到多少个 runGameLoop 检查点（0 = mixin 没命中） */
    public static long framesCount() {
        return frames.get();
    }

    /** 给加载器自检用：已经收到多少个 runTick 检查点 */
    public static long ticksCount() {
        return ticks.get();
    }

    /** 最近一次检查点的标签；从未推进过时是 "none" */
    public static String lastCheckpoint() {
        return lastLabel;
    }

    public static int getGlFinishSites() {
        return glFinishSites;
    }

    private FrameSpike() {
    }

    public static void checkpoint(String string) {
        try {
            long l = System.nanoTime();
            int n = seq.get();
            seq.lazySet(n + 1);
            FrameSpike.hTime[FrameSpike.hHead] = l;
            FrameSpike.hLabel[FrameSpike.hHead] = string;
            hHead = (hHead + 1) % 16;
            seq.lazySet(n + 2);
            if (clientThread == null) {
                clientThread = Thread.currentThread();
            }
            if (pendingStall) {
                FrameSpike.endStall(l, string);
            }
            if (pendingChat != null) {
                String string2 = pendingChat;
                String string3 = pendingClick;
                pendingChat = null;
                pendingClick = null;
                if (string3 != null) {
                    Cmd.chatLink(string2, string3);
                } else {
                    Cmd.chat(string2);
                }
            }
            lastLabel = string;
            lastNs = l;
            captured.set(false);
            if ("runTick".equals(string)) {
                ticks.incrementAndGet();
                FrameSpike.maybeRegisterCommand();
            } else if ("runGameLoop".equals(string)) {
                frames.incrementAndGet();
                /* 这里也触发一次注册：1.12.2 的 runTick 混淆名不在候选表里（实测 hooks=1/2），
                   注册原来只挂在 runTick 上，结果 1.12.2 里 /fs 永远不注册。
                   maybeRegisterCommand 幂等（Cmd.done() 收口），1.8.9 行为不变。 */
                FrameSpike.maybeRegisterCommand();
            }
            if (!running) {
                FrameSpike.startThreads();
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public static void glFinish() {
        try {
            if (!Cfg.proxyGlFinish()) {
                return;
            }
            if (glPassThrough && FrameSpike.invokeRealGlFinish()) {
                return;
            }
            glSkipped.incrementAndGet();
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static boolean invokeRealGlFinish() {
        Method method = realGlFinish;
        if (method == null) {
            if (glHandleTried) {
                return false;
            }
            method = FrameSpike.resolveGlFinish();
            if (method == null) {
                glHandleTried = true;
                glState = "\u53e5\u67c4\u4e0d\u53ef\u7528 -> \u65e0\u6cd5\u900f\u4f20\uff0c\u5b9e\u9645\u884c\u4e3a=\u8df3\u8fc7";
                FrameSpike.note("[glfinish] \u62ff\u4e0d\u5230 GL11.glFinish \u53e5\u67c4\uff0c\u65e0\u6cd5\u900f\u4f20\uff1b\u5f53\u524d\u884c\u4e3a\u9000\u5316\u4e3a\u8df3\u8fc7\u3002");
                return false;
            }
            realGlFinish = method;
        }
        try {
            method.invoke(null, new Object[0]);
            return true;
        }
        catch (Throwable throwable) {
            realGlFinish = null;
            glHandleTried = true;
            glState = "\u8c03\u7528\u5931\u8d25: " + throwable;
            FrameSpike.note("[glfinish] \u53cd\u5c04\u8c03\u7528 GL11.glFinish \u5931\u8d25: " + throwable);
            return false;
        }
    }

    private static Method resolveGlFinish() {
        ClassLoader[] classLoaderArray = new ClassLoader[]{Thread.currentThread().getContextClassLoader(), FrameSpike.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (int i = 0; i < classLoaderArray.length; ++i) {
            ClassLoader classLoader = classLoaderArray[i];
            if (classLoader == null) continue;
            try {
                Class<?> clazz = Class.forName("org.lwjgl.opengl.GL11", false, classLoader);
                return clazz.getMethod("glFinish", new Class[0]);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        return null;
    }

    public static void prepareGlFinish() {
        if (!Cfg.proxyGlFinish()) {
            glState = "n/a (mode=off)";
            return;
        }
        Method method = FrameSpike.resolveGlFinish();
        if (method == null) {
            Cfg.glFinishMode = "off";
            glState = "proxy \u542f\u52a8\u5931\u8d25\uff0c\u5df2\u964d\u7ea7\u4e3a off";
            FrameSpike.note("[glfinish] mode=proxy \u4f46\u89e3\u6790\u4e0d\u5230 org.lwjgl.opengl.GL11.glFinish()\uff0c\u5df2\u81ea\u52a8\u964d\u7ea7\u4e3a off\uff08\u4e0d\u78b0\u5b57\u8282\u7801\uff09\u3002");
            return;
        }
        realGlFinish = method;
        glHandleTried = true;
        glPassThrough = true;
        glState = "proxy \u5c31\u7eea\uff0c\u5f53\u524d=\u900f\u4f20(\u539f\u751f\u884c\u4e3a)";
        FrameSpike.note("[glfinish] mode=proxy\uff0c\u53e5\u67c4\u5df2\u5c31\u7eea\u3002\u6e38\u620f\u5185 /fs glfinish on \u8df3\u8fc7\u3001off \u6062\u590d\u539f\u751f\u3002");
    }

    private static void maybeRegisterCommand() {
        if (Cmd.done()) {
            return;
        }
        /* ticks+frames：1.12.2 的 runTick 不在候选表命中时 ticks 永远 0，
           只看 ticks 会让 1.12.2 的 /fs 永远不注册（实测踩到）。 */
        long l = ticks.get() + frames.get();
        if (l < 60L) {
            return;
        }
        if (l > 660L) {
            if (!Cmd.done()) {
                Cmd.gaveUp = true;
                FrameSpike.note("[cmd] \u91cd\u8bd5\u9884\u7b97\u7528\u5c3d\uff0c\u653e\u5f03\u6ce8\u518c\u5ba2\u6237\u7aef\u547d\u4ee4 -> \u542f\u7528 ctrl \u6587\u4ef6\u901a\u9053: " + Cfg.ctrlFile);
            }
            return;
        }
        if (l % 16L != 0L) {
            return;
        }
        if (mcLoader == null) {
            mcLoader = FrameSpike.findMcLoader();
        }
        if (mcLoader == null) {
            if (l == 60L) {
                FrameSpike.note("[cmd] \u627e\u4e0d\u5230\u80fd\u52a0\u8f7d net.minecraft.command.ICommand \u7684\u7c7b\u52a0\u8f7d\u5668\uff0c\u6ce8\u518c\u6682\u65f6\u65e0\u6cd5\u8fdb\u884c\uff08\u4ecd\u4f1a\u7ee7\u7eed\u91cd\u8bd5\uff09");
            }
            return;
        }
        if (Cmd.tryRegister(mcLoader)) {
            FrameSpike.note("[cmd] " + Cmd.state());
        }
    }

    private static ClassLoader findMcLoader() {
        ClassLoader[] classLoaderArray = new ClassLoader[]{Thread.currentThread().getContextClassLoader(), FrameSpike.class.getClassLoader(), ClassLoader.getSystemClassLoader()};
        for (int i = 0; i < classLoaderArray.length; ++i) {
            ClassLoader classLoader = classLoaderArray[i];
            if (classLoader == null) continue;
            try {
                Class.forName("net.minecraft.command.ICommand", false, classLoader);
                return classLoader;
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        return null;
    }

    private static synchronized void startThreads() {
        if (running) {
            return;
        }
        running = true;
        try {
            Thread thread = new Thread(new Runnable(){

                @Override
                public void run() {
                    FrameSpike.watchdogLoop();
                }
            }, "FrameSpike-Watchdog");
            thread.setDaemon(true);
            thread.setPriority(7);
            thread.start();
            Thread thread2 = new Thread(new Runnable(){

                @Override
                public void run() {
                    FrameSpike.cpuLoop();
                }
            }, "FrameSpike-Cpu");
            thread2.setDaemon(true);
            thread2.start();
            FrameSpike.note("=== " + SIGN + " ===");
            FrameSpike.note("=== Minecraft 1.8.9 \u5e27\u65f6\u95f4\u5c16\u5cf0\u4e0e\u5361\u987f\u5206\u6790 mod ===");
            FrameSpike.note("=== FrameSpike started === " + Cfg.summary() + "  t0=" + System.currentTimeMillis());
            FrameSpike.note("\u6307\u4ee4: " + FrameSpike.usage() + "   (\u522b\u540d " + Cmd.activeAliases + ")");
            bootMs = System.currentTimeMillis();
            FrameSpike.installGcListener();
            FrameSpike.installExitHook();
            Thread thread3 = new Thread(new Runnable(){

                @Override
                public void run() {
                    try {
                        Thread.sleep(3000L);
                    }
                    catch (InterruptedException interruptedException) {
                        return;
                    }
                    FrameSpike.loadCrashState();
                    FrameSpike.scanCrashes(true);
                }
            }, "FrameSpike-CrashScan");
            thread3.setDaemon(true);
            thread3.start();
        }
        catch (Throwable throwable) {
            FrameSpike.note("startThreads failed: " + throwable);
        }
    }

    private static void installExitHook() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){

                @Override
                public void run() {
                    try {
                        StringBuilder stringBuilder = new StringBuilder(2048);
                        stringBuilder.append('\n').append(FrameSpike.ts()).append("  === JVM EXIT ===").append("  uptime=").append(FrameSpike.fmtDur(System.currentTimeMillis() - bootMs)).append("  pendingStall=").append(pendingStall).append("  recording=").append(recording);
                        long l = lastNs;
                        if (l > 0L) {
                            stringBuilder.append("  lastCheckpoint='").append(lastLabel).append("'  ").append((System.nanoTime() - l) / 1000000L).append("ms \u524d");
                        }
                        stringBuilder.append('\n');
                        for (int i = 0; i < 16; ++i) {
                            int n = (hHead + i) % 16;
                            if (hLabel[n] == null || hTime[n] == 0L) continue;
                            stringBuilder.append("    ").append(hLabel[n]).append("  t+").append((hTime[n] - l) / 1000000L).append("ms\n");
                        }
                        FrameSpike.log(stringBuilder.toString());
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                }
            }, "FrameSpike-Exit"));
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static void installGcListener() {
        try {
            int n = 0;
            for (GarbageCollectorMXBean garbageCollectorMXBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!(garbageCollectorMXBean instanceof NotificationEmitter)) continue;
                ((NotificationEmitter)((Object)garbageCollectorMXBean)).addNotificationListener(new NotificationListener(){

                    /*
                     * WARNING - Removed try catching itself - possible behaviour change.
                     */
                    @Override
                    public void handleNotification(Notification notification, Object object) {
                        try {
                            if (!"com.sun.management.gc.notification".equals(notification.getType())) {
                                return;
                            }
                            GarbageCollectionNotificationInfo garbageCollectionNotificationInfo = GarbageCollectionNotificationInfo.from((CompositeData)notification.getUserData());
                            long l = garbageCollectionNotificationInfo.getGcInfo().getDuration();
                            List list = gcEvents;
                            synchronized (list) {
                                gcEvents.add(new GcEvent(System.currentTimeMillis(), l, garbageCollectionNotificationInfo.getGcName()));
                                while (gcEvents.size() > 64) {
                                    gcEvents.remove(0);
                                }
                            }
                            if (l >= 200L) {
                                FrameSpike.note("  [GC] " + garbageCollectionNotificationInfo.getGcName() + "  " + l + "ms");
                            }
                        }
                        catch (Throwable throwable) {
                            // empty catch block
                        }
                    }
                }, null, null);
                ++n;
            }
            FrameSpike.note("[gc] \u5df2\u6302 GC \u901a\u77e5\u76d1\u542c\uff08" + n + " \u4e2a\u6536\u96c6\u5668\uff09\uff1a\u5927 GC \u8bb0\u65e5\u5fd7\uff0c\u505c\u987f\u7ed3\u7b97\u65f6\u81ea\u52a8\u6807\u6ce8\u662f\u5426 GC");
        }
        catch (Throwable throwable) {
            FrameSpike.note("[gc] \u76d1\u542c\u5931\u8d25: " + throwable);
        }
    }

    private static void watchdogLoop() {
        while (true) {
            try {
                while (true) {
                    Thread thread;
                    StackTraceElement[] stackTraceElementArray;
                    long l;
                    long l2;
                    Thread.sleep(2L);
                    if (++wdTick >= 1250) {
                        wdTick = 0;
                        FrameSpike.checkIni();
                        l2 = System.currentTimeMillis();
                        if (l2 - lastCrashScan > 30000L) {
                            lastCrashScan = l2;
                            FrameSpike.scanCrashes(false);
                        }
                    }
                    if (Cmd.gaveUp && ++ctrlPollTick >= 250) {
                        ctrlPollTick = 0;
                        FrameSpike.pollCtrl();
                    }
                    if (!recording) {
                        l2 = lastNs;
                        if (l2 == 0L || clientThread == null || !Cfg.chatEnabled || (l = (System.nanoTime() - l2) / 1000000L) < (long)Cfg.stallThresholdMs || pendingStall || !captured.compareAndSet(false, true)) continue;
                        pendingFromNs = l2;
                        pendingDetectMs = l;
                        pendingLabel = lastLabel;
                        pendingStall = true;
                        stackTraceElementArray = null;
                        try {
                            thread = clientThread;
                            if (thread != null) {
                                stackTraceElementArray = thread.getStackTrace();
                            }
                        }
                        catch (Throwable throwable) {
                            // empty catch block
                        }
                        pendingReason = FrameSpike.classify(stackTraceElementArray);
                        pendingStack = stackTraceElementArray;
                        continue;
                    }
                    l2 = lastNs;
                    if (l2 == 0L || clientThread == null || (l = (System.nanoTime() - l2) / 1000000L) < (long)Cfg.stallThresholdMs || pendingStall || stalls.get() >= (long)Cfg.maxDumps || !captured.compareAndSet(false, true)) continue;
                    pendingFromNs = l2;
                    pendingDetectMs = l;
                    pendingLabel = lastLabel;
                    pendingStall = true;
                    stackTraceElementArray = null;
                    try {
                        thread = clientThread;
                        if (thread != null) {
                            stackTraceElementArray = thread.getStackTrace();
                        }
                    }
                    catch (Throwable throwable) {
                        // empty catch block
                    }
                    pendingReason = FrameSpike.classify(stackTraceElementArray);
                    pendingStack = stackTraceElementArray;
                    FrameSpike.dumpStall(l, false, stackTraceElementArray);
                }
            }
            catch (InterruptedException interruptedException) {
                return;
            }
            catch (Throwable throwable) {
                FrameSpike.note("watchdog error: " + throwable);
                continue;
            }
        }
    }

    /** 降级通道：只在 /fs 注册失败时启用，避免两条通道同时改状态。 */
    private static void pollCtrl() {
        try {
            File f = new File(Cfg.ctrlFile);
            if (!f.isFile()) {
                if (!ctrlChecked) {
                    ctrlChecked = true;
                    writeFile(f, "# FrameSpike 控制文件（/fs 指令注册失败时的降级通道）\n"
                            + "# 每行一个动作，保存即生效，处理完文件会被清空。\n"
                            + "# 可用: start / stop / status / dump / tail 5 / threshold 80 / cpu / glfinish on\n");
                    note("[ctrl] 已创建控制文件: " + f.getAbsolutePath());
                }
                return;
            }
            String txt = readFile(f, 8192);
            if (txt == null) return;
            List<String> lines = new ArrayList<String>();
            String[] raw = txt.split("\\r?\\n");
            for (int i = 0; i < raw.length; i++) {
                String l = raw[i].trim();
                if (l.length() > 0 && !l.startsWith("#")) lines.add(l);
            }
            if (lines.isEmpty()) return;
            writeFile(f, "");
            for (int i = 0; i < lines.size(); i++) {
                String[] tok = lines.get(i).split("\\s+");
                String[] argv = new String[tok.length + 1];
                argv[0] = Cmd.activeName;
                System.arraycopy(tok, 0, argv, 1, tok.length);
                note("[ctrl] 收到: " + lines.get(i));
                handleCommand(null, argv);
            }
        } catch (Throwable t) {
            note("[ctrl] 轮询失败: " + t);
        }
    }

    public static String classify(StackTraceElement[] stackTraceElementArray) {
        try {
            if (stackTraceElementArray == null || stackTraceElementArray.length == 0) {
                return "\u672a\u77e5";
            }
            StringBuilder stringBuilder = new StringBuilder(256);
            int n = Math.min(stackTraceElementArray.length, 6);
            for (int i = 0; i < n; ++i) {
                stringBuilder.append(stackTraceElementArray[i].getClassName()).append('.').append(stackTraceElementArray[i].getMethodName()).append(' ');
            }
            return FrameSpike.classifyFromText(stringBuilder.toString(), stackTraceElementArray[0].getClassName(), stackTraceElementArray[0].getMethodName());
        }
        catch (Throwable throwable) {
            return "\u672a\u77e5";
        }
    }

    /**
     * 归因分类器：模组的实时归类与报告解析共用这一份。
     * 顺序敏感 —— 先按「前 6 帧拼串」匹配具体类别（写文件在类加载之前，因为栈底往往还有 defineClass），
     * 都没命中再按首个栈帧判定「游戏自身 / JDK 内部 / 混淆类」，最后才退回「类.方法」。
     */
    private static final String[][] CLASSIFY_RULES = {
            {"写文件", "FileOutputStream|FileChannel.write|writeBytes|BufferedOutputStream|FileWriter|PrintStream|RandomAccessFile.write"},
            {"读文件", "RandomAccessFile|FileInputStream|FileReader|BufferedReader|WinNTFileSystem|getBooleanAttributes|Files.read|File.length|File.exists"},
            {"Mixin 注入", "mixin|spongepowered|LateApplyingInject|InjectorWrapper|Coprocessor|$sp."},
            {"类加载/remap", "defineClass|ClassLoader|findClass|ClassReader|ClassWriter|SymbolTable|MethodWriter|ByteVector|objectweb.asm|cadixdev|JarFile|ZipFile|Inflater|URLClassPath|MethodHandles|getDeclared|JarURLConnection"},
            {"窗口合成", "WindowsDisplay|WindowsNativeDispatche|Dwm|nUpdate|JNI.invoke"},
            {"GL驱动", "lwjgl.opengl|nglDraw|nglCallList|nglFinish|nSwapBuffers|glTexImage|glFinish|nglDeleteBuffers"},
            {"音效", "paulscode|SoundSystem|SoundManager"},
            {"字体缓存", "FontRenderer|fontRendererObj"},
            {"小地图", "xaero"},
            {"OptiFine", "optifine"},
            {"网络", "io.netty|Channel.read|NetworkManager"},
            {"区块渲染", "RenderChunk|VboChunkFactory|ExtendedBlockStor|ChunkRender|Chunk.func_"},
            {"实体/模型渲染", "RendererLivingEnti|RenderLiving|AnimationControlle|VanillaVertexConsu|ModelBiped|RenderPlayer|ModelRenderer"},
            {"GUI", "net.minecraft.client.gui|GuiScreen|GuiIngame|GuiChat"},
            {"直接内存", "allocateDirect|allocateMemory|tryReserveMemory|reserveMemory|copyMemory"},
            {"等待/限速", "Unsafe.park|LockSupport|Thread.sleep|Object.wait|.await"},
            {"压缩/解压", "Zstd|zstd|Deflater|nZSTD|compress"},
            {"序列化/解析", "protobuf|newBuilderForType|StreamingJsonDecod|JsonFormat|beginStructure|ObjectMapper|Gson|JsonReader"},
            {"图像", "BufferedImage|ImageIO|getRGB|NativeImage|TextureUtil"},
            {"内嵌浏览器", "WebEngine|org.cef|CefBrowser|javafx.scene.web"},
            {"Essential", "gg.essential|SubscriptionServic|EssentialLoader|EssentialConfig"},
            {"异常构造", "fillInStackTrace|Throwable.<init>|Exception.<init>"},
    };

    /** 按首个栈帧判定的类别（前缀匹配） */
    private static final String[][] CLASSIFY_FIRST = {
            {"游戏自身", "net.minecraft."},
            {"JDK 内部", "java."},
            {"JDK 内部", "javax."},
            {"JDK 内部", "jdk."},
            {"JDK 内部", "sun."},
    };

    static String classifyFromText(String string, String string2, String string3) {
        try {
            String string4 = string == null ? "" : string.toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < CLASSIFY_RULES.length; ++i) {
                if (!FrameSpike.containsAny(string4, CLASSIFY_RULES[i][1])) continue;
                return CLASSIFY_RULES[i][0];
            }
            String string5 = string2 == null ? "" : string2;
            String string6 = string3 == null ? "" : string3;
            for (int i = 0; i < CLASSIFY_FIRST.length; ++i) {
                if (!string5.startsWith(CLASSIFY_FIRST[i][1])) continue;
                return CLASSIFY_FIRST[i][0];
            }
            if (string6.matches("func_\\d{5}_.*") || string6.matches("field_\\d{5}_.*")) {
                return "\u6e38\u620f\u81ea\u8eab";
            }
            if (FrameSpike.isObfuscated(string5)) {
                String string7 = string5.toLowerCase(java.util.Locale.ROOT);
                return string7.indexOf("moonsworth") >= 0 || string7.indexOf("lunar") >= 0
                        ? "Lunar \u6df7\u6dc6\u7c7b" : "\u6df7\u6dc6\u7c7b";
            }
            int n = string5.lastIndexOf(46);
            String string8 = n >= 0 ? string5.substring(n + 1) : string5;
            if (string8.length() > 18) {
                string8 = string8.substring(0, 18);
            }
            return string8 + "." + string6;
        }
        catch (Throwable throwable) {
            return "\u672a\u77e5";
        }
    }

    /** 规则里的 token 用 | 分隔；tt 已经小写 */
    private static boolean containsAny(String string, String string2) {
        String[] arrstring = string2.split("\\|");
        for (int i = 0; i < arrstring.length; ++i) {
            if (arrstring[i].length() == 0) continue;
            if (string.indexOf(arrstring[i].toLowerCase(java.util.Locale.ROOT)) >= 0) return true;
        }
        return false;
    }

    /** 混淆类名：去包名取类名，全是大写字母/数字且长度 >= 8（GL11 这类短名不算） */
    static boolean isObfuscated(String string) {
        if (string == null) return false;
        int n = string.lastIndexOf(46);
        String string2 = n >= 0 ? string.substring(n + 1) : string;
        int n2 = string2.lastIndexOf(36);
        if (n2 >= 0) string2 = string2.substring(n2 + 1);
        if (string2.length() < 8) return false;
        for (int i = 0; i < string2.length(); ++i) {
            char c = string2.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') continue;
            return false;
        }
        return true;
    }

    private static void dumpStall(long l, boolean bl, StackTraceElement[] stackTraceElementArray) {
        Thread thread = clientThread;
        StringBuilder stringBuilder = new StringBuilder(8192);
        stringBuilder.append('\n');
        String string = FrameSpike.worldContext();
        stringBuilder.append(FrameSpike.ts()).append(bl ? "  SNAPSHOT " : "  STALL-DETECT ").append(l).append("ms").append("  since='").append(lastLabel).append('\'').append("  frames=").append(frames.get()).append("  ticks=").append(ticks.get()).append("  stall#").append(bl ? stalls.get() : stalls.incrementAndGet()).append("  reason=").append(bl ? FrameSpike.classify(stackTraceElementArray) : pendingReason).append("  glFinishSkipped=").append(glSkipped.get()).append(string.length() > 0 ? "  ctx=" + string : "").append("  t0=").append(System.currentTimeMillis() - l);
        stringBuilder.append('\n').append("  timeline:");
        FrameSpike.appendTimeline(stringBuilder, l);
        stringBuilder.append('\n').append("  stack of \"").append(thread == null ? "?" : thread.getName()).append("\":");
        try {
            if (stackTraceElementArray == null || stackTraceElementArray.length == 0) {
                stringBuilder.append("\n    <empty: thread could not be dumped>");
            } else {
                int n = Cfg.stackDepth <= 0 ? stackTraceElementArray.length : Math.min(stackTraceElementArray.length, Cfg.stackDepth);
                for (int i = 0; i < n; ++i) {
                    stringBuilder.append("\n    ").append(stackTraceElementArray[i]);
                }
                if (stackTraceElementArray.length > n) {
                    stringBuilder.append("\n    ...(").append(stackTraceElementArray.length - n).append(" more)");
                }
            }
        }
        catch (Throwable throwable) {
            stringBuilder.append("\n    <dump failed: ").append(throwable).append('>');
        }
        FrameSpike.log(stringBuilder.toString());
    }

    private static void endStall(long l, String string) {
        long l2 = pendingDetectMs;
        try {
            long l3 = (l - pendingFromNs) / 1000000L;
            if (l3 >= 0L) {
                l2 = l3;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        pendingStall = false;
        if (l2 < pendingDetectMs) {
            l2 = pendingDetectMs;
        }
        String string2 = pendingReason;
        String string3 = FrameSpike.gcOverlap(l2);
        if (recording) {
            FrameSpike.note("  STALL-END    total=" + l2 + "ms  detected@" + pendingDetectMs + "ms  since='" + pendingLabel + "'  next='" + string + "'  reason=" + string2 + (string3 != null ? "  gc=" + string3 : "") + "  t1=" + System.currentTimeMillis());
            if (l2 > maxStallMs) {
                maxStallMs = l2;
            }
            if (l2 < 100L) {
                segBucket[0] = segBucket[0] + 1L;
            } else if (l2 < 200L) {
                segBucket[1] = segBucket[1] + 1L;
            } else if (l2 < 500L) {
                segBucket[2] = segBucket[2] + 1L;
            } else {
                segBucket[3] = segBucket[3] + 1L;
            }
        }
        String gcTag = null;
        if (string3 != null) {
            int n5 = string3.lastIndexOf(32);
            gcTag = n5 >= 0 ? string3.substring(n5 + 1) : string3;   /* 只留 "11ms"，收集器名字在日志/报告里 */
        }
        FrameSpike.rememberStall(l2, string2, pendingLabel, false);
        long l4 = System.currentTimeMillis();
        if (Cfg.chatEnabled && l2 >= (long)Cfg.chatMinMs && l4 - lastChatMs >= (long)Cfg.chatCooldownMs) {
            lastChatMs = l4;
                Cmd.chat("\u00a7e[FS]\u00a7r \u00a7c" + l2 + "ms\u00a7r " + string2
                        + " \u00a77" + FrameSpike.topFrame(pendingStack) + "\u00a7r"
                        + (gcTag != null ? " \u00a7b[GC " + gcTag + "]\u00a7r" : ""));
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /** 记一条摘要给 /fs tail（此时日志里那条 STALL-END 写没写都无所谓） */
    private static void rememberStall(long l, String string, String string2, boolean bl) {
        try {
            String string3 = bl
                    ? FrameSpike.ts() + "  " + l + "ms  " + string + "  （手动 dump）"
                    : FrameSpike.ts() + "  " + l + "ms  " + string
                      + (string2 != null && string2.length() > 0 ? "  since=" + string2 : "");
            java.util.ArrayDeque<String> arrayDeque = recentStalls;
            synchronized (arrayDeque) {
                recentStalls.addLast(string3);
                while (recentStalls.size() > RECENT_MAX) recentStalls.removeFirst();
            }
        }
        catch (Throwable throwable) {
            // 摘要丢了不影响主流程
        }
    }

    private static String gcOverlap(long l) {
        try {
            List<GcEvent> list = gcEvents;
            synchronized (list) {
                if (gcEvents.isEmpty()) {
                    return null;
                }
                long l2 = System.currentTimeMillis();
                long l3 = l2 - l;
                GcEvent gcEvent = null;
                for (int i = 0; i < gcEvents.size(); ++i) {
                    GcEvent gcEvent2 = gcEvents.get(i);
                    if (gcEvent2.endMs < l3 - 100L || gcEvent2.endMs > l2 + 100L || gcEvent != null && gcEvent2.durMs <= gcEvent.durMs) continue;
                    gcEvent = gcEvent2;
                }
                return gcEvent == null ? null : gcEvent.name + " " + gcEvent.durMs + "ms";
            }
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private static String topFrame(StackTraceElement[] stackTraceElementArray) {
        try {
            String string;
            if (stackTraceElementArray == null || stackTraceElementArray.length == 0) {
                return "";
            }
            String string2 = stackTraceElementArray[0].getClassName();
            int n = string2.lastIndexOf(46);
            String string3 = string = n >= 0 ? string2.substring(n + 1) : string2;
            if (string.length() > 22) {
                string = string.substring(0, 22);
            }
            String string4 = string + "." + stackTraceElementArray[0].getMethodName();
            if (stackTraceElementArray[0].getLineNumber() > 0) {
                string4 = string4 + ":" + stackTraceElementArray[0].getLineNumber();
            }
            return string4;
        }
        catch (Throwable throwable) {
            return "";
        }
    }

    private static void appendTimeline(StringBuilder stringBuilder, long l) {
        long l2 = lastNs - l * 1000000L;
        for (int i = 0; i < 3; ++i) {
            int n;
            int n2 = seq.get();
            if ((n2 & 1) != 0) continue;
            StringBuilder stringBuilder2 = new StringBuilder(512);
            for (n = 0; n < 16; ++n) {
                int n3 = (hHead + n) % 16;
                String string = hLabel[n3];
                long l3 = hTime[n3];
                if (string == null || l3 == 0L) continue;
                stringBuilder2.append(String.format("%n    %-20s t+%8.3fms", string, (double)(l3 - l2) / 1000000.0));
            }
            n = seq.get();
            if (n2 != n) continue;
            stringBuilder.append((CharSequence)stringBuilder2);
            return;
        }
        stringBuilder.append("\n    <timeline read inconsistent>");
    }

    private static void cpuLoop() {
        ThreadMXBean threadMXBean;
        try {
            threadMXBean = ManagementFactory.getThreadMXBean();
            if (threadMXBean == null || !threadMXBean.isThreadCpuTimeSupported()) {
                FrameSpike.note("[CPU] \u672c JVM \u4e0d\u652f\u6301 thread cpu time");
                return;
            }
            if (!threadMXBean.isThreadCpuTimeEnabled()) {
                threadMXBean.setThreadCpuTimeEnabled(true);
            }
        }
        catch (Throwable throwable) {
            FrameSpike.note("[CPU] init failed: " + throwable);
            return;
        }
        HashMap<Long, Long> hashMap = new HashMap<Long, Long>();
        long l = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(250L);
            }
            catch (InterruptedException interruptedException) {
                return;
            }
            long l2 = System.nanoTime();
            long l3 = (long)Math.max(5, Cfg.cpuSampleSec) * 1000000000L;
            boolean bl = l2 - l >= l3;
            boolean bl2 = cpuRequest.getAndSet(false);
            if (!bl && !bl2) continue;
            try {
                if (l2 - l < 500000000L) continue;
                double d = (double)(l2 - l) / 1.0E9;
                double d2 = d > 0.0 ? (double)(frames.get() - sampleFrame0) / d : 0.0;
                FrameSpike.fpsDropCheck(d2, l2);
                FrameSpike.log("\n" + FrameSpike.buildCpuTable(threadMXBean, hashMap, l, l2));
                hashMap = FrameSpike.snapshotOf(threadMXBean);
                l = l2;
                sampleFrame0 = frames.get();
                sampleTick0 = ticks.get();
                cpuSnapshot = Collections.unmodifiableMap(new HashMap<Long, Long>(hashMap));
                cpuSnapshotNs = l2;
                continue;
            }
            catch (Throwable throwable) {
                FrameSpike.note("cpu sampler error: " + throwable);
                continue;
            }
        }
    }

    private static void fpsDropCheck(double d, long l) {
        try {
            if (d <= 0.0) {
                return;
            }
            fpsHist.add(d);
            while (fpsHist.size() > 6) {
                fpsHist.remove(0);
            }
            if (fpsHist.size() < 6) {
                return;
            }
            double d2 = 0.0;
            for (int i = 0; i < 5; ++i) {
                d2 += fpsHist.get(i).doubleValue();
            }
            d2 /= 5.0;
            double d3 = fpsHist.get(5);
            if (d2 >= 60.0 && d3 < d2 * 0.5 && l - lastFpsAlertMs > 300000L) {
                lastFpsAlertMs = l;
                FrameSpike.note("  [fps-drop] \u5e27\u7387\u9aa4\u964d: " + Math.round(d2) + " -> " + Math.round(d3) + " fps\uff08\u6700\u8fd1 " + 6 + " \u4e2a\u91c7\u6837\uff09");
                if (Cfg.chatEnabled) {
                    pendingChat = "\u00a7e[FS]\u00a7r \u00a7c\u5e27\u7387\u9aa4\u964d\u00a7r  " + Math.round(d2) + " \u2192 " + Math.round(d3) + " fps\u00a77 \uff085 \u5206\u949f\u5185\u4e0d\u91cd\u590d\u63d0\u793a\uff09\u00a7r";
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static HashMap<Long, Long> snapshotOf(ThreadMXBean threadMXBean) {
        HashMap<Long, Long> hashMap = new HashMap<Long, Long>();
        long[] lArray = threadMXBean.getAllThreadIds();
        for (int i = 0; i < lArray.length; ++i) {
            long l = threadMXBean.getThreadCpuTime(lArray[i]);
            if (l < 0L) continue;
            hashMap.put(lArray[i], l);
        }
        return hashMap;
    }

    private static String buildCpuTable(ThreadMXBean threadMXBean, Map<Long, Long> map, long l, long l2) {
        int n;
        int n2;
        int n3;
        int n4;
        double d = Math.max(0.5, (double)(l2 - l) / 1.0E9);
        long[] lArray = threadMXBean.getAllThreadIds();
        long[] lArray2 = new long[lArray.length];
        long l3 = 0L;
        for (n4 = 0; n4 < lArray.length; ++n4) {
            Long prevCpu = map.get(lArray[n4]);
            long l4 = threadMXBean.getThreadCpuTime(lArray[n4]);
            if (prevCpu == null || l4 < 0L) continue;
            lArray2[n4] = l4 - prevCpu;
            if (lArray2[n4] <= 0L) continue;
            l3 += lArray2[n4];
        }
        n4 = Runtime.getRuntime().availableProcessors();
        StringBuilder sb = new StringBuilder(1024);
        sb.append(FrameSpike.ts()).append("  [CPU] ").append(String.format("%.1fs", d)).append(" window, ").append(n4).append(" cores, machine busy=").append(String.format("%.1f", (double)l3 / 1.0E9 / d / (double)n4 * 100.0)).append('%').append("  (totalCpu=").append(String.format("%.1f", (double)l3 / 1.0E9)).append("s)").append("  fps\u2248").append(String.format("%.1f", (double)(frames.get() - sampleFrame0) / d)).append("  tps\u2248").append(String.format("%.1f", (double)(ticks.get() - sampleTick0) / d)).append("  frames=").append(frames.get()).append("  ticks=").append(ticks.get()).append(" stalls=").append(stalls.get()).append(" glFinishSkipped=").append(glSkipped.get());
        int[] nArray = new int[lArray.length];
        for (n3 = 0; n3 < lArray.length; ++n3) {
            nArray[n3] = n3;
        }
        for (n3 = 0; n3 < lArray.length; ++n3) {
            n2 = n3;
            for (n = n3 + 1; n < lArray.length; ++n) {
                if (lArray2[nArray[n]] <= lArray2[nArray[n2]]) continue;
                n2 = n;
            }
            n = nArray[n3];
            nArray[n3] = nArray[n2];
            nArray[n2] = n;
        }
        n3 = Math.min(Math.max(1, Cfg.cpuTopN), lArray.length);
        for (n2 = 0; n2 < n3 && lArray2[n = nArray[n2]] > 0L; ++n2) {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(lArray[n], 0);
            String string = threadInfo == null ? "tid-" + lArray[n] : threadInfo.getThreadName();
            sb.append(String.format("%n    %-28s %6.1f%% of one core  (%.1fs)", string, (double)lArray2[n] / (d * 1.0E7), (double)lArray2[n] / 1.0E9));
        }
        return sb.toString();
    }

    private static String cpuTableNow() {
        try {
            Map<Long, Long> map = cpuSnapshot;
            if (map.isEmpty()) {
                return "\u5c1a\u65e0 CPU \u91c7\u6837\u5feb\u7167\uff08\u7b49 500ms \u540e\u518d\u8bd5\uff09";
            }
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
            if (threadMXBean == null) {
                return "\u672c JVM \u65e0 ThreadMXBean";
            }
            return FrameSpike.buildCpuTable(threadMXBean, map, cpuSnapshotNs, System.nanoTime());
        }
        catch (Throwable throwable) {
            return "CPU \u8868\u751f\u6210\u5931\u8d25: " + throwable;
        }
    }

    /** 用法串：直接从子命令表拼，新增子命令不用再改这里 */
    public static String usage() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('/').append(Cmd.activeName);
        for (int i = 0; i < SUBCOMMANDS.length; i++) {
            sb.append(i == 0 ? " " : "|").append(SUBCOMMANDS[i]);
        }
        return sb.toString();
    }

    /** 无参数时：只报 mod 版本 / MC+加载器 / JDK 版本（用户要求精简，带颜色） */
    public static String[] quickLines() {
        String ld = LOADER.length() > 0 ? ("  \u00a77\u00b7 " + LOADER + "\u00a7r") : "";
        return new String[]{
                "\u00a7e" + MOD_NAME + " " + MOD_CN + "\u00a7r \u00a77v" + MOD_VERSION + "\u00a7r",
                "\u00a77Minecraft " + MC_VER + ld + "\u00a7r",
                "\u00a77Java " + System.getProperty("java.version", "?") + "\u00a7r",
                "\u00a77全部命令: " + "/" + Cmd.activeName + " help\u00a7r"
        };
    }

    /** /fs version：一行行报身份与运行环境，排查时截图给作者就够 */
    public static String[] versionLines() {
        String p = "/" + Cmd.activeName;
        return new String[]{
                "\u00a7e" + MOD_NAME + " " + MOD_CN + "\u00a7r \u00a77v" + MOD_VERSION
                        + "  by " + MOD_AUTHOR + "\u00a7r",
                "\u00a77" + MOD_TITLE,
                "\u00a77纯 coremod \u00b7 零外部依赖 \u00b7 不向外部发送任何数据 \u00b7 Forge 1.8.9 / Lunar Client",
                "\u00a77环境=" + (envName.length() > 0 ? envName : "未探测")
                        + (envKinds.length() > 0 ? ("（" + envKinds + "）") : "")
                        + "   java=" + System.getProperty("java.version", "?")
                        + "   ini=" + Cfg.iniPath(),
                "\u00a77日志=" + Cfg.logFile,
                "\u00a77记录=" + (recording ? "开" : "关")
                        + "   阈值=" + Cfg.stallThresholdMs + "ms"
                        + "   栈深=" + (Cfg.stackDepth <= 0 ? "不截断" : Cfg.stackDepth + " 层")
                        + "   上限=" + Cfg.maxDumps + " 次",
                "\u00a77命令=" + p + "   别名=" + Cmd.activeAliases
                        + "   聊天=" + (Cfg.chatEnabled ? "开" : "关")
                        + "（>=" + Cfg.chatMinMs + "ms）",
                "\u00a77报告服务=" + (Report.serverRunning()
                        ? ("\u00a7ahttp://127.0.0.1:" + Report.serverPort() + "/") : "未启动（" + p + " serve）"),
                "\u00a77崩溃监控 / GC 关联 / fps 骤降 / 世界上下文 / ini 热重载：全部内置",
                "\u00a77" + SIGN
        };
    }

    /** 分组全表：左列命令、右列一句话（详情见 /fs help <子命令>） */
    public static String[] helpLines() {
        String p = "/" + Cmd.activeName + " ";
        return new String[]{
                "\u00a7e" + MOD_NAME + " " + MOD_CN + "\u00a7r \u00a77v" + MOD_VERSION,
                "\u00a7e\u00a7l记录\u00a7r",
                "  start            \u00a77开始记录\u00a7r",
                "  stop             \u00a77停止并出汇总\u00a7r",
                "  status           \u00a77当前状态\u00a7r",
                "\u00a7e\u00a7l取证\u00a7r",
                "  dump             \u00a77抓一次主线程栈\u00a7r",
                "  mark <文本>      \u00a77插对齐标记\u00a7r",
                "  tail [n]         \u00a77最近 n 条停顿\u00a7r",
                "\u00a7e\u00a7l报告\u00a7r",
                "  report [all]     \u00a77生成 HTML 报告\u00a7r",
                "  serve [端口|off] \u00a77本地实时报告页\u00a7r",
                "\u00a7e\u00a7l调参\u00a7r",
                "  threshold <ms>   \u00a77停顿阈值（当前 " + Cfg.stallThresholdMs + "ms）\u00a7r",
                "  chat on|off      \u00a77聊天提示\u00a7r",
                "  chatmin <ms>     \u00a77提示门槛\u00a7r",
                "  glfinish on|off  \u00a77glFinish 干预\u00a7r",
                "  config           \u00a77配置：列出 / 设置 / save 写回 ini\u00a7r",
                "  reload           \u00a77重新读 ini\u00a7r",
                "\u00a7e\u00a7l其它\u00a7r",
                "  cpu              \u00a77全线程 CPU 表\u00a7r",
                "  version          \u00a77版本与环境\u00a7r",
                "  help [子命令]    \u00a77单条详情\u00a7r",
                "\u00a77详情: " + p + "help <子命令>",
                "\u00a77" + SIGN
        };
    }

    public static String[] detailLines(String string) {
        String string2 = "/" + Cmd.activeName + " ";
        if (string.equals("start")) {
            return new String[]{"\u00a7e" + string2 + "start", "  \u5f00\u59cb\u4e00\u6bb5\u8bb0\u5f55\uff1a\u91cd\u7f6e\u672c\u6bb5\u7edf\u8ba1\uff08\u5e27\u6570/tick/\u5206\u6863/\u6700\u957f\uff09\u5e76\u5199\u4e00\u884c RECORDING STARTED\u3002", "  \u5efa\u8bae\uff1a\u8fdb\u670d\u7a33\u5b9a\u540e\u5148\u6253\u4e00\u6b21\uff0c\u8fd9\u6837\u7edf\u8ba1\u91cc\u4e0d\u4f1a\u6df7\u8fdb\u8fdb\u670d/\u6362\u56fe\u7684\u4e00\u6b21\u6027\u5f00\u9500\u3002"};
        }
        if (string.equals("stop")) {
            return new String[]{"\u00a7e" + string2 + "stop", "  \u7ed3\u675f\u672c\u6bb5\u5e76\u8f93\u51fa\u6c47\u603b\uff1a\u65f6\u957f\u3001\u5e27\u6570(fps)\u3001tick(tps)\u3001\u505c\u987f\u6b21\u6570\u3001\u771f\u5b9e\u65f6\u957f\u5206\u6863\u3001\u6700\u957f\u4e00\u6b21\u3002", "  \u65f6\u957f\u662f\u7ed3\u7b97\u51fa\u6765\u7684\u771f\u5b9e\u503c\uff08\u4e0d\u662f\u68c0\u6d4b\u65f6\u523b\uff09\uff0c\u6240\u4ee5\u80fd\u533a\u5206 60ms \u548c 1.5s\u3002", "  \u6b63\u5728\u8fdb\u884c\u7684\u505c\u987f\u4e5f\u4f1a\u5728\u8fd9\u4e00\u523b\u88ab\u515c\u5e95\u7ed3\u7b97\u3002", "  stop \u53ea\u505c\u00a7f\u8bb0\u5f55\u00a7r\uff1a\u804a\u5929\u64ad\u62a5\u4e00\u76f4\u5f00\u7740\uff0c\u5361\u987f\u7167\u6837\u63d0\u793a\uff1b/fs start \u6062\u590d\u5b8c\u6574\u8bb0\u5f55\u3002"};
        }
        if (string.equals("status")) {
            return new String[]{"\u00a7e" + string2 + "status", "  \u4e00\u884c\u884c\u770b\u5f53\u524d\u72b6\u6001\uff1a\u662f\u5426\u5728\u8bb0\u5f55\u3001\u9608\u503c\u3001glFinish \u6a21\u5f0f\u4e0e\u8c03\u7528\u70b9\u6570\u3001\u6ce8\u518c\u8be6\u60c5\u3001chat \u8bbe\u7f6e\u3001\u65e5\u5fd7\u8def\u5f84\u3002", "  Tab \u6ca1\u53cd\u5e94\u65f6\u770b\u8fd9\u884c\u91cc\u7684 tabCalls\uff1a\u4e3a 0 = \u8bf7\u6c42\u6ca1\u5230 mod\uff0c\u4e0d\u4e3a 0 = \u5230\u4e86\u4f46\u56de\u586b\u6709\u95ee\u9898\u3002"};
        }
        if (string.equals("dump")) {
            return new String[]{"\u00a7e" + string2 + "dump", "  \u7acb\u523b\u6293\u4e00\u6b21\u4e3b\u7ebf\u7a0b\u8c03\u7528\u6808\u5e76\u5199\u5165\u65e5\u5fd7\uff08\u4e0d\u4f9d\u8d56\u505c\u987f\u9608\u503c\uff09\u3002", "  \u7528\u9014\uff1a\u4f60\u89c9\u5f97\u300c\u5c31\u662f\u8fd9\u4e00\u4e0b\u5361\u4e86\u300d\u7684\u65f6\u5019\u624b\u52a8\u6253\u70b9\uff0c\u987a\u4fbf\u770b\u5b83\u5f52\u5230\u54ea\u4e00\u7c7b\u3002"};
        }
        if (string.equals("mark")) {
            return new String[]{"\u00a7e" + string2 + "mark <\u6587\u672c>", "  \u5f80\u65e5\u5fd7\u63d2\u4e00\u6761\u5e26\u65f6\u95f4\u6233\u7684\u6807\u8bb0\u3002\u628a\u65e5\u5fd7\u65f6\u95f4\u7ebf\u548c\u5b9e\u9645\u4e8b\u4ef6\u5bf9\u9f50\u7528\u3002", "  \u4f8b\uff1a" + string2 + "mark \u5f00\u5c40 / " + string2 + "mark \u88ab\u6253\u4e86 / " + string2 + "mark \u6362\u56fe"};
        }
        if (string.equals("tail")) {
            return new String[]{"\u00a7e" + string2 + "tail [n]", "  \u8bfb\u65e5\u5fd7\u5c3e\u90e8\uff0c\u628a\u6700\u8fd1 n \u6761 STALL \u7684\u9996\u884c\uff08\u65f6\u523b/\u65f6\u957f/\u539f\u56e0\uff09\u56de\u663e\u5230\u804a\u5929\u3002\u9ed8\u8ba4 3\uff0c\u4e0a\u9650 20\u3002", "  \u7528\u9014\uff1a\u521a\u5361\u5b8c\u60f3\u7acb\u523b\u786e\u8ba4\u662f\u54ea\u4e00\u7c7b\uff0c\u4e0d\u7528\u5207\u51fa\u53bb\u770b\u6587\u4ef6\u3002"};
        }
        if (string.equals("threshold")) {
            return new String[]{"\u00a7e" + string2 + "threshold <ms>", "  \u505c\u987f\u5224\u5b9a\u9608\u503c\uff0c\u5f53\u524d " + Cfg.stallThresholdMs + "ms\uff0c\u8303\u56f4 20-5000\u3002", "  \u8c03\u4f4e\u80fd\u6293\u5230\u66f4\u5c0f\u7684\u6296\u52a8\uff08\u4f46\u65e5\u5fd7\u4f1a\u53d8\u591a\uff09\uff1b\u8c03\u9ad8\u53ea\u770b\u5927\u5361\u987f\u3002"};
        }
        if (string.equals("chat")) {
            return new String[]{"\u00a7e" + string2 + "chat on|off", "  \u5361\u987f\u65f6\u5728\u804a\u5929\u91cc\u63d0\u793a\u4e00\u884c\uff0c\u5f53\u524d " + (Cfg.chatEnabled ? "\u5f00" : "\u5173") + "\u3002", "  \u683c\u5f0f\uff1a[FS] 312ms  \u5199\u6587\u4ef6  FileOutputStream.writeBytes:0", "  \u6709\u4e09\u9053\u95f8\u9632\u5237\u5c4f\uff1a\u65f6\u957f\u95e8\u69db\u3001\u51b7\u5374\u3001\u603b\u5f00\u5173\u3002\u65e5\u5fd7\u4e0d\u53d7\u5f71\u54cd\uff0c\u6c38\u8fdc\u5168\u91cf\u8bb0\u3002"};
        }
        if (string.equals("chatmin")) {
            return new String[]{"\u00a7e" + string2 + "chatmin <ms>", "  \u804a\u5929\u63d0\u793a\u7684\u95e8\u69db\uff0c\u5f53\u524d " + Cfg.chatMinMs + "ms\uff08\u8303\u56f4 50-5000\uff09\u3002", "  \u4f4e\u4e8e\u5b83\u7684\u505c\u987f\u53ea\u8fdb\u65e5\u5fd7\u4e0d\u5237\u5c4f\u3002\u51b7\u5374 " + Cfg.chatCooldownMs + "ms \u5185\u6700\u591a\u4e00\u6761\u3002"};
        }
        if (string.equals("glfinish")) {
            return new String[]{"\u00a7e" + string2 + "glfinish on|off", "  on = \u8df3\u8fc7\u6bcf\u5e27\u7684 glFinish\uff08\u5e72\u9884\u751f\u6548\uff09\uff0coff = \u6062\u590d\u539f\u751f\u900f\u4f20\u3002\u5f53\u524d " + glState + "\u3002", "  \u9700\u8981 ini \u91cc glFinishMode=proxy \u5e76\u5728\u542f\u52a8\u65f6\u89e3\u6790\u5230\u8c03\u7528\u70b9\uff0c\u5426\u5219\u4f1a\u76f4\u63a5\u544a\u8bc9\u4f60\u4e0d\u53ef\u7528\u3002", "  \u8c03\u7528\u70b9\u6570\u91cf\u89c1 /" + Cmd.activeName + " status \u91cc\u7684 sites\u3002"};
        }
        if (string.equals("cpu")) {
            return new String[]{"\u00a7e" + string2 + "cpu", "  \u7acb\u523b\u8f93\u51fa\u5168\u7ebf\u7a0b CPU \u8868\uff08\u524d\u51e0\u884c\u8fdb\u804a\u5929\uff0c\u5b8c\u6574\u8868\u8fdb\u65e5\u5fd7\uff09\u3002", "  \u7528\u9014\uff1a\u5224\u65ad\u300c\u662f\u4e0d\u662f\u522b\u7684\u7ebf\u7a0b/\u522b\u7684\u8fdb\u7a0b\u5728\u62a2\u300d\uff0c\u4ee5\u53ca\u300c\u673a\u5668\u8fd8\u6709\u6ca1\u6709\u4f59\u91cf\u300d\u3002"};
        }
        if (string.equals("report")) {
            return new String[]{"\u00a7e" + string2 + "report", "  \u628a\u5f53\u524d\u65e5\u5fd7\u53d8\u6210\u4e00\u4efd\u81ea\u5305\u542b\u7684 HTML \u5206\u6790\u62a5\u544a\uff08\u7c7b\u4f3c spark \u7684\u67e5\u770b\u5668\uff09\uff1a", "  \u65f6\u957f\u5206\u6863 / \u539f\u56e0\u5f52\u56e0 / \u706b\u7130\u56fe\uff08\u5bbd\u5ea6=\u7d2f\u8ba1\u505c\u7559\u65f6\u957f\uff0c\u53ef\u70b9\u51fb\u805a\u7126\uff09/ CPU \u4e0e\u5e27\u7387\u8d8b\u52bf / \u505c\u987f\u660e\u7ec6\uff08\u53ef\u5c55\u5f00\u6808\uff09\u3002", "  \u751f\u6210\u5728\u65e5\u5fd7\u540c\u76ee\u5f55\uff08\u6216 ini \u91cc reportDir \u6307\u5b9a\u7684\u76ee\u5f55\uff09\uff0c\u6587\u4ef6\u540d report-<\u65f6\u95f4>.html\uff0c\u53cc\u51fb\u7528\u6d4f\u89c8\u5668\u6253\u5f00\u3002", "  \u540e\u53f0\u7ebf\u7a0b\u751f\u6210\uff0c\u4e0d\u4f1a\u5728\u6e38\u620f\u91cc\u7559\u4e0b\u505c\u987f\u3002"};
        }
        if (string.equals("serve")) {
            return new String[]{"\u00a7e" + string2 + "serve [\u7aef\u53e3|off]", "  \u8d77\u4e00\u4e2a\u672c\u5730 HTTP \u62a5\u544a\u670d\u52a1\uff08\u53ea\u7ed1 127.0.0.1\uff09\uff0c\u9ed8\u8ba4\u7aef\u53e3 8731\uff0c\u6d4f\u89c8\u5668\u6253\u5f00 http://127.0.0.1:8731/ \u3002", "  \u9875\u9762\u6bcf\u6b21\u5237\u65b0\u90fd\u4f1a\u91cd\u65b0\u8bfb\u65e5\u5fd7\uff0c\u7b49\u4e8e\u00a7f\u6d3b\u7684\u00a7r\uff1b/log \u8def\u5f84\u7ed9\u539f\u59cb\u65e5\u5fd7\u3002", "  \u5173\u95ed: " + string2 + "serve off \u3002\u53ea\u7ed9\u672c\u673a\u770b\uff0c\u4e0d\u5bf9\u5916\u66b4\u9732\u3002"};
        }
        if (string.equals("version")) {
            return new String[]{"\u00a7e" + string2 + "version",
                    "  报版本、作者与运行环境：java 版本、ini 路径、日志路径、记录开关、阈值、栈深、命令名、报告服务。",
                    "  遇到问题截这一屏 + /logs 里的段落就够定位，不用再问\u201c你装的哪个版本\u201d。"};
        }
        if (string.equals("help")) {
            return new String[]{"\u00a7e" + string2 + "\u5e2e\u52a9 [\u5b50\u547d\u4ee4]", "  \u4e0d\u5e26\u53c2\u6570\uff1a\u65e0\u53c2 /" + Cmd.activeName + " \u7ed9\u5feb\u901f\u4e0a\u624b\uff0c/" + Cmd.activeName + " help \u7ed9\u5206\u7ec4\u5168\u8868\u3002", "  \u5e26\u53c2\u6570\uff1a\u7ed9\u5355\u4e2a\u5b50\u547d\u4ee4\u7684\u8be6\u60c5\uff0c\u4f8b " + string2 + "help tail\u3002"};
        }
        if (string.equals("config")) {
            return new String[]{"\u00a7e" + string2 + "config", "  \u4e0d\u5e26\u53c2\u6570\uff1a\u5217\u51fa\u5168\u90e8\u53ef\u914d\u7f6e\u9879\u4e0e\u5f53\u524d\u503c\u3002", "  config <\u952e> <\u503c>\uff1a\u70ed\u6539\u4e00\u9879\uff0c\u952e\u6709 threshold chatmin chatcool maxdumps stackdepth cpusec cputop autoreport\u3002", "  config save\uff1a\u628a\u5f53\u524d\u503c\u5199\u56de ini\uff1b\u91cd\u8bfb\u7528 /" + Cmd.activeName + " reload\u3002"};
        }
        if (string.equals("reload")) {
            return new String[]{"\u00a7e" + string2 + "reload", "  \u91cd\u65b0\u8bfb ini\uff0c\u624b\u52a8\u6539\u5b8c ini \u6216 config save \u4e4b\u540e\u7528\u3002", "  \u5e73\u65f6\u4e0d\u9700\u8981\uff1aini \u6709\u70ed\u6539\u76d1\u89c6\uff08\u7ea6 2.5 \u79d2\u81ea\u52a8\u751f\u6548\uff09\u3002"};
        }
        return new String[]{"\u00a7c\u6ca1\u6709\u8fd9\u4e2a\u5b50\u547d\u4ee4: " + string + "\u00a7r", "\u8bd5\u8bd5: " + FrameSpike.usage(), "\u00a77" + SIGN};
    }

    private static String[] stripCommandName(String[] stringArray) {
        boolean bl;
        String string;
        if (stringArray == null || stringArray.length == 0) {
            return new String[0];
        }
        String string2 = string = stringArray[0] == null ? "" : stringArray[0].trim();
        if (string.startsWith("/")) {
            string = string.substring(1);
        }
        if (!(bl = string.equalsIgnoreCase(Cmd.activeName))) {
            for (int i = 0; i < Cmd.activeAliases.size(); ++i) {
                if (!string.equalsIgnoreCase(Cmd.activeAliases.get(i))) continue;
                bl = true;
                break;
            }
        }
        if (!bl) {
            return stringArray;
        }
        String[] stringArray2 = new String[stringArray.length - 1];
        System.arraycopy(stringArray, 1, stringArray2, 0, stringArray2.length);
        return stringArray2;
    }

    public static List<String> tabComplete(String[] stringArray) {
        ArrayList<String> arrayList = new ArrayList<String>();
        try {
            long l = tabCalls.incrementAndGet();
            String[] stringArray2 = FrameSpike.stripCommandName(stringArray);
            if (stringArray2.length == 0) {
                for (int i = 0; i < SUBCOMMANDS.length; ++i) {
                    arrayList.add(SUBCOMMANDS[i]);
                }
            } else if (stringArray2.length == 1) {
                String string = stringArray2[0] == null ? "" : stringArray2[0];
                for (int i = 0; i < SUBCOMMANDS.length; ++i) {
                    if (!SUBCOMMANDS[i].startsWith(string)) continue;
                    arrayList.add(SUBCOMMANDS[i]);
                }
            } else if (stringArray2.length == 2) {
                String string;
                String string2 = stringArray2[0] == null ? "" : stringArray2[0].toLowerCase();
                String string3 = string = stringArray2[1] == null ? "" : stringArray2[1].toLowerCase();
                if (string2.equals("glfinish")) {
                    if ("on".startsWith(string)) {
                        arrayList.add("on");
                    }
                    if ("off".startsWith(string)) {
                        arrayList.add("off");
                    }
                } else if (string2.equals("config")) {
                    String[] keys = new String[]{"list", "save", "threshold", "chatmin", "chatcool", "maxdumps", "stackdepth", "cpusec", "cputop", "autoreport"};
                    for (int i = 0; i < keys.length; ++i) {
                        if (keys[i].startsWith(string)) {
                            arrayList.add(keys[i]);
                        }
                    }
                }
            }
            if (l <= 3L) {
                FrameSpike.note("[cmd] tabComplete#" + l + " -> " + arrayList);
            }
        }
        catch (Throwable throwable) {
            FrameSpike.note("[cmd] tabComplete \u51fa\u9519: " + throwable);
        }
        return arrayList;
    }

    public static void handleCommand(Object object, String[] stringArray) {
        try {
            String string;
            String[] objectArray = FrameSpike.stripCommandName(stringArray);
            String string2 = objectArray.length > 0 && objectArray[0] != null ? objectArray[0].toLowerCase() : "";
            String string3 = string = object == null ? "ctrl-file" : "/" + Cmd.activeName;
            if (string2.equals("start")) {
                segStartNs = System.nanoTime();
                segStall0 = stalls.get();
                segFrame0 = frames.get();
                segTick0 = ticks.get();
                segGl0 = glSkipped.get();
                maxStallMs = 0L;
                for (int i = 0; i < segBucket.length; ++i) {
                    FrameSpike.segBucket[i] = 0L;
                }
                recording = true;
                FrameSpike.note("=== RECORDING STARTED (by " + string + ") === threshold=" + Cfg.stallThresholdMs + "ms glFinishMode=" + Cfg.glFinishMode + "  t0=" + System.currentTimeMillis());
                Cmd.reply(object, "\u5df2\u5f00\u59cb\u8bb0\u5f55\uff08\u9608\u503c " + Cfg.stallThresholdMs + "ms\uff09\u3002\u65e5\u5fd7: " + Cfg.logFile);
                return;
            }
            if (string2.equals("stop")) {
                if (pendingStall) {
                    FrameSpike.endStall(System.nanoTime(), "<stop>");
                }
                recording = false;
                String string4 = FrameSpike.summaryText();
                FrameSpike.note("=== RECORDING STOPPED (by " + string + ") ===  t1=" + System.currentTimeMillis() + "\n" + string4);
                Cmd.reply(object, "\u5df2\u505c\u6b62\u8bb0\u5f55\uff08\u00a7f\u804a\u5929\u64ad\u62a5\u4ecd\u5728\u8dd1\u00a7r\uff0c\u5361\u987f\u7167\u6837\u63d0\u793a\uff1b/fs start \u6062\u590d\u5b8c\u6574\u8bb0\u5f55\uff09\u3002");
                String[] stringArray2 = string4.split("\n");
                for (int i = 0; i < stringArray2.length; ++i) {
                    Cmd.reply(object, stringArray2[i]);
                }
                if (Cfg.autoReport) {
                    FrameSpike.reportAsync("\u00a7a\u62a5\u544a\u5df2\u751f\u6210\u00a7r  \u7528\u6d4f\u89c8\u5668\u6253\u5f00: ");
                }
                return;
            }
            if (string2.equals("status")) {
                Cmd.reply(object, "recording=" + recording + "  ini.enabled=" + Cfg.enabled + "  (stop \u53ea\u505c\u8bb0\u5f55\uff0c\u804a\u5929\u64ad\u62a5\u5e38\u5f00)");
                Cmd.reply(object, "\u5d29\u6e83\u76d1\u63a7=\u5f00\uff08crash-reports + \u6b7b\u524d\u65f6\u95f4\u7ebf\uff09  GC\u5173\u8054=\u5f00  fps\u9aa4\u964d=\u5f00  ini\u70ed\u91cd\u8f7d=\u5f00");
                Cmd.reply(object, "threshold=" + Cfg.stallThresholdMs + "ms  frames=" + frames.get() + "  stalls=" + stalls.get() + "  maxDump=" + Cfg.maxDumps + "  tabCalls=" + tabCalls.get());
                Cmd.reply(object, "glFinishMode=" + Cfg.glFinishMode + "  passThrough=" + glPassThrough + "  skipped=" + glSkipped.get() + "  sites=" + glFinishSites + "  [" + glState + "]");
                Cmd.reply(object, Cmd.state());
                Cmd.reply(object, "chat=" + Cfg.chatEnabled + "  chatMinMs=" + Cfg.chatMinMs + "  cooldown=" + Cfg.chatCooldownMs + "ms");
                Cmd.reply(object, "log=" + Cfg.logFile);
                Cmd.reply(object, "report: autoReport=" + Cfg.autoReport + (Report.serverRunning() ? "  \u670d\u52a1\u8fd0\u884c\u4e2d http://127.0.0.1:" + Report.serverPort() + "/" : "  \u670d\u52a1\u672a\u542f\u52a8\uff08/fs serve\uff09"));
                Cmd.reply(object, SIGN);
                if (Cmd.gaveUp) {
                    Cmd.reply(object, "ctrl \u901a\u9053\u5df2\u542f\u7528: " + Cfg.ctrlFile);
                }
                return;
            }
            if (string2.equals("dump")) {
                long l = lastNs == 0L ? 0L : (System.nanoTime() - lastNs) / 1000000L;
                StackTraceElement[] stackTraceElementArray = null;
                try {
                    Thread thread = clientThread;
                    if (thread != null) {
                        stackTraceElementArray = thread.getStackTrace();
                    }
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                FrameSpike.dumpStall(l, true, stackTraceElementArray);
                Cmd.reply(object, "\u5df2\u6293\u53d6\u4e00\u6b21\u5feb\u7167\uff08\u8ddd\u4e0a\u4e2a\u68c0\u67e5\u70b9 " + l + "ms\uff09\uff1a" + FrameSpike.classify(stackTraceElementArray));
                return;
            }
            if (string2.equals("mark")) {
                StringBuilder stringBuilder = new StringBuilder();
                for (int i = 1; i < objectArray.length; ++i) {
                    if (stringBuilder.length() > 0) {
                        stringBuilder.append(' ');
                    }
                    stringBuilder.append(objectArray[i]);
                }
                FrameSpike.note("MARK " + FrameSpike.ts() + "  " + stringBuilder);
                Cmd.reply(object, "\u5df2\u6807\u8bb0: " + stringBuilder);
                return;
            }
            if (string2.equals("tail")) {
                List<String> list;
                int n = 3;
                try {
                    if (objectArray.length > 1) {
                        n = Integer.parseInt(objectArray[1].trim());
                    }
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                if (n < 1) {
                    n = 1;
                }
                if (n > 20) {
                    n = 20;
                }
                if ((list = FrameSpike.tailStalls(n)).isEmpty()) {
                    Cmd.reply(object, "\u65e5\u5fd7\u91cc\u8fd8\u6ca1\u6709 STALL \u8bb0\u5f55\u3002");
                }
                for (int i = 0; i < list.size(); ++i) {
                    Cmd.reply(object, list.get(i));
                }
                return;
            }
            if (string2.equals("threshold")) {
                try {
                    int n = Integer.parseInt(objectArray[1].trim());
                    if (n < 20) {
                        n = 20;
                    }
                    if (n > 5000) {
                        n = 5000;
                    }
                    Cfg.stallThresholdMs = n;
                    Cmd.reply(object, "\u9608\u503c\u5df2\u6539\u4e3a " + n + "ms");
                }
                catch (Throwable throwable) {
                    Cmd.reply(object, "\u7528\u6cd5: /" + Cmd.activeName + " threshold <20-5000>");
                }
                return;
            }
            if (string2.equals("cpu")) {
                cpuRequest.set(true);
                String string5 = FrameSpike.cpuTableNow();
                Cmd.reply(object, "\u5df2\u89e6\u53d1\u91c7\u6837\uff08\u7ea6 250ms \u540e\u5199\u5165\u65e5\u5fd7\uff09\u3002\u5f53\u524d\u5feb\u7167\uff1a");
                String[] stringArray3 = string5.split("\n");
                for (int i = 0; i < stringArray3.length && i < 4; ++i) {
                    Cmd.reply(object, stringArray3[i]);
                }
                return;
            }
            if (string2.equals("glfinish")) {
                String string6;
                if (!Cfg.proxyGlFinish()) {
                    Cmd.reply(object, "\u5f53\u524d glFinishMode=" + Cfg.glFinishMode + "\uff0c\u672a\u63a5\u7ba1\u8c03\u7528\u70b9\uff0c\u65e0\u6cd5\u70ed\u5207\u3002\u8981\u542f\u7528\u8bf7\u628a ini \u7684 glFinishMode \u6539\u6210 proxy \u5e76\u91cd\u542f\u4e00\u6b21\u3002");
                    return;
                }
                if (glFinishSites == 0) {
                    Cmd.reply(object, "\u5b57\u8282\u7801\u91cc\u6ca1\u627e\u5230 GL11.glFinish \u8c03\u7528\u70b9\uff08\u65e5\u5fd7\u91cc\u6709 WARNING \u884c\uff09\uff0c\u8bf4\u660e\u5b83\u662f OptiFine/Lunar \u5728\u66f4\u665a\u7684\u9636\u6bb5\u6ce8\u5165\u7684\uff0cproxy \u63a5\u7ba1\u4e0d\u4e86 \u2014\u2014 \u8fd9\u4e2a\u529f\u80fd\u672c\u6b21\u4e0d\u53ef\u7528\u3002");
                    Cmd.reply(object, "\u8981\u9a8c\u8bc1 Smooth FPS \u7684\u540c\u6b65\u5f00\u9500\uff0c\u53ea\u80fd\u6539 optionsof.txt \u91cc\u7684 ofSmoothFps \u505a A/B\u3002");
                    return;
                }
                String string7 = string6 = objectArray.length > 1 && objectArray[1] != null ? objectArray[1].toLowerCase() : "";
                if (string6.equals("on")) {
                    glPassThrough = false;
                    glState = "proxy\uff0c\u5f53\u524d=\u8df3\u8fc7 glFinish";
                    Cmd.reply(object, "glFinish \u5df2\u8df3\u8fc7\uff08\u5e72\u9884\u751f\u6548\uff09\u3002\u7528 /" + Cmd.activeName + " glfinish off \u6062\u590d\u539f\u751f\u3002");
                } else if (string6.equals("off")) {
                    glPassThrough = true;
                    glState = "proxy\uff0c\u5f53\u524d=\u900f\u4f20(\u539f\u751f)";
                    Cmd.reply(object, "glFinish \u5df2\u6062\u590d\u539f\u751f\uff08\u900f\u4f20\uff09\u3002");
                } else {
                    Cmd.reply(object, "\u5f53\u524d passThrough=" + glPassThrough + "\u3002\u7528\u6cd5: glfinish on|off");
                }
                FrameSpike.note("[glfinish] " + glState + " (by " + string + ")");
                return;
            }
            if (string2.equals("chat")) {
                String string8;
                String string9 = string8 = objectArray.length > 1 && objectArray[1] != null ? objectArray[1].toLowerCase() : "";
                if (string8.equals("on")) {
                    Cfg.chatEnabled = true;
                    Cmd.reply(object, "\u804a\u5929\u63d0\u793a\u5df2\u5f00\u542f\uff08>=" + Cfg.chatMinMs + "ms\uff0c\u51b7\u5374 " + Cfg.chatCooldownMs + "ms\uff09");
                } else if (string8.equals("off")) {
                    Cfg.chatEnabled = false;
                    Cmd.reply(object, "\u804a\u5929\u63d0\u793a\u5df2\u5173\u95ed\uff08\u65e5\u5fd7\u7167\u5e38\u8bb0\u5f55\uff09");
                } else {
                    Cmd.reply(object, "chat=" + Cfg.chatEnabled + "  chatMinMs=" + Cfg.chatMinMs + "  cooldown=" + Cfg.chatCooldownMs + "ms");
                }
                return;
            }
            if (string2.equals("chatmin")) {
                try {
                    int n = Integer.parseInt(objectArray[1].trim());
                    if (n < 50) {
                        n = 50;
                    }
                    if (n > 5000) {
                        n = 5000;
                    }
                    Cfg.chatMinMs = n;
                    Cmd.reply(object, "\u804a\u5929\u63d0\u793a\u95e8\u69db\u5df2\u6539\u4e3a " + n + "ms");
                }
                catch (Throwable throwable) {
                    Cmd.reply(object, "\u7528\u6cd5: /" + Cmd.activeName + " chatmin <50-5000>");
                }
                return;
            }
            if (string2.equals("report")) {
                boolean bl = objectArray.length > 1 && "all".equalsIgnoreCase(String.valueOf(objectArray[1]));
                FrameSpike.reportAsync(bl
                        ? "\u00a7a\u62a5\u544a\u5df2\u751f\u6210\uff08\u6574\u4efd\u65e5\u5fd7\uff09\u00a7r  \u70b9\u4e00\u4e0b\u6253\u5f00: "
                        : "\u00a7a\u62a5\u544a\u5df2\u751f\u6210\uff08\u672c\u6bb5\u8bb0\u5f55\uff09\u00a7r  \u70b9\u4e00\u4e0b\u6253\u5f00: ", bl);
                Cmd.reply(object, "\u6b63\u5728\u540e\u53f0\u751f\u6210\u62a5\u544a\uff08\u7ea6 1 \u79d2\uff09\uff0c\u5b8c\u6210\u540e\u4f1a\u5728\u804a\u5929\u91cc\u7ed9\u51fa\u8def\u5f84");
                return;
            }
            if (string2.equals("serve")) {
                String string10;
                String string11;
                String string12 = string11 = objectArray.length > 1 && objectArray[1] != null ? objectArray[1].trim().toLowerCase() : "";
                if (string11.equals("off") || string11.equals("stop")) {
                    Report.stopServer();
                    Cmd.reply(object, "\u62a5\u544a\u670d\u52a1\u5df2\u5173\u95ed");
                    return;
                }
                int n = 8731;
                if (string11.length() > 0) {
                    try {
                        n = Integer.parseInt(string11);
                    }
                    catch (Throwable throwable) {
                        n = 8731;
                    }
                }
                if (n < 1024 || n > 65535) {
                    n = 8731;
                }
                if ((string10 = Report.startServer(n, new File(Cfg.logFile))) == null) {
                    Cmd.reply(object, "\u8d77\u670d\u52a1\u5931\u8d25\uff08\u7aef\u53e3\u88ab\u5360\uff1f\u6362\u4e2a\u7aef\u53e3: /" + Cmd.activeName + " serve 8732\uff09");
                } else {
                    Cmd.reply(object, "\u00a7a\u62a5\u544a\u670d\u52a1\u5df2\u542f\u52a8\u00a7r  " + string10);
                    Cmd.reply(object, "\u9875\u9762\u6bcf\u6b21\u5237\u65b0\u90fd\u91cd\u65b0\u8bfb\u65e5\u5fd7\uff08\u7b49\u4e8e\u00a7f\u6d3b\u7684\u00a7r\uff09\uff1b\u5173\u95ed: /" + Cmd.activeName + " serve off");
                }
                return;
            }
            if (string2.equals("version")) {
                String[] stringArrayV = FrameSpike.versionLines();
                for (int i = 0; i < stringArrayV.length; ++i) {
                    Cmd.reply(object, stringArrayV[i]);
                }
                return;
            }
            if (string2.equals("config")) {
                /* /fs config / list     每项一行列出全部可配置项与当前值
                 * /fs config <键> <值>  设置（热生效，不写盘）
                 * /fs config save       把当前值写回 ini */
                boolean listForm = objectArray.length >= 2 && objectArray[1] != null
                        && objectArray[1].equalsIgnoreCase("list");
                if (objectArray.length < 2 || objectArray[1] == null || objectArray[1].length() == 0 || listForm) {
                    Cmd.reply(object, "可配置项（当前值）：");
                    Cmd.reply(object, "  threshold  = " + Cfg.stallThresholdMs + "ms   停顿判定阈值（20-5000）");
                    Cmd.reply(object, "  chatmin    = " + Cfg.chatMinMs + "ms   聊天提示门槛（50-5000）");
                    Cmd.reply(object, "  chatcool   = " + Cfg.chatCooldownMs + "ms   聊天冷却");
                    Cmd.reply(object, "  maxdumps   = " + Cfg.maxDumps + "   单次会话最多抓多少次");
                    Cmd.reply(object, "  stackdepth = " + (Cfg.stackDepth <= 0 ? "不截断" : Cfg.stackDepth + " 层") + "   抓多少层栈");
                    Cmd.reply(object, "  cpusec     = " + Cfg.cpuSampleSec + "s   CPU 表间隔");
                    Cmd.reply(object, "  cputop     = " + Cfg.cpuTopN + "   CPU 表前 N 行进聊天");
                    Cmd.reply(object, "  autoreport = " + Cfg.autoReport + "   /fs stop 时自动出报告");
                    Cmd.reply(object, "设置: " + string + " config <键> <值>    保存: " + string + " config save");
                    return;
                }
                String k = objectArray[1] == null ? "" : objectArray[1].toLowerCase();
                if (k.equals("save")) {
                    Cfg.save();
                    Cmd.reply(object, "配置已写回 ini: " + Cfg.iniPath());
                    return;
                }
                String v = objectArray.length > 2 && objectArray[2] != null ? objectArray[2].trim() : "";
                if (v.length() == 0) {
                    Cmd.reply(object, "缺少值: " + string + " config " + k + " <值>");
                    return;
                }
                if (k.equals("threshold")) {
                    int n = Integer.parseInt(v);
                    if (n < 20) n = 20;
                    if (n > 5000) n = 5000;
                    Cfg.stallThresholdMs = n;
                    Cmd.reply(object, "threshold=" + Cfg.stallThresholdMs + "ms");
                    return;
                }
                if (k.equals("chatmin")) {
                    int n = Integer.parseInt(v);
                    if (n < 50) n = 50;
                    if (n > 5000) n = 5000;
                    Cfg.chatMinMs = n;
                    Cmd.reply(object, "chatmin=" + Cfg.chatMinMs + "ms");
                    return;
                }
                if (k.equals("chatcool")) {
                    int n = Integer.parseInt(v);
                    if (n < 0) n = 0;
                    Cfg.chatCooldownMs = n;
                    Cmd.reply(object, "chatcool=" + Cfg.chatCooldownMs + "ms");
                    return;
                }
                if (k.equals("maxdumps")) {
                    int n = Integer.parseInt(v);
                    if (n < 1) n = 1;
                    Cfg.maxDumps = n;
                    Cmd.reply(object, "maxdumps=" + Cfg.maxDumps + "（单次会话最多抓多少次）");
                    return;
                }
                if (k.equals("stackdepth")) {
                    int n = Integer.parseInt(v);
                    Cfg.stackDepth = n;
                    Cmd.reply(object, "stackdepth=" + (n <= 0 ? "不截断" : n + " 层"));
                    return;
                }
                if (k.equals("cpusec")) {
                    int n = Integer.parseInt(v);
                    if (n < 5) n = 5;
                    Cfg.cpuSampleSec = n;
                    Cmd.reply(object, "cpusec=" + Cfg.cpuSampleSec + "s（CPU 表间隔）");
                    return;
                }
                if (k.equals("cputop")) {
                    int n = Integer.parseInt(v);
                    if (n < 1) n = 1;
                    Cfg.cpuTopN = n;
                    Cmd.reply(object, "cputop=" + Cfg.cpuTopN + "（CPU 表前 N 行进聊天）");
                    return;
                }
                if (k.equals("autoreport")) {
                    Cfg.autoReport = v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true") || v.equals("1");
                    Cmd.reply(object, "autoreport=" + Cfg.autoReport + "（/fs stop 时自动出报告）");
                    return;
                }
                Cmd.reply(object, "未知键: " + k + "（可用: threshold chatmin chatcool maxdumps stackdepth cpusec cputop autoreport）");
                return;
            }
            if (string2.equals("reload")) {
                lastIniMs = 1L;   // 强制 checkIni 走重读分支（文件的 mtime 是大数，必 != 1）
                checkIni();
                Cmd.reply(object, "ini 已重读: threshold=" + Cfg.stallThresholdMs + "ms  chatmin=" + Cfg.chatMinMs
                        + "  maxdumps=" + Cfg.maxDumps + "  chatcool=" + Cfg.chatCooldownMs);
                return;
            }
            if (string2.equals("help")) {
                String[] stringArray4 = objectArray.length > 1 && objectArray[1] != null && objectArray[1].length() > 0 ? FrameSpike.detailLines(((String)objectArray[1]).toLowerCase()) : FrameSpike.helpLines();
                for (int i = 0; i < stringArray4.length; ++i) {
                    Cmd.reply(object, stringArray4[i]);
                }
                return;
            }
            if (string2.length() > 0) {
                Cmd.reply(object, "\u672a\u77e5\u5b50\u547d\u4ee4 \"" + string2 + "\"\uff08\u6536\u5230\u7684\u53c2\u6570: " + Arrays.toString(objectArray) + "\uff09");
                for (int i = 0; i < 3; ++i) {
                    Cmd.reply(object, FrameSpike.helpLines()[i]);
                }
                Cmd.reply(object, "\u5168\u90e8\u547d\u4ee4: /" + Cmd.activeName + " help");
                return;
            }
            String[] stringArray5 = FrameSpike.quickLines();
            for (int i = 0; i < stringArray5.length; ++i) {
                Cmd.reply(object, stringArray5[i]);
            }
        }
        catch (Throwable throwable) {
            Cmd.reply(object, "\u6307\u4ee4\u6267\u884c\u51fa\u9519: " + throwable);
            FrameSpike.note("[cmd] \u51fa\u9519: " + throwable);
        }
    }

    private static String summaryText() {
        long l = (System.nanoTime() - segStartNs) / 1000000L;
        double d = Math.max(0.001, (double)l / 1000.0);
        long l2 = stalls.get() - segStall0;
        long l3 = frames.get() - segFrame0;
        long l4 = ticks.get() - segTick0;
        long l5 = glSkipped.get() - segGl0;
        StringBuilder stringBuilder = new StringBuilder(512);
        stringBuilder.append("\u672c\u6bb5\u7edf\u8ba1: \u65f6\u957f=").append(FrameSpike.fmtDur(l));
        stringBuilder.append("\n\u5e27\u6570=").append(l3).append("\uff08\u5e73\u5747 ").append(String.format("%.1f", (double)l3 / d)).append(" fps\uff09").append("  tick=").append(l4).append("\uff08\u5e73\u5747 ").append(String.format("%.1f", (double)l4 / d)).append(" tps\uff09");
        stringBuilder.append("\n\u505c\u987f\u6b21\u6570=").append(l2).append("  \u6700\u957f=").append(maxStallMs).append("ms").append("  \u5e73\u5747\u6bcf ").append(l2 == 0L ? "n/a" : String.format("%.0f", (double)l3 / (double)l2)).append(" \u5e27\u4e00\u6b21");
        stringBuilder.append("\n\u5206\u6863: <100ms=").append(segBucket[0]).append("  100-199ms=").append(segBucket[1]).append("  200-499ms=").append(segBucket[2]).append("  >=500ms=").append(segBucket[3]);
        stringBuilder.append("\nglFinishSkipped=").append(l5).append("  glFinishMode=").append(Cfg.glFinishMode).append("  glFinishSites=").append(glFinishSites);
        return stringBuilder.toString();
    }

    private static String fmtDur(long l) {
        long l2 = l / 1000L;
        return String.format("%d:%02d:%02d", l2 / 3600L, l2 % 3600L / 60L, l2 % 60L);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static List<String> tailStalls(int n) {
        ArrayList<String> arrayList = new ArrayList<String>();
        /* 优先用内存环形表：日志不再受"最后 32KB 里有没有 STALL 头"这种巧合影响 */
        java.util.ArrayDeque<String> arrayDeque = recentStalls;
        synchronized (arrayDeque) {
            java.util.Iterator<String> iterator = recentStalls.descendingIterator();
            while (iterator.hasNext() && arrayList.size() < n) {
                arrayList.add(iterator.next());
            }
        }
        if (!arrayList.isEmpty()) {
            Collections.reverse(arrayList);
            return arrayList;
        }
        try {
            File file = new File(Cfg.logFile);
            if (!file.isFile()) {
                return arrayList;
            }
            long l = file.length();
            int n2 = (int)Math.min(l, 262144L);
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");){
                randomAccessFile.seek(l - (long)n2);
                byte[] byArray = new byte[n2];
                randomAccessFile.readFully(byArray);
                String[] stringArray = new String(byArray, "UTF-8").split("\\r?\\n");
                for (int i = stringArray.length - 1; i >= 0 && arrayList.size() < n; --i) {
                    String string = stringArray[i];
                    /* 0.3.0 起日志头叫 STALL-DETECT（更早的版本才是 "  STALL "），都要认 */
                    if (!string.contains("STALL-DETECT") && !string.contains("STALL-END")
                            && !string.contains("  STALL ") && !string.contains("SNAPSHOT ")) continue;
                    arrayList.add(string.trim());
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        Collections.reverse(arrayList);
        return arrayList;
    }

    private static File crashStateFile() {
        File file = new File(Cfg.logFile).getParentFile();
        return new File(file == null ? new File(".") : file, "framespike.state");
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void loadCrashState() {
        try {
            String string = FrameSpike.readFile(FrameSpike.crashStateFile(), 65536);
            if (string == null) {
                return;
            }
            Set<String> set = seenCrashes;
            synchronized (set) {
                for (String string2 : string.split("\\r?\\n")) {
                    String string3 = string2.trim();
                    if (string3.length() <= 0) continue;
                    seenCrashes.add(string3);
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void saveCrashState() {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            Set<String> set = seenCrashes;
            synchronized (set) {
                for (String string : seenCrashes) {
                    stringBuilder.append(string).append('\n');
                }
            }
            FrameSpike.writeFile(FrameSpike.crashStateFile(), stringBuilder.toString());
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static File[] crashDirs() {
        ArrayList<File> arrayList = new ArrayList<File>();
        try {
            File file;
            File file2 = new File(Cfg.logFile).getParentFile();
            File file3 = file = file2 == null ? null : file2.getParentFile();
            if (file != null) {
                arrayList.add(new File(file, "crash-reports"));
            }
            arrayList.add(new File("crash-reports"));
            String string = System.getProperty("user.home", ".");
            arrayList.add(new File(string, ".lunarclient/offline/multiplayer/crash-reports"));
            arrayList.add(new File(string, ".lunarclient/offline/singleplayer/crash-reports"));
            String string2 = System.getenv("APPDATA");
            arrayList.add(new File(string2 == null || string2.length() == 0 ? string : string2, ".minecraft/crash-reports"));
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return arrayList.toArray(new File[arrayList.size()]);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void scanCrashes(boolean bl) {
        try {
            int n;
            boolean bl2 = false;
            ArrayList<String> arrayList = new ArrayList<String>();
            File[] fileArray = FrameSpike.crashDirs();
            for (n = 0; n < fileArray.length; ++n) {
                File[] fileArray2 = fileArray[n].listFiles();
                if (fileArray2 == null) continue;
                for (int i = 0; i < fileArray2.length; ++i) {
                    boolean bl3;
                    String string = fileArray2[i].getName();
                    if (!string.startsWith("crash-") || !string.endsWith(".txt")) continue;
                    String string2 = fileArray2[i].getAbsolutePath();
                    Set<String> set = seenCrashes;
                    synchronized (set) {
                        bl3 = seenCrashes.add(string2);
                    }
                    if (!bl3) continue;
                    bl2 = true;
                    arrayList.add(string2);
                }
            }
            for (n = 0; n < arrayList.size(); ++n) {
                FrameSpike.reportCrash(new File((String)arrayList.get(n)), bl);
            }
            if (bl2) {
                FrameSpike.saveCrashState();
            }
        }
        catch (Throwable throwable) {
            FrameSpike.note("[crash] \u626b\u63cf\u5931\u8d25: " + throwable);
        }
    }

    private static void reportCrash(File file, boolean bl) {
        try {
            int n;
            CharSequence charSequence;
            String string;
            String string2;
            String string3 = FrameSpike.readFile(file, 65536);
            if (string3 == null || string3.length() == 0) {
                return;
            }
            String string4 = "";
            String string5 = "";
            ArrayList<String> arrayList = new ArrayList<String>();
            String[] stringArray = string3.split("\\r?\\n");
            for (int i = 0; i < stringArray.length; ++i) {
                string2 = stringArray[i];
                if (string4.length() == 0 && string2.startsWith("Description: ")) {
                    string4 = string2.substring("Description: ".length()).trim();
                    continue;
                }
                if (string5.length() == 0 && string2.startsWith("Time: ")) {
                    string5 = string2.substring("Time: ".length()).trim();
                    continue;
                }
                string = string2.trim();
                if (!string.startsWith("at ") || arrayList.size() >= 24) continue;
                arrayList.add(string.substring(3).trim());
            }
            StringBuilder stringBuilder = new StringBuilder(256);
            string2 = "";
            string = "";
            for (int i = 0; i < arrayList.size() && i < 6; ++i) {
                int n2 = ((String)arrayList.get(i)).indexOf(40);
                charSequence = n2 > 0 ? ((String)arrayList.get(i)).substring(0, n2) : (String)arrayList.get(i);
                n = ((String)charSequence).lastIndexOf(46);
                if (n <= 0) continue;
                stringBuilder.append((String)charSequence).append(' ');
                if (string2.length() != 0) continue;
                string2 = ((String)charSequence).substring(0, n);
                string = ((String)charSequence).substring(n + 1);
            }
            String string6 = stringBuilder.length() > 0 ? FrameSpike.classifyFromText(stringBuilder.toString(), string2, string) : "\u672a\u77e5";
            String string7 = string4.length() > 60 ? string4.substring(0, 60) + "\u2026" : string4;
            charSequence = new StringBuilder(4096);
            ((StringBuilder)charSequence).append('\n').append(FrameSpike.ts()).append("  === CRASH ===  ").append(bl ? "\u4e0a\u6b21\u4f1a\u8bdd" : "\u672c\u6b21\u4f1a\u8bdd").append("  file=").append(file.getName()).append("\n  time=").append(string5.length() > 0 ? string5 : FrameSpike.ts()).append("\n  desc=").append(string4.length() > 0 ? string4 : "(\u6ca1\u8bfb\u5230 Description)").append("\n  reason=").append(string6).append("\n--- \u5d29\u6e83\u6808\u524d ").append(arrayList.size()).append(" \u5e27 ---");
            for (n = 0; n < arrayList.size(); ++n) {
                ((StringBuilder)charSequence).append("\n  at ").append((String)arrayList.get(n));
            }
            FrameSpike.log(((StringBuilder)charSequence).toString());
            if (Cfg.chatEnabled) {
                pendingChat = "\u00a7c[FS] \u68c0\u6d4b\u5230" + (bl ? "\u4e0a\u6b21" : "\u672c\u6b21") + "\u5d29\u6e83\u00a7r  " + string7 + "  \u00a77" + string6 + "\u00a7r";
            }
        }
        catch (Throwable throwable) {
            FrameSpike.note("[crash] \u89e3\u6790\u5931\u8d25: " + throwable);
        }
    }

    private static void checkIni() {
        try {
            File file = new File(Cfg.iniPath());
            if (!file.isFile()) {
                return;
            }
            long l = file.lastModified();
            if (lastIniMs == 0L) {
                lastIniMs = l;
                return;
            }
            if (l == lastIniMs) {
                return;
            }
            lastIniMs = l;
            int n = Cfg.stallThresholdMs;
            int n2 = Cfg.chatMinMs;
            int n3 = Cfg.chatCooldownMs;
            int n4 = Cfg.cpuSampleSec;
            int n5 = Cfg.maxDumps;
            int n6 = Cfg.stackDepth;
            boolean bl = Cfg.chatEnabled;
            boolean bl2 = Cfg.enabled;
            String string = Cfg.logFile;
            Cfg.load();
            StringBuilder stringBuilder = new StringBuilder();
            if (Cfg.stallThresholdMs != n) {
                stringBuilder.append(" threshold ").append(n).append("->").append(Cfg.stallThresholdMs);
            }
            if (Cfg.chatEnabled != bl) {
                stringBuilder.append(" chat ").append(bl).append("->").append(Cfg.chatEnabled);
            }
            if (Cfg.chatMinMs != n2) {
                stringBuilder.append(" chatMinMs ").append(n2).append("->").append(Cfg.chatMinMs);
            }
            if (Cfg.chatCooldownMs != n3) {
                stringBuilder.append(" chatCooldownMs ").append(n3).append("->").append(Cfg.chatCooldownMs);
            }
            if (Cfg.cpuSampleSec != n4) {
                stringBuilder.append(" cpuSampleSec ").append(n4).append("->").append(Cfg.cpuSampleSec);
            }
            if (Cfg.maxDumps != n5) {
                stringBuilder.append(" maxDumps ").append(n5).append("->").append(Cfg.maxDumps);
            }
            if (Cfg.stackDepth != n6) {
                stringBuilder.append(" stackDepth ").append(n6).append("->").append(Cfg.stackDepth);
            }
            if (Cfg.enabled != bl2) {
                recording = Cfg.enabled;
                stringBuilder.append(" enabled ").append(bl2).append("->").append(Cfg.enabled);
            }
            if (!Cfg.logFile.equals(string)) {
                FrameSpike.resetLogWriter();
                stringBuilder.append(" logFile -> ").append(Cfg.logFile);
            }
            if (stringBuilder.length() > 0) {
                FrameSpike.note("[ini] \u70ed\u91cd\u8f7d:" + stringBuilder.toString());
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /** 重新打开日志文件（ini 热重载 / coremod 拿到 gameDir 后换路径用） */
    public static void resetLogWriter() {
        Object object = logLock;
        synchronized (object) {
            if (out != null) {
                try {
                    out.close();
                }
                catch (Throwable throwable) {
                    // empty catch block
                }
                out = null;
            }
        }
    }

    /** 停顿那一刻所在的世界（维度/坐标）。尽力而为：反射失败就静默跳过，成功后缓存 */
    private static String worldContext() {
        try {
            if (worldCtx.length() > 0) return worldCtx;
            long now = System.currentTimeMillis();
            if (now - lastWorldTry < 30000L) return worldCtx;
            lastWorldTry = now;
            Object player = Cmd.playerObject();
            if (player == null) return worldCtx;

            /* 坐标：找一个「无参 + 返回值正好 3 个 int 字段」的方法（1.8.9 是 getPosition 返回 BlockPos） */
            int x = Integer.MIN_VALUE, y = 0, z = 0;
            Method best = null;
            try {
                Method[] ms = player.getClass().getMethods();
                for (int i = 0; i < ms.length && best == null; i++) {
                    Method m = ms[i];
                    if (m.getParameterTypes().length != 0) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt.isPrimitive() || rt == String.class) continue;
                    java.lang.reflect.Field[] fs = rt.getDeclaredFields();
                    if (fs.length != 3) continue;
                    boolean allInt = true;
                    for (int j = 0; j < 3; j++) if (fs[j].getType() != int.class) { allInt = false; break; }
                    if (allInt) best = m;
                }
                if (best != null) {
                    Object pos = best.invoke(player);
                    java.lang.reflect.Field[] fs = best.getReturnType().getDeclaredFields();
                    int[] v = new int[3];
                    for (int j = 0; j < 3; j++) { fs[j].setAccessible(true); v[j] = fs[j].getInt(pos); }
                    if (Math.abs(v[0]) <= 30000000 && Math.abs(v[1]) <= 1000 && Math.abs(v[2]) <= 30000000) {
                        x = v[0]; y = v[1]; z = v[2];
                    }
                }
            } catch (Throwable ignored) { }

            /* 维度：从 player 往上找 World 对象，再找它身上 WorldProvider 类型的字段的 int 值（-1/0/1） */
            int dim = Integer.MIN_VALUE;
            Class<?> c = player.getClass();
            outer:
            for (int level = 0; level < 4 && c != null; level++, c = c.getSuperclass()) {
                java.lang.reflect.Field[] pfs = c.getDeclaredFields();
                for (int i = 0; i < pfs.length; i++) {
                    java.lang.reflect.Field f = pfs[i];
                    if (f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object o = f.get(player);
                        if (o == null) continue;
                        java.lang.reflect.Field[] ofs = o.getClass().getDeclaredFields();
                        for (int j = 0; j < ofs.length; j++) {
                            if (!ofs[j].getType().getName().endsWith("WorldProvider")) continue;
                            ofs[j].setAccessible(true);
                            Object prov = ofs[j].get(o);
                            if (prov == null) continue;
                            java.lang.reflect.Field[] pfs2 = prov.getClass().getDeclaredFields();
                            for (int k = 0; k < pfs2.length; k++) {
                                if (pfs2[k].getType() != int.class) continue;
                                pfs2[k].setAccessible(true);
                                int v = pfs2[k].getInt(prov);
                                if (v == -1 || v == 0 || v == 1) { dim = v; break outer; }
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            }

            if (dim == Integer.MIN_VALUE && x == Integer.MIN_VALUE) return worldCtx;
            String dimName = dim == 0 ? "主世界" : dim == -1 ? "下界" : dim == 1 ? "末地"
                    : (dim == Integer.MIN_VALUE ? "?" : "dim:" + dim);
            worldCtx = "dim=" + dimName + (x != Integer.MIN_VALUE
                    ? " x=" + x + " y=" + y + " z=" + z : "");
            return worldCtx;
        } catch (Throwable t) {
            return worldCtx;
        }
    }

    private static String ts() {
        try {
            return TS.format(new Date());
        }
        catch (Throwable throwable) {
            return "??:??:??.???";
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static String readFile(File file, int n) {
        String string;
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(file);
            byte[] byArray = new byte[n];
            int n2 = fileInputStream.read(byArray);
            string = n2 <= 0 ? "" : new String(byArray, 0, n2, "UTF-8");
            if (fileInputStream == null) return string;
        }
        catch (Throwable throwable) {
            try {
                String string3 = null;
                return string3;
            }
            catch (Throwable throwable2) {
                throw throwable2;
            }
            finally {
                if (fileInputStream != null) {
                    try {
                        fileInputStream.close();
                    }
                    catch (Throwable throwable3) {}
                }
            }
        }
        try {
            fileInputStream.close();
            return string;
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return string;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void writeFile(File file, String string) {
        OutputStreamWriter outputStreamWriter = null;
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            outputStreamWriter = new OutputStreamWriter((OutputStream)new FileOutputStream(file, false), "UTF-8");
            outputStreamWriter.write(string);
        }
        catch (Throwable throwable) {
        }
        finally {
            if (outputStreamWriter != null) {
                try {
                    outputStreamWriter.close();
                }
                catch (Throwable throwable) {}
            }
        }
    }

    public static void note(String string) {
        FrameSpike.log(string);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static void log(String string) {
        Object object = logLock;
        synchronized (object) {
            try {
                if (out == null) {
                    File file;
                    File file2;
                    String string2 = Cfg.logFile;
                    if (string2 == null || string2.length() == 0) {
                        string2 = "frame-spikes.log";
                    }
                    if ((file2 = (file = new File(string2)).getParentFile()) != null && !file2.isDirectory()) {
                        file2.mkdirs();
                    }
                    out = new PrintWriter(new OutputStreamWriter((OutputStream)new FileOutputStream(file, true), "UTF-8"));
                }
                out.println(string);
                out.flush();
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
    }

    static {
        running = false;
        pendingStall = false;
        pendingFromNs = 0L;
        pendingDetectMs = 0L;
        pendingLabel = "?";
        pendingReason = "\u672a\u77e5";
        lastChatMs = 0L;
        seenCrashes = new HashSet<String>();
        lastCrashScan = 0L;
        gcEvents = new ArrayList<GcEvent>();
        fpsHist = new ArrayList<Double>();
        lastFpsAlertMs = 0L;
        lastIniMs = 0L;
        wdTick = 0;
        bootMs = 0L;
        worldCtx = "";
        lastWorldTry = 0L;
        recording = true;
        segStartNs = System.nanoTime();
        segStall0 = 0L;
        segFrame0 = 0L;
        segTick0 = 0L;
        segGl0 = 0L;
        maxStallMs = 0L;
        segBucket = new long[4];
        glPassThrough = true;
        glHandleTried = false;
        glState = "n/a (mode=off)";
        glFinishSites = -1;
        cpuSnapshot = Collections.emptyMap();
        cpuSnapshotNs = 0L;
        cpuRequest = new AtomicBoolean(false);
        sampleFrame0 = 0L;
        sampleTick0 = 0L;
        ctrlChecked = false;
        ctrlPollTick = 0;
        logLock = new Object();
        TS = new SimpleDateFormat("HH:mm:ss.SSS");
        SUBCOMMANDS = new String[]{"start", "stop", "status", "dump", "mark", "tail", "threshold", "config", "reload", "cpu", "glfinish", "chat", "chatmin", "report", "serve", "version", "help"};
        tabCalls = new AtomicLong();
    }

    private static final class GcEvent {
        final long endMs;
        final long durMs;
        final String name;

        GcEvent(long l, long l2, String string) {
            this.endMs = l;
            this.durMs = l2;
            this.name = string;
        }
    }
}
