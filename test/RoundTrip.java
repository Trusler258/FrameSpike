import framespike.Cfg;
import framespike.FrameSpikeTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 真类往返测试：拿原版 1.8.9.jar 里最大的 40 个类，让 transformer 走一遍
 * ClassReader -> (我们的 visitor) -> ClassWriter，然后比对改写前后的
 * 「方法清单 + 每个方法的 opcode 序列」。用于证明纯往返是无损的。
 * 名字故意传成一个 hook 目标名，好让工具强制走完整往返路径（但真实类里没有 SRG 方法名，
 * 所以实际注入数应为 0）。
 */
public class RoundTrip {

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 规范化文本：类名 + 方法（名/描述符/访问标志）+ 指令 opcode 序列 */
    private static String canon(byte[] cls) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        StringBuilder sb = new StringBuilder(cls.length);
        sb.append("CLASS ").append(cn.name).append(' ').append(cn.version)
          .append(" super=").append(cn.superName).append('\n');
        sb.append("IFACES ").append(cn.interfaces).append('\n');
        sb.append("FIELDS ").append(cn.fields.size()).append('\n');
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = (MethodNode) cn.methods.get(i);
            sb.append("M ").append(m.access).append(' ').append(m.name).append(m.desc)
              .append(" maxs=").append(m.maxStack).append('/').append(m.maxLocals)
              .append(" insns=").append(m.instructions.size())
              .append(" trycatch=").append(m.tryCatchBlocks == null ? 0 : m.tryCatchBlocks.size())
              .append('\n');
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                sb.append(p.getOpcode()).append(',');
            }
            sb.append('\n');
            if (m.localVariables != null) sb.append("LV ").append(m.localVariables.size()).append('\n');
        }
        sb.append("INNERS ").append(cn.innerClasses == null ? 0 : cn.innerClasses.size()).append('\n');
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Cfg.enabled = true;
        Cfg.glFinishMode = "off";
        Cfg.logFile = "out/roundtrip.log";
        new java.io.File(Cfg.logFile).delete();
        FrameSpikeTransformer tx = new FrameSpikeTransformer();

        ZipFile z = new ZipFile(args[0]);
        List<ZipEntry> es = new ArrayList<ZipEntry>();
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().endsWith(".class") && e.getSize() > 3000) es.add(e);
        }
        es.sort(new java.util.Comparator<ZipEntry>() {
            public int compare(ZipEntry a, ZipEntry b) { return Long.compare(b.getSize(), a.getSize()); }
        });

        int tested = 0, fail = 0, changed = 0;
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        for (int i = 0; i < es.size() && tested < limit; i++) {
            ZipEntry e = es.get(i);
            byte[] in = readAll(z.getInputStream(e));
            tested++;
            try {
                // 故意用一个「有 report 但没有任何 hook」的类名，强制走完整往返路径且注入数为 0
                byte[] out = tx.transform("rt/Obf", "net.minecraft.client.renderer.RenderGlobal", in);
                if (out == in) { System.out.println("  SKIP(未走往返) " + e.getName()); continue; }
                changed++;
                String a = canon(in), b = canon(out);
                if (!a.equals(b)) {
                    fail++;
                    System.out.println("  MISMATCH " + e.getName() + " (" + in.length + " -> " + out.length + " bytes)");
                    String[] la = a.split("\n"), lb = b.split("\n");
                    for (int k = 0; k < Math.min(la.length, lb.length); k++) {
                        if (!la[k].equals(lb[k])) {
                            System.out.println("     - " + la[k]);
                            System.out.println("     + " + lb[k]);
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                fail++;
                System.out.println("  ERROR " + e.getName() + " : " + t);
            }
        }
        int phase2fail = 0;
        System.out.println();
        System.out.println("== 第二段：用真实 notch 类验证 hook 真的命中 ==");
        String[][] reals = {
                {"ave.class", "net.minecraft.client.Minecraft", "2"},
                {"bfk.class", "net.minecraft.client.renderer.EntityRenderer", "3"},
        };
        for (int i = 0; i < reals.length; i++) {
            String entryName = reals[i][0];
            String mcp = reals[i][1];
            int expect = Integer.parseInt(reals[i][2]);
            byte[] in = readAll(z.getInputStream(z.getEntry(entryName)));
            byte[] out = tx.transform(entryName.replace(".class", ""), mcp, in);
            int inj = countInjections(out);
            System.out.println("  " + entryName + " (" + mcp + "): 注入检查点 " + inj + " 个（期望 " + expect + "）");
            if (inj != expect) phase2fail++;
            java.util.Map<String, int[]> ai = opcodes(in);
            java.util.Map<String, int[]> bo = opcodes(out);
            if (!ai.keySet().equals(bo.keySet())) {
                System.out.println("     *** 方法集合变了");
                phase2fail++;
                continue;
            }
            java.util.Iterator<String> it = ai.keySet().iterator();
            int bad = 0;
            while (it.hasNext()) {
                String k = it.next();
                int[] a = ai.get(k);
                int[] b = bo.get(k);
                if (a.length == b.length) {
                    for (int j = 0; j < a.length; j++) {
                        if (a[j] != b[j]) { bad++; break; }
                    }
                } else if (b.length == a.length + 2 && b[0] == 18 && b[1] == 184) {
                    // 注入的 LDC + INVOKESTATIC 出现在方法开头，其余原样
                    for (int j = 0; j < a.length; j++) {
                        if (a[j] != b[j + 2]) { bad++; break; }
                    }
                } else {
                    bad++;
                }
            }
            System.out.println("     指令序列差异异常的方法数 = " + bad);
            if (bad != 0) phase2fail++;
        }
        if (phase2fail == 0) System.out.println("  真实类注入验证: PASS");
        else System.out.println("  真实类注入验证: FAIL");

        System.out.println();
        System.out.println("tested=" + tested + " roundTripped=" + changed + " mismatch=" + fail);
        boolean ok = (fail == 0 && phase2fail == 0);
        System.out.println(ok ? "ROUNDTRIP LOSSESS: PASS" : "ROUNDTRIP: FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static int countInjections(byte[] cls) {
        final int[] n = new int[1];
        new ClassReader(cls).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int a, String mn, String d, String s, String[] e) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                        if (op == org.objectweb.asm.Opcodes.INVOKESTATIC
                                && owner.equals("framespike/FrameSpike") && name.equals("checkpoint")) n[0]++;
                    }
                };
            }
        }, 0);
        return n[0];
    }

    private static java.util.Map<String, int[]> opcodes(byte[] cls) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, 0);
        java.util.LinkedHashMap<String, int[]> map = new java.util.LinkedHashMap<String, int[]>();
        for (int i = 0; i < cn.methods.size(); i++) {
            MethodNode m = (MethodNode) cn.methods.get(i);
            int sz = m.instructions.size();
            int[] ops = new int[sz];
            int k = 0;
            for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
                ops[k++] = p.getOpcode();
            }
            map.put(m.name + m.desc, ops);
        }
        return map;
    }
}
