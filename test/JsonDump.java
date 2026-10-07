import framespike.Report;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 把日志转成报告用的 JSON —— 给 test/parse_check.js 当"标准答案"用，
 * 保证网页里那份 JS 解析器和 Java 侧 Report.buildJson 口径完全一致。
 *
 *   java -cp out/main;resources;out/test JsonDump <日志> [输出json]
 *
 * 不给输出路径就直接把 JSON 打到 stdout。
 */
public class JsonDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: JsonDump <日志> [输出json]");
            System.exit(2);
        }
        File log = new File(args[0]);
        if (!log.isFile()) {
            System.out.println("找不到日志: " + log.getAbsolutePath());
            System.exit(1);
        }
        String json = Report.buildJson(read(log));
        if (args.length > 1) {
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(args[1], false), "UTF-8");
            try { w.write(json); } finally { w.close(); }
            System.out.println("json: " + args[1] + "  " + json.length() + " 字符");
        } else {
            System.out.print(json);
        }
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int n = in.read(b);
        in.close();
        return new String(b, 0, n, "UTF-8");
    }
}
