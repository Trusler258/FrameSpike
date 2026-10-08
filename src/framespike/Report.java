/*
 * FrameSpike - Minecraft 1.8.9 / Lunar Client 帧暂停取证 coremod
 * Author: Trusler
 */
package framespike;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 把 frame-spikes.log 变成一个自包含的分析网页（类似 spark 的查看器）。
 *
 * 两种用法：
 *   Report.write(...)      生成 report-<时间>.html，双击即可看，数据内嵌不需要服务器
 *   Report.startServer(...) 起一个本地 HTTP 服务，页面每次刷新都重新读日志（等于"活的"）
 *
 * 模板是 jar 里的资源 resources/framespike/report.html，用 __XXX__ 占位符注入。
 * 依赖上只用了 JDK（ServerSocket 手写极简 HTTP），没有第三方库。
 */
public final class Report {

    private static final String TPL = "/framespike/report.html";

    private Report() {}

    // ------------------------------------------------------------------ 对外接口

    /** 生成报告文件，返回生成的文件（失败返回 null） */
    public static File write(File logFile, File outDir) {
        return write(logFile, outDir, false);
    }

    public static File write(File logFile, File outDir, boolean wholeLog) {
        try {
            String log = readText(logFile);
            File dir = outDir != null ? outDir : logFile.getParentFile();
            if (dir == null) dir = new File(".");
            if (!dir.isDirectory()) dir.mkdirs();
            String name = "report-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".html";
            File out = new File(dir, name);
            writeText(out, buildHtml(log, logFile.getAbsolutePath(), wholeLog));
            return out;
        } catch (Throwable t) {
            FrameSpike.note("[report] 生成失败: " + t);
            return null;
        }
    }

    /** 纯函数：日志文本 -> 完整 HTML。便于离线测试。 */
    public static String buildHtml(String logText, String source) {
        return buildHtml(logText, source, false);
    }

    public static String buildHtml(String logText, String source, boolean wholeLog) {
        FrameSpike.probeGameVersion();
        String tpl = readResource();
        if (tpl == null) tpl = "<html><body><pre>模板缺失</pre></body></html>";
        return tpl.replace("__TITLE__", FrameSpike.MOD_NAME + " " + FrameSpike.MOD_CN + " · 卡顿报告")
                  .replace("__SOURCE__", esc(source))
                  .replace("__GENERATED__", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                  .replace("__VERSION__", FrameSpike.MOD_VERSION)
                  .replace("__MCVER__", FrameSpike.MC_VER)
                  .replace("__AUTHOR__", FrameSpike.MOD_AUTHOR)
                  .replace("__DATA__", buildJson(logText, wholeLog));
    }

    // ------------------------------------------------------------------ 本地服务

    private static volatile ServerSocket srvSock;
    private static volatile Thread srvThread;
    private static volatile int srvPort = 0;

    public static boolean serverRunning() { return srvSock != null && !srvSock.isClosed(); }
    public static int serverPort() { return srvPort; }

    public static String startServer(int port, final File logFile) {
        stopServer();
        try {
            final ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            srvSock = ss;
            srvPort = port;
            Thread t = new Thread(new Runnable() {
                public void run() { serveLoop(ss, logFile); }
            }, "FrameSpike-Http");
            t.setDaemon(true);
            t.start();
            srvThread = t;
            return "http://127.0.0.1:" + port + "/";
        } catch (Throwable e) {
            FrameSpike.note("[serve] 启动失败: " + e);
            stopServer();
            return null;
        }
    }

    public static void stopServer() {
        ServerSocket s = srvSock;
        srvSock = null;
        srvPort = 0;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static void serveLoop(ServerSocket ss, File logFile) {
        while (!ss.isClosed()) {
            Socket c = null;
            try {
                c = ss.accept();
                c.setSoTimeout(5000);
                // 只要请求头（本服务只在 localhost 给人看）
                InputStream in = c.getInputStream();
                byte[] buf = new byte[2048];
                int n = in.read(buf);
                String req = n > 0 ? new String(buf, 0, n, "ISO-8859-1") : "";
                String body;
                if (req.startsWith("GET /log") || req.startsWith("GET /log?")) {
                    body = "<html><head><meta charset='utf-8'></head><body><pre>"
                            + esc(readText(logFile)) + "</pre></body></html>";
                } else {
                    /* ?whole=1 时给全量（页面右上角可以切） */
                    boolean whole = req.indexOf("whole=1") >= 0;
                    body = buildHtml(readText(logFile), logFile.getAbsolutePath(), whole);
                }
                /* serve 出来的页面加自动刷新：serve 本来就每次请求重读日志，
                   配上 meta refresh 才算「实时」。直接打开本地文件不走这条路，
                   交互状态（展开的栈 / 火焰图焦点）不会被影响。 */
                int headAt = body.indexOf("<head>");
                if (headAt >= 0) {
                    body = body.substring(0, headAt + 6)
                            + "\n<meta http-equiv=\"refresh\" content=\"5\">"
                            + body.substring(headAt + 6);
                }
                byte[] b = body.getBytes("UTF-8");
                OutputStream out = c.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Cache-Control: no-store\r\nContent-Length: " + b.length
                        + "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
                out.write(b);
                out.flush();
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ 日志解析

    private static class Stall {
        String t = "";
        int detect;
        int total;
        /** 是否有真实的 STALL-END 结算。旧格式（0.2.0 之前）没有，total 只能等于检测值 */
        boolean ended;
        String since = "";
        String reason = "未知";
        /** 这次停顿是不是 GC（从 STALL-END 行的 gc= 来，新格式带 overlap：名字 重叠/总时长） */
        String gc = "";
        /** 多次栈采样统计（2026-10-08）：votes=4/5、majority 栈顶、置信度、来源 jar */
        String votes = "";
        String majority = "";
        String conf = "";
        String src = "";
        /** 模组原生写的墙钟时间戳（epoch ms）：t0 = 停顿起点，t1 = 停顿结束 */
        long t0, t1;
        /** 停顿那一刻的维度/坐标（模组尽力而为写的 ctx=） */
        String ctx = "";
        final List<String[]> timeline = new ArrayList<String[]>();
        final List<String> stack = new ArrayList<String>();
    }

    private static class Crash {
        String t = "";
        String file = "";
        String time = "";
        String desc = "";
        String reason = "";
    }

    private static class Node {
        final String name;
        long ms;
        int count;
        String color;
        final List<Node> kids = new ArrayList<Node>();
        Node(String name) { this.name = name; }
    }

    /** 判头行：注意日志行是以时间戳开头的（`HH:mm:ss.SSS  STALL-DETECT ...`），不能用 startsWith */
    private static boolean isHeader(String s) {
        if (s.indexOf("STALL-DETECT") >= 0) return true;
        if (s.indexOf("SNAPSHOT ") >= 0 && s.indexOf("ms  since=") > 0) return true;
        return s.indexOf("STALL ") >= 0 && s.indexOf("ms  since='") > 0;
    }

    private static final List<Crash> crashes = new ArrayList<Crash>();
    /** micro-stutter 事件（2026-10-08）：[stutter] 行解析出 [时间, maxMs, avgMs] */
    private static final List<String[]> stutters = new ArrayList<String[]>();

    /**
     * 报告默认只统计最后一段记录：RECORDING STARTED → RECORDING STOPPED。
     * 没见过记录标记（没手动 start 过）时，退回「最后一次会话」（banner 起）。
     * whole = true 时原样返回（/fs report all）。
     */
    private static String segmentText(String text, boolean whole) {
        if (whole || text == null) return text;
        try {
            int a = text.lastIndexOf("RECORDING STARTED");
            if (a < 0) {
                int b = text.lastIndexOf("=== FrameSpike started ===");
                if (b < 0) return text;
                int ls0 = text.lastIndexOf('\n', b);
                return text.substring(ls0 < 0 ? b : ls0);
            }
            int ls = text.lastIndexOf('\n', a);
            int start = ls < 0 ? 0 : ls;
            int e = text.indexOf("RECORDING STOPPED", a);
            if (e < 0) return text.substring(start);
            int le = text.lastIndexOf('\n', e);
            return text.substring(start, le < 0 ? e : le);
        } catch (Throwable t) {
            return text;
        }
    }
    /** 采集起止（epoch ms）与退出钩子信息 */
    private static long sessT0 = 0, sessT1 = 0;
    private static String exitUptime = "";
    private static boolean exitPending = false;

    private static List<Stall> parse(String text) {
        crashes.clear();   // 每次解析重新收集（本地服务每次刷新都重读日志）
        sessT0 = 0; sessT1 = 0; exitUptime = ""; exitPending = false;
        List<Stall> out = new ArrayList<Stall>();
        Stall cur = null;
        Crash curCrash = null;
        int mode = 0;   // 1=timeline 2=stack
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String s = l.trim();
            if (s.indexOf("STALL-END") >= 0) {
                if (cur != null) {
                    int v = num(s, "total=(\\d+)ms");
                    if (v > 0) { cur.total = v; cur.ended = true; }
                    /* 新格式（0.7.2+）：gc=名字 重叠/总时长，后面跟 votes=/conf=/src=；旧格式 gc 后直接 t1 */
                    cur.gc = str(s, "gc=(.+?)(?:  votes=|  t1=)");
                    cur.votes = str(s, "votes=(\\d+/\\d+)");
                    cur.majority = str(s, "majority=(\\S+)");
                    cur.conf = str(s, "conf=(\\S+)");
                    cur.src = str(s, "src=([^\\s]+)");
                    long t1v = lng(s, "t1=(\\d+)");
                    if (t1v > 0) cur.t1 = t1v;
                    cur = null;
                }
                mode = 0;
                continue;
            }
            if (s.indexOf("=== CRASH ===") >= 0) {
                Crash c = new Crash();
                int sp = l.indexOf(' ');
                c.t = sp > 0 ? l.substring(0, sp) : "";
                c.file = str(s, "file=([^\\s]+)");
                crashes.add(c);
                curCrash = c;
                cur = null;
                mode = 0;
                continue;
            }
            if (curCrash != null) {
                if (s.startsWith("time=")) { curCrash.time = s.substring(5).trim(); continue; }
                if (s.startsWith("desc=")) { curCrash.desc = s.substring(5).trim(); continue; }
                if (s.startsWith("reason=")) { curCrash.reason = s.substring(7).trim(); continue; }
                if (s.startsWith("---")) { curCrash = null; continue; }
            }
            if (s.indexOf("[stutter]") >= 0) {
                int mx = num(s, "max=(\\d+)ms");
                if (mx > 0) {
                    int sp = l.indexOf(' ');
                    stutters.add(new String[]{sp > 0 ? l.substring(0, sp) : "", String.valueOf(mx), str(s, "avg=(\\d+)ms")});
                }
            }
            if (isHeader(s)) {
                Stall st = new Stall();
                int sp = l.indexOf(' ');
                st.t = sp > 0 ? l.substring(0, sp) : "";
                int d = num(s, "STALL-DETECT (\\d+)ms");
                if (d == 0) d = num(s, "SNAPSHOT (\\d+)ms");
                if (d == 0) d = num(s, "STALL (\\d+)ms");
                st.detect = d;
                st.since = str(s, "since='([^']*)'");
                String r = str(s, "reason=([^\\s]+)");
                st.reason = r.length() > 0 ? r : "\u672a\u77e5";
                long t0v = lng(s, "t0=(\\d+)");
                if (t0v > 0) st.t0 = t0v;
                /* 新格式（0.7.2+）：STALL-DETECT 带 src=（Mod ownership），ctx 解析避开它 */
                st.src = str(s, "src=([^\\s]+)");
                String ctxLine = st.src.length() > 0 ? s.substring(0, s.indexOf("  src=")) : s;
                st.ctx = str(ctxLine, "ctx=(.+)$");
                out.add(st);
                cur = st;
                mode = 0;
                continue;
            }
            if (cur != null) {
                if (s.startsWith("timeline:")) { mode = 1; continue; }
                if (s.startsWith("stack of")) { mode = 2; continue; }
                if (mode == 1 && l.startsWith("    ") && l.indexOf("t+") > 0) {
                    // 形状是 `runTick   t+   0.412ms` —— t+ 和数值可能被 %8.3f 的补位空格分开，
                    // 数值够宽时又会粘在一起，两种都得吃
                    String[] p = l.trim().split("\\s+");
                    int k = -1;
                    for (int j = 0; j < p.length; j++) if (p[j].startsWith("t+")) { k = j; break; }
                    if (k >= 0) {
                        String v = p[k].length() > 2 ? p[k].substring(2) : "";
                        if (v.length() == 0 && k + 1 < p.length) v = p[k + 1];
                        v = v.replace("ms", "").trim();
                        if (v.length() > 0) {
                            try {
                                cur.timeline.add(new String[]{p[0],
                                        String.format("%.2f", Double.parseDouble(v))});
                            } catch (Throwable ignored) { }
                        }
                    }
                    continue;
                }
                if (mode == 2 && l.startsWith("    ") && s.length() > 0
                        && !s.startsWith("<") && !s.startsWith("...") && !s.startsWith("STALL-")) {
                    cur.stack.add(s);
                    continue;
                }
            }
        }
        // 会话起止与退出钩子：和页面 JS 的扫描口径一致
        for (int i = 0; i < lines.length; i++) {
            String sl = lines[i];
            if (sl.indexOf("=== FrameSpike started ===") >= 0 && sessT0 == 0) sessT0 = lng(sl, "t0=(\\d+)");
            if (sl.indexOf("RECORDING STARTED") >= 0) {
                long v = lng(sl, "t0=(\\d+)");
                if (v > 0) sessT0 = v;
            }
            if (sl.indexOf("RECORDING STOPPED") >= 0) {
                long v = lng(sl, "t1=(\\d+)");
                if (v > 0) sessT1 = v;
            }
            if (sl.indexOf("=== JVM EXIT ===") >= 0) {
                exitUptime = str(sl, "uptime=([^\\s]+)");
                exitPending = sl.indexOf("pendingStall=true") >= 0;
            }
        }

        // 兜底原因（旧日志的「类.方法」）按新规则重新归类
        for (int i = 0; i < out.size(); i++) {
            out.get(i).reason = reclassify(out.get(i).reason, out.get(i).stack);
        }

        // 没结算完的（会话中断）：用检测值兜底
        for (int i = 0; i < out.size(); i++) {
            Stall st = out.get(i);
            if (st.total <= 0) st.total = st.detect;
        }
        return out;
    }

    /** epoch 毫秒超过 int 范围，必须用 long 取 */
    private static long lng(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
            return m.find() ? Long.parseLong(m.group(1)) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int num(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String str(String s, String re) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(s);
            return m.find() ? m.group(1) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ JSON

    private static final String[] DEPTH_COLOR = {
            "#8b6dff", "#4cc9f0", "#2dd4bf", "#a3e635", "#f5a524", "#f472b6", "#7c8aa5"
    };

    /** 报告体积控制：只带最长的 MAX_STALLS 条；栈不再截断，日志里有多少层就写多少层（ini 的 stackDepth 是模组侧的上限） */
    private static final int MAX_STALLS = 400;

    public static String buildJson(String logText) {
        return buildJson(logText, false);
    }

    public static String buildJson(String logText, boolean wholeLog) {
        int allStalls = 0;
        /* 崩块与会话从整份日志扫（崩溃可能发生在记录段之后），然后只把本段交给停顿解析 */
        parse(logText);
        List<Crash> allCrashes = new ArrayList<Crash>(crashes);
        List<String[]> allStutters = new ArrayList<String[]>(stutters);
        long s0 = sessT0, s1 = sessT1;
        String ex = exitUptime;
        boolean ep = exitPending;
        String segText = segmentText(logText, wholeLog);
        List<Stall> stalls = parse(segText);
        crashes.clear();
        crashes.addAll(allCrashes);
        stutters.clear();
        stutters.addAll(allStutters);
        sessT0 = s0; sessT1 = s1; exitUptime = ex; exitPending = ep;
        allStalls = stalls.size();
        boolean truncated = false;
        if (stalls.size() > MAX_STALLS) {
            Collections.sort(stalls, new Comparator<Stall>() {
                public int compare(Stall a, Stall b) { return b.total - a.total; }
            });
            stalls = new ArrayList<Stall>(stalls.subList(0, MAX_STALLS));
            truncated = true;
        }
        List<String[]> cpu = parseCpu(logText);

        // 统计只算有真实时长的记录：旧格式（无 STALL-END）的 total 只是检测值，
        // 一律等于阈值，混进来会把"最长/分档/累计"全带偏。
        long totalMs = 0, maxMs = 0;
        int b1 = 0, b2 = 0, b3 = 0, b4 = 0, measured = 0;
        for (int i = 0; i < stalls.size(); i++) {
            Stall s = stalls.get(i);
            if (!s.ended) continue;
            measured++;
            totalMs += s.total;
            if (s.total > maxMs) maxMs = s.total;
            if (s.total < 100) b1++;
            else if (s.total < 200) b2++;
            else if (s.total < 500) b3++;
            else b4++;
        }

        // 火焰树：按栈顶在前（越深越具体）累计时长
        Node root = new Node("Client thread");
        for (int i = 0; i < stalls.size(); i++) {
            Stall s = stalls.get(i);
            Node node = root;
            root.ms += s.total;
            root.count++;
            for (int j = 0; j < s.stack.size(); j++) {
                String fn = s.stack.get(j);
                Node kid = null;
                for (int k = 0; k < node.kids.size(); k++) {
                    if (node.kids.get(k).name.equals(fn)) { kid = node.kids.get(k); break; }
                }
                if (kid == null) {
                    kid = new Node(fn);
                    kid.color = j == 0 ? colorOf(s.reason) : DEPTH_COLOR[(j + 1) % DEPTH_COLOR.length];
                    node.kids.add(kid);
                }
                kid.ms += s.total;
                kid.count++;
                node = kid;
            }
        }
        sortTree(root);

        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\"summary\":{");
        sb.append("\"stalls\":").append(stalls.size());
        sb.append(",\"measured\":").append(measured);
        sb.append(",\"totalMs\":").append(totalMs);
        sb.append(",\"maxMs\":").append(maxMs);
        sb.append(",\"buckets\":[").append(b1).append(',').append(b2).append(',').append(b3).append(',').append(b4).append(']');
        double fps = 0, tps = 0;
        if (!cpu.isEmpty()) {
            for (int i = 0; i < cpu.size(); i++) {
                try {
                    fps = Math.max(fps, Double.parseDouble(cpu.get(i)[1]));
                    tps = Math.max(tps, Double.parseDouble(cpu.get(i)[2]));
                } catch (Throwable ignored) { }
            }
        }
        sb.append(",\"fps\":").append(fmt(fps)).append(",\"tps\":").append(fmt(tps));
        sb.append(",\"truncated\":").append(truncated ? "true" : "false");
        sb.append(",\"allStalls\":").append(allStalls);
        sb.append(",\"durText\":\"").append(segDuration(logText)).append('"');
        sb.append(",\"sessT0\":").append(sessT0).append(",\"sessT1\":").append(sessT1);
        sb.append(",\"exitUptime\":\"").append(esc(exitUptime)).append('"');
        sb.append(",\"exitPending\":").append(exitPending ? "true" : "false");
        sb.append(",\"crashes\":").append(crashes.size());
        sb.append(",\"microStutters\":").append(stutters.size());
        sb.append(",\"stutters\":[");
        for (int i = 0; i < stutters.size() && i < 32; i++) {
            if (i > 0) sb.append(',');
            String[] st = stutters.get(i);
            sb.append("{\"t\":\"").append(esc(st[0])).append("\",\"max\":").append(st[1]).append(",\"avg\":").append(st[2]).append('}');
        }
        sb.append(']');
        if (truncated) sb.append(",\"note\":\"日志共 ").append(allStalls)
                .append(" 条停顿，报告只带最长的 ").append(stalls.size()).append(" 条\"");
        sb.append("},\"stalls\":[");
        for (int i = 0; i < stalls.size(); i++) {
            Stall s = stalls.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":\"").append(esc(s.t)).append('"');
            sb.append(",\"detect\":").append(s.detect);
            sb.append(",\"total\":").append(s.total);
            sb.append(",\"since\":\"").append(esc(s.since)).append('"');
            sb.append(",\"reason\":\"").append(esc(s.reason)).append('"');
            if (s.gc != null && s.gc.length() > 0) sb.append(",\"gc\":\"").append(esc(s.gc)).append('"');
            if (s.ctx != null && s.ctx.length() > 0) sb.append(",\"ctx\":\"").append(esc(s.ctx)).append('"');
            if (s.src != null && s.src.length() > 0) sb.append(",\"src\":\"").append(esc(s.src)).append('"');
            if (s.votes != null && s.votes.length() > 0) {
                sb.append(",\"votes\":\"").append(esc(s.votes)).append('"');
                sb.append(",\"majority\":\"").append(esc(s.majority)).append('"');
                sb.append(",\"conf\":\"").append(esc(s.conf)).append('"');
            }
            if (s.t0 > 0) sb.append(",\"t0\":").append(s.t0);
            if (s.t1 > 0) sb.append(",\"t1\":").append(s.t1);
            if (!s.ended) sb.append(",\"est\":true");
            sb.append(",\"timeline\":[");
            for (int j = 0; j < s.timeline.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append("[\"").append(esc(s.timeline.get(j)[0])).append("\",")
                  .append(s.timeline.get(j)[1]).append(']');
            }
            sb.append("],\"stack\":[");
            for (int j = 0; j < s.stack.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(esc(s.stack.get(j))).append('"');
            }
            sb.append("]}");
        }
        sb.append("],\"crashes\":[");
        int ncrash = Math.min(crashes.size(), 8);
        for (int i = 0; i < ncrash; i++) {
            Crash c = crashes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":\"").append(esc(c.t)).append('"');
            sb.append(",\"file\":\"").append(esc(c.file)).append('"');
            sb.append(",\"time\":\"").append(esc(c.time)).append('"');
            sb.append(",\"desc\":\"").append(esc(c.desc)).append('"');
            sb.append(",\"reason\":\"").append(esc(c.reason)).append("\"}");
        }
        sb.append("],\"cpu\":[");
        for (int i = 0; i < cpu.size(); i++) {
            String[] c = cpu.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":\"").append(esc(c[0])).append("\",\"cpu\":").append(fmt(dbl(c[3])))
              .append(",\"fps\":").append(fmt(dbl(c[1]))).append(",\"tps\":").append(fmt(dbl(c[2]))).append('}');
        }
        sb.append("],\"flame\":");
        node(sb, root);
        sb.append('}');
        return sb.toString();
    }

    private static void node(StringBuilder sb, Node n) {
        sb.append("{\"name\":\"").append(esc(n.name)).append('"');
        if (n.color != null) sb.append(",\"color\":\"").append(n.color).append('"');
        sb.append(",\"ms\":").append(n.ms).append(",\"count\":").append(n.count).append(",\"kids\":[");
        for (int i = 0; i < n.kids.size(); i++) {
            if (i > 0) sb.append(',');
            node(sb, n.kids.get(i));
        }
        sb.append("]}");
    }

    private static void sortTree(Node n) {
        Collections.sort(n.kids, new Comparator<Node>() {
            public int compare(Node a, Node b) { return Long.compare(b.ms, a.ms); }
        });
        for (int i = 0; i < n.kids.size(); i++) sortTree(n.kids.get(i));
    }

    private static String colorOf(String reason) {
        if (reason == null) return "#7c8aa5";
        if (reason.indexOf("写文件") >= 0) return "#8b6dff";
        if (reason.indexOf("读文件") >= 0) return "#6f8cff";
        if (reason.indexOf("类加载/remap") >= 0) return "#d85a30";
        if (reason.indexOf("Mixin 注入") >= 0) return "#ef4b6b";
        if (reason.indexOf("窗口合成") >= 0) return "#ef9f27";
        if (reason.indexOf("GL驱动") >= 0) return "#2dd4bf";
        if (reason.indexOf("音效") >= 0) return "#f472b6";
        if (reason.indexOf("字体缓存") >= 0) return "#4cc9f0";
        if (reason.indexOf("小地图") >= 0) return "#a3e635";
        if (reason.indexOf("OptiFine") >= 0) return "#f5a524";
        if (reason.indexOf("网络") >= 0) return "#8b8a80";
        if (reason.indexOf("区块渲染") >= 0) return "#37b0e6";
        if (reason.indexOf("实体/模型渲染") >= 0) return "#7fd8a8";
        if (reason.indexOf("GUI") >= 0) return "#c9a227";
        if (reason.indexOf("游戏自身") >= 0) return "#7f9bb5";
        if (reason.indexOf("直接内存") >= 0) return "#9ad0ff";
        if (reason.indexOf("等待/限速") >= 0) return "#b0b7c3";
        if (reason.indexOf("压缩/解压") >= 0) return "#b58cff";
        if (reason.indexOf("序列化/解析") >= 0) return "#a9c46c";
        if (reason.indexOf("图像") >= 0) return "#e07ab8";
        if (reason.indexOf("内嵌浏览器") >= 0) return "#8fa3c8";
        if (reason.indexOf("Essential") >= 0) return "#59c4a8";
        if (reason.indexOf("Lunar 混淆类") >= 0) return "#6b7480";
        if (reason.indexOf("混淆类") >= 0) return "#6b7480";
        if (reason.indexOf("JDK 内部") >= 0) return "#93a0b5";
        if (reason.indexOf("异常构造") >= 0) return "#e08a5a";
        return "#7c8aa5";
    }

    /** 老日志里「类.方法」这种兜底原因，用栈按新规则重新归类（两边解析器口径一致） */
    private static String reclassify(String reason, List<String> stack) {
        if (stack == null || stack.isEmpty()) return reason;
        if (reason == null) reason = "\u672a\u77e5";
        if (reason.indexOf('.') < 0 && reason.indexOf("\u672a\u77e5") < 0) return reason;
        StringBuilder top = new StringBuilder(256);
        String fc = "", fm = "";
        for (int i = 0; i < stack.size() && i < 6; i++) {
            String t = stack.get(i);
            int p = t.indexOf('(');
            String cm = p > 0 ? t.substring(0, p).trim() : t.trim();
            top.append(cm).append(' ');
            if (fc.length() == 0) {
                int d = cm.lastIndexOf('.');
                if (d > 0) { fc = cm.substring(0, d); fm = cm.substring(d + 1); }
            }
        }
        return top.length() > 0 ? FrameSpike.classifyFromText(top.toString(), fc, fm) : reason;
    }

    private static List<String[]> parseCpu(String text) {
        List<String[]> out = new ArrayList<String[]>();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.indexOf("[CPU]") < 0) continue;
            String t = l.trim();
            int sp = t.indexOf(' ');
            String fps = str(l, "fps\u2248([\\d.]+)");
            String tps = str(l, "tps\u2248([\\d.]+)");
            String busy = str(l, "machine busy=([\\d.]+)");
            if (fps.length() == 0 && tps.length() == 0) continue;
            out.add(new String[]{sp > 0 ? t.substring(0, sp) : "", fps, tps, busy});
        }
        return out;
    }

    private static String segDuration(String text) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("RECORDING STARTED[^\\n]*").matcher(text);
            if (!m.find()) return "";
            String[] lines = text.split("\\r?\\n");
            String start = null, end = null;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].indexOf("RECORDING STARTED") >= 0 && start == null) start = lines[i];
                if (lines[i].indexOf("RECORDING STOPPED") >= 0) end = lines[i];
            }
            if (start == null) return "";
            String a = str(start, "(\\d\\d:\\d\\d:\\d\\d)");
            String b = end != null ? str(end, "(\\d\\d:\\d\\d:\\d\\d)") : "";
            return b.length() > 0 ? (a + " → " + b) : a;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s); } catch (Throwable t) { return 0; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else if (c == '<') b.append("\\u003c");
            else if (c == '>') b.append("\\u003e");
            else if (c == '&') b.append("\\u0026");
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 文件 / 资源

    private static String readResource() {
        try {
            InputStream in = Report.class.getResourceAsStream(TPL);
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File f) {
        try {
            if (f == null || !f.isFile()) return "";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeText(File f, String s) throws IOException {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
        try { w.write(s); } finally { w.close(); }
    }
}
