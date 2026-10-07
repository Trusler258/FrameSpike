import framespike.Report;

import java.io.File;

/**
 * 命令行生成报告 —— 不用启动游戏也能把旧日志变成分析网页。
 *
 *   java -cp out/main;resources;out/test ReportGen "<日志路径>" ["<输出目录>"]
 *
 * 不传参数时用默认路径（%USERPROFILE%\.lunarclient\profiles\1.8\framespike\frame-spikes.log）。
 */
public class ReportGen {

    public static void main(String[] args) {
        String def = System.getProperty("user.home")
                + "\\.lunarclient\\profiles\\1.8\\framespike\\frame-spikes.log";
        String logPath = args.length > 0 ? args[0] : def;
        File log = new File(logPath);
        if (!log.isFile()) {
            System.out.println("找不到日志: " + log.getAbsolutePath());
            System.exit(1);
        }
        File dir = args.length > 1 ? new File(args[1]) : null;
        File out = Report.write(log, dir);
        if (out == null) {
            System.out.println("生成失败");
            System.exit(1);
        }
        System.out.println("报告: " + out.getAbsolutePath());
        System.out.println("大小: " + out.length() + " bytes");

        // 顺手打印一份文字版摘要，方便命令行里直接看
        String json = Report.buildJson(read(log));
        System.out.println("停顿条数: " + pick(json, "\"stalls\":", ','));
        System.out.println("累计时长: " + pick(json, "\"totalMs\":", ',') + " ms");
        System.out.println("最长一次: " + pick(json, "\"maxMs\":", ',') + " ms");
        System.out.println("分档: " + pick(json, "\"buckets\":[", ']'));
    }

    private static String pick(String s, String key, char end) {
        int i = s.indexOf(key);
        if (i < 0) return "?";
        i += key.length();
        int j = s.indexOf(end, i);
        return j < 0 ? "?" : s.substring(i, j);
    }

    private static String read(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
