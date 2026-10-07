package framespike.neoforge;

import framespike.Cmd;
import framespike.FrameSpike;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * NeoForge 侧聊天出口：**反射 + 形状匹配**，零 MC 类引用。
 *
 * NeoForge 1.20.1 运行期的 MC 是 SRG 名且 mod 代码不被 remap —— 任何 MC 类的编译期引用
 * 都会 NoSuchMethodError（实测踩到）。所以按「形状」找目标，不看名字：
 *   Minecraft 的静态无参方法返回自身              = getInstance
 *   Minecraft 实例字段类型 .LocalPlayer 结尾      = 本地玩家（不能模糊匹配，PlayerSocialManager 也含 Player）
 *   玩家身上 (Component, boolean) 的方法          = displayClientMessage；退路 (Component) = sendSystemMessage
 *   Component 的静态 (String) 方法                = literal
 * 颜色：自己拆 § 段，逐段 literal + withStyle(ChatFormatting...) 链（varargs 感知——
 * 它的参数是 ChatFormatting[] 数组，按 getName().endsWith 匹配会漏掉，实测踩到）。
 * 和 1.8.9 coremod 的反射桥同一招。SRG 名变了也不受影响。
 */
public class NeoChat implements Cmd.Sink {

    private static volatile Object mcInstance;
    private static volatile Object player;
    private static volatile Method sendMessage;
    private static volatile Method literal;
    private static volatile boolean chatFailLogged;

    @Override
    public void send(String text) {
        send(text, null);
    }

    @Override
    public void sendLink(String text, String url) {
        // 反射方案下可点链接要做 Style 链，先退化为纯文本（路径在日志里仍有）
        send(text + (url == null ? "" : "  " + url), null);
    }

    private void send(String text, String url) {
        try {
            resolve();
            if (player == null || sendMessage == null) {
                FrameSpike.note(text);
                return;
            }
            Object comp = buildComponent(text);
            sendMessage.invoke(player, comp, Boolean.FALSE);
        } catch (Throwable t) {
            FrameSpike.note(text);
        }
    }

    /**
     * 自己拆 § 段，逐段 literal(文本).withStyle(ChatFormatting) 并 append 成一个组件。
     *
     * 反射查找的两个坑（都实测踩到）：
     *   - `MutableComponent.withStyle(ChatFormatting...)` 是 **varargs**，参数类型是
     *     `ChatFormatting[]` 数组 —— 按 `pt[0].getName().endsWith("ChatFormatting")` 匹配会漏掉
     *     （数组名是 `[Lnet.minecraft.ChatFormatting;`），要用 `getComponentType()` 判断；
     *   - `§r` 走 withStyle(RESET) 是 no-op（RESET 的颜色/粗体全是 null），
     *     重置要靠「下一段不带样式」（sibling 的样式互相独立，天然就是重置）。
     */
    private Object buildComponent(String s) {
        try {
            Class<?> chatFmt = Class.forName("net.minecraft.ChatFormatting");
            Class<?> mutCls = Class.forName("net.minecraft.network.chat.MutableComponent");
            Class<?> compCls = Class.forName("net.minecraft.network.chat.Component");

            Method getByCode = null;
            for (Method m : chatFmt.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && pt.length == 1 && pt[0] == char.class
                        && chatFmt.isAssignableFrom(m.getReturnType())) {
                    getByCode = m;
                    break;
                }
            }
            if (getByCode == null) return literal.invoke(null, plain(s));

            Method withStyle = null;
            Method append = null;
            for (Method m : mutCls.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || !mutCls.isAssignableFrom(m.getReturnType())) continue;
                if (withStyle == null && pt[0].isArray()
                        && pt[0].getComponentType() == chatFmt) withStyle = m;
                if (append == null && pt[0] == compCls) append = m;
            }
            if (withStyle == null || append == null) return literal.invoke(null, plain(s));

            Object root = null;
            Object cur = null;
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\u00a7' && i + 1 < s.length()) {
                    if (buf.length() > 0) {
                        Object seg = literal.invoke(null, buf.toString());
                        if (cur != null) seg = withStyle.invoke(seg, getByCode.invoke(null, cur));
                        root = (root == null) ? seg : append.invoke(root, seg);
                        buf.setLength(0);
                    }
                    char code = s.charAt(i + 1);
                    cur = ('r' == code || 'R' == code) ? null : getByCode.invoke(null, code);
                    i++;
                    continue;
                }
                buf.append(c);
            }
            if (buf.length() > 0) {
                Object seg = literal.invoke(null, buf.toString());
                if (cur != null) seg = withStyle.invoke(seg, getByCode.invoke(null, cur));
                root = (root == null) ? seg : append.invoke(root, seg);
            }
            if (root == null) root = literal.invoke(null, "");
            return root;
        } catch (Throwable t) {
            try {
                return literal.invoke(null, plain(s));
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /** 现代 MC 的 Component.literal 不解释 § 颜色码 —— 反射件不全时的兜底：剥掉（文本仍可读） */
    private static String plain(String s) {
        if (s == null || s.indexOf('\u00a7') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 形态匹配解析聊天链路。**失败不锁死**：第一次解析可能发生在进世界之前
     * （那时本地玩家还不存在，实测踩到），所以每次发送都重试直到成功。
     */
    private static synchronized void resolve() {
        if (sendMessage != null) return;
        try {
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft");

            // 1) 静态 + 无参 + 返回自身 = getInstance
            Object inst = null;
            for (Method m : mc.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0 || m.getReturnType() != mc) continue;
                try {
                    m.setAccessible(true);
                    inst = m.invoke(null);
                    if (inst != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (inst == null) return;
            mcInstance = inst;

            // 2) 实例字段类型是本地玩家类 = LocalPlayer
            // 不能模糊匹配「含 Player」：Minecraft 里还有 playerSocialManager（PlayerSocialManager），
            // 它排在 player 前面，会拿到社交管理器而不是玩家（实测踩到，诊断日志抓到的）
            for (Class<?> c = mc; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    String tn = f.getType().getName();
                    if (!tn.endsWith(".LocalPlayer") && !tn.endsWith("ClientPlayerEntity")) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(inst);
                        if (v != null) {
                            player = v;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (player != null) break;
            }
            if (player == null) return;

            // 3) Component 的静态 (String) 方法返回 Component = literal
            Class<?> comp = Class.forName("net.minecraft.network.chat.Component");
            final String compName = comp.getName();
            for (Method m : comp.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == String.class && m.getReturnType().getName().equals(compName)) {
                    m.setAccessible(true);
                    literal = m;
                    break;
                }
            }
            if (literal == null) return;

            // 4) 玩家身上 (Component, boolean) = displayClientMessage；找不到再找 (Component) = sendSystemMessage
            // 用**类型名**匹配，不用 isAssignableFrom —— NeoForge 1.20.1 的模块加载器下，
            // 我们的 Component 与运行期 MC 的 Component 可能不是同一个 Class 对象（实测踩到）
            for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[1] == boolean.class && pt[0].getName().equals(compName)) {
                        m.setAccessible(true);
                        sendMessage = m;
                        break;
                    }
                }
                if (sendMessage != null) break;
            }
            if (sendMessage == null) {
                for (Class<?> c = player.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt.length == 1 && pt[0].getName().equals(compName)) {
                            m.setAccessible(true);
                            sendMessage = m;
                            break;
                        }
                    }
                    if (sendMessage != null) break;
                }
            }
            if (sendMessage == null) {
                FrameSpike.note("[chat] 反射没找到聊天方法（每条提示只在日志里，进世界后会重试）");
            }
        } catch (Throwable t) {
            if (!chatFailLogged) {
                chatFailLogged = true;
                FrameSpike.note("[chat] 反射解析聊天链路失败（同类失败本会话不再刷日志）: " + t);
            }
        }
    }
}
