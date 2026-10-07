/*
 * FrameSpike - Minecraft 1.8.9 / Lunar Client 帧暂停取证 coremod
 * Author: Trusler
 */
package framespike;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Properties;

/** 配置：优先读取 -Dframespike.ini，其次加载器提供的 gameDir，兜底相对路径 ./framespike/framespike.ini */
public final class Cfg {

    public static volatile boolean enabled = true;
    public static volatile int stallThresholdMs = 60;
    public static volatile int maxDumps = 2000;
    /** 抓多少层栈；<=0 表示不截断（日志会明显变大） */
    public static volatile int stackDepth = 96;
    /** off = 完全不碰字节码；proxy = 把每帧的 GL11.glFinish 换成可游戏内热切的代理 */
    public static volatile String glFinishMode = "off";
    public static volatile int cpuSampleSec = 60;
    public static volatile int cpuTopN = 8;
    public static volatile String logFile = "";
    /** 游戏版本段（只用于标题/署名显示）。加载器在 load() 之前设置。 */
    public static volatile String mcVersion = "1.8.9";
    public static volatile String cmdName = "fs";
    public static volatile String cmdAliases = "framespike,fspike";
    public static volatile String ctrlFile = "";
    /** /fs stop 时自动生成一份 HTML 分析报告 */
    public static volatile boolean autoReport = true;
    /** 报告输出目录，留空 = 与日志同目录 */
    public static volatile String reportDir = "";
    /** 卡顿时在聊天里提示 */
    public static volatile boolean chatEnabled = true;
    public static volatile int chatMinMs = 150;
    public static volatile int chatCooldownMs = 1000;

    public static boolean proxyGlFinish() {
        return "proxy".equalsIgnoreCase(glFinishMode);
    }

    private static String iniPath = "";
    /** 存档根目录。null = 用 Lunar 1.8 的老路径（1.8.9 行为完全不变） */
    private static volatile File baseDir = null;

    /**
     * 加载器（Fabric / NeoForge）在 Cfg.load() 之前调用，传入游戏目录。
     * 此时 ini / 日志 / 报告都落在 <gameDir>/framespike/ 下。
     * 不调用则兜底相对路径 ./framespike/（进程工作目录）。
     */
    public static void setBaseDir(File d) { baseDir = d; }

    public static File baseDir() { return baseDir; }

    private Cfg() {}

    public static String iniPath() { return iniPath; }

    public static void load() {
        try {
            File ini = locate();
            iniPath = ini.getAbsolutePath();
            Properties p = new Properties();
            if (ini.isFile()) {
                FileInputStream in = new FileInputStream(ini);
                try { p.load(in); } finally { in.close(); }
            } else {
                writeDefault(ini);
            }
            enabled = bool(p, "enabled", enabled);
            stallThresholdMs = intv(p, "stallThresholdMs", stallThresholdMs);
            maxDumps = intv(p, "maxDumps", maxDumps);
            stackDepth = intv(p, "stackDepth", stackDepth);
            glFinishMode = str(p, "glFinishMode", glFinishMode);
            cpuSampleSec = intv(p, "cpuSampleSec", cpuSampleSec);
            cpuTopN = intv(p, "cpuTopN", cpuTopN);
            cmdName = str(p, "commandName", cmdName);
            cmdAliases = str(p, "commandAliases", cmdAliases);
            chatEnabled = bool(p, "chatNotify", chatEnabled);
            chatMinMs = intv(p, "chatMinMs", chatMinMs);
            chatCooldownMs = intv(p, "chatCooldownMs", chatCooldownMs);
            autoReport = bool(p, "autoReport", autoReport);
            reportDir = p.getProperty("reportDir", reportDir);
            logFile = p.getProperty("logFile", defaultLog(ini));
            ctrlFile = p.getProperty("ctrlFile", defaultCtrl(ini));
            if (logFile == null || logFile.trim().length() == 0) logFile = defaultLog(ini);
            if (ctrlFile == null || ctrlFile.trim().length() == 0) ctrlFile = defaultCtrl(ini);
        } catch (Throwable t) {
            logFile = defaultLog(null);
        }
    }

    private static File locate() {
        String s = System.getProperty("framespike.ini");
        if (s != null && s.length() > 0) return new File(s);
        File d = baseDir;
        if (d != null) {
            File a2 = new File(new File(d, "framespike"), "framespike.ini");
            if (a2.getParentFile() != null) a2.getParentFile().mkdirs();
            return a2;
        }
        /* 兜底：相对路径（进程工作目录）。Lunar 1.8.9 走不到这里 —— coremod 的
           injectData 会用 FML 的 mcLocation 设 baseDir。
           之前这里写死过一个 Lunar 专属目录，1.12.2 的日志会被写进去（实测踩到）；
           现在兜底一律相对路径，要发布给别人用就不能有任何个人路径。 */
        File rel = new File(new File(".", "framespike"), "framespike.ini");
        if (rel.getParentFile() != null) rel.getParentFile().mkdirs();
        return rel;
    }

    private static String defaultLog(File ini) {
        if (ini == null) return new File("frame-spikes.log").getAbsolutePath();
        File dir = ini.getParentFile();
        File f = new File(dir == null ? new File(".") : dir, "frame-spikes.log");
        return f.getAbsolutePath();
    }

    private static String defaultCtrl(File ini) {
        if (ini == null) return new File("frame-spikes.ctrl").getAbsolutePath();
        File dir = ini.getParentFile();
        File f = new File(dir == null ? new File(".") : dir, "frame-spikes.ctrl");
        return f.getAbsolutePath();
    }

    /** 把当前值写回 ini（/fs config save 用）。logFile 不写（从 ini 位置派生） */
    public static void save() {
        try {
            File ini = locate();
            if (ini.getParentFile() != null) ini.getParentFile().mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(ini), "UTF-8");
            try {
                w.write("# FrameSpike coremod config (saved by /fs config save)\n");
                w.write("enabled=" + enabled + "\n");
                w.write("stallThresholdMs=" + stallThresholdMs + "\n");
                w.write("maxDumps=" + maxDumps + "\n");
                w.write("stackDepth=" + stackDepth + "\n");
                w.write("glFinishMode=" + glFinishMode + "\n");
                w.write("cpuSampleSec=" + cpuSampleSec + "\n");
                w.write("cpuTopN=" + cpuTopN + "\n");
                w.write("commandName=" + cmdName + "\n");
                w.write("commandAliases=" + cmdAliases + "\n");
                w.write("chatNotify=" + chatEnabled + "\n");
                w.write("chatMinMs=" + chatMinMs + "\n");
                w.write("chatCooldownMs=" + chatCooldownMs + "\n");
                w.write("autoReport=" + autoReport + "\n");
                w.write("reportDir=" + (reportDir == null ? "" : reportDir) + "\n");
                w.write("logFile=\n");
            } finally {
                w.close();
            }
        } catch (Throwable t) {
        }
    }

    private static void writeDefault(File ini) {
        try {
            if (ini.getParentFile() != null) ini.getParentFile().mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(ini), "UTF-8");
            try {
                w.write("# FrameSpike coremod config\n");
                w.write("# enabled=0 可整体关闭（等价于删掉 mod）\n");
                w.write("enabled=true\n");
                w.write("# 距上一个检查点超过该毫秒数即判定为一次停顿并抓栈\n");
                w.write("stallThresholdMs=60\n");
                w.write("# 单次会话最多抓多少次（防止日志无限增长）\n");
                w.write("maxDumps=2000\n");
                w.write("# 抓多少层栈；0 = 不截断（日志会明显变大）\n");
                w.write("stackDepth=96\n");
                w.write("# glFinishMode=off   完全不碰游戏字节码（默认，零风险）\n");
                w.write("# glFinishMode=proxy 把 EntityRenderer 里每帧一次的 GL11.glFinish 换成代理，\n");
                w.write("#                    之后就能在游戏内用 /fs glfinish on|off 热切，不用重启。\n");
                w.write("#                    注意：改成 proxy 需要重启一次才生效。\n");
                w.write("glFinishMode=off\n");
                w.write("# 游戏内指令名与别名（逗号分隔）。重名会自动换名并在日志里记录。\n");
                w.write("commandName=fs\n");
                w.write("commandAliases=framespike,fspike\n");
                w.write("# 卡顿时在聊天里提示一行（含时长 + 原因简写）\n");
                w.write("chatNotify=true\n");
                w.write("# 只有 >= 这个毫秒数的停顿才提示（避免被小抖动刷屏）\n");
                w.write("chatMinMs=150\n");
                w.write("# 两条聊天提示之间至少间隔多少毫秒\n");
                w.write("chatCooldownMs=1000\n");
                w.write("# /fs stop 时自动生成一份 HTML 分析报告（火焰图/归因/停顿明细）\n");
                w.write("autoReport=true\n");
                w.write("# 报告输出目录，留空 = 与日志同目录\n");
                w.write("reportDir=\n");
                w.write("# 每多少秒输出一次全线程 CPU 占用表（补 spark 只看 Client thread 的盲区）\n");
                w.write("cpuSampleSec=60\n");
                w.write("cpuTopN=8\n");
                w.write("# 留空 = 与 ini 同目录的 frame-spikes.log\n");
                w.write("logFile=\n");
            } finally { w.close(); }
        } catch (Throwable ignored) { }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        v = v.trim();
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    private static int intv(Properties p, String k, int d) {
        try {
            String v = p.getProperty(k);
            if (v == null || v.trim().length() == 0) return d;
            return Integer.parseInt(v.trim());
        } catch (Throwable t) { return d; }
    }

    private static String str(Properties p, String k, String d) {
        String v = p.getProperty(k);
        if (v == null) return d;
        v = v.trim();
        return v.length() == 0 ? d : v;
    }

    public static String summary() {
        return "enabled=" + enabled + " threshold=" + stallThresholdMs + "ms maxDumps=" + maxDumps
                + " mc=" + mcVersion
                + " glFinishMode=" + glFinishMode + " cpuSampleSec=" + cpuSampleSec
                + " cmd=/" + cmdName + " aliases=" + cmdAliases
                + " log=" + logFile + " ini=" + iniPath;
    }
}
