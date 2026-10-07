/*
 * FrameSpike - Minecraft 1.8.9 / Lunar Client 帧暂停取证 coremod
 * Author: Trusler
 */
package framespike;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端命令反射桥。
 *
 * 设计原则：**不硬编码任何 SRG 方法名**（运行时的真实名字无法离线确认，硬编码会静默失配），
 * 一律按「参数个数 + 参数类型 + 返回类型」的形态定位目标方法，并把实际命中的方法名打进日志。
 *
 * 为什么用 java.lang.reflect.Proxy 而不是自己写一个实现类：
 * 本机只有 JDK 17，没有 MCP 命名的 MC jar，编译期拿不到 ICommand 的签名。Proxy 可以在运行时
 * 直接实现运行时加载的 ICommand 接口，零编译期依赖。
 */
public final class Cmd {

    private static final String IFACE_CMD = "net.minecraft.command.ICommand";
    private static final String IFACE_SENDER = "net.minecraft.command.ICommandSender";
    private static final String HANDLER = "net.minecraftforge.client.ClientCommandHandler";

    /** 运行时可能叫这几个名字之一（1.8.9 与 1.9+ 命名不同，不能写死） */
    private static final String[] TEXT_IMPL_CANDIDATES = {
            "net.minecraft.util.text.ChatComponentText",
            "net.minecraft.util.ChatComponentText",
            "net.minecraft.util.text.TextComponentString",
            "net.minecraft.util.text.ChatTextComponent",
    };

    public static volatile String activeName = "";
    public static volatile List<String> activeAliases = Collections.emptyList();
    public static volatile boolean gaveUp = false;
    /** 运行时类加载器（注册时记下），供后续反射用 */
    public static volatile ClassLoader loader;
    /** 最近一次 processCommand 的 sender（客户端线程），卡顿提示复用它 */
    private static volatile Object lastSender;
    private static volatile Object cachedPlayer;

    private static Object commandInstance;
    private static Method chatMethod;
    private static Constructor<?> textCtor;
    private static String chatInfo = "not resolved";
    private static String registerInfo = "not attempted";

    private Cmd() {}

    /**
     * 加载器接管的聊天出口。
     *
     * Fabric / NeoForge 的聊天组件跟 1.8.9 完全不是一回事（Text/String、写法都变了），
     * 与其在核心塞一堆版本分支，不如让加载器自己实现一个 Sink 交进来。
     * 没装 Sink 时核心走原来的「反射找 thePlayer + ChatComponentText」老路 ——
     * 1.8.9 coremod 永远不装，所以那条路径的行为一字未变。
     */
    public interface Sink {
        void send(String text);

        /** 带可点链接的一行；实现方不支持可以退化成 send */
        void sendLink(String text, String url);
    }

    private static volatile Sink sink;
    /** 命令已由加载器自己的命令系统注册（Fabric / NeoForge），核心不必再走 Forge 反射注册 */
    private static volatile boolean external;

    public static void setSink(Sink s) { sink = s; }

    public static boolean hasSink() { return sink != null; }

    /** 加载器注册完命令后调用，让 /fs status 显示真实状态、并让 Forge 注册重试直接收手 */
    public static void markExternalRegistration(String name, List<String> aliases) {
        external = true;
        if (name != null && name.length() > 0) activeName = name;
        if (aliases != null && !aliases.isEmpty()) activeAliases = aliases;
        registerInfo = "由加载器的命令系统注册";
    }

    public static boolean externallyRegistered() { return external; }

    /** 走 Sink 发一行；没有 Sink 返回 false（调用方继续走老路径） */
    private static boolean sinkSend(String text) {
        Sink s = sink;
        if (s == null) return false;
        try {
            s.send(text);
        } catch (Throwable t) {
            FrameSpike.note("[chat] sink 发送失败: " + t);
        }
        return true;
    }

    private static boolean sinkLink(String text, String url) {
        Sink s = sink;
        if (s == null) return false;
        try {
            s.sendLink(text, url);
        } catch (Throwable t) {
            try { s.send(text); } catch (Throwable ignored) { }
        }
        return true;
    }

    public static boolean registered() {
        return commandInstance != null || external;
    }

    /** 已经彻底放弃（预算用尽）→ 由调用方切到 ctrl 文件通道。 */
    public static boolean done() {
        return registered() || gaveUp;
    }

    public static String state() {
        return "registered=" + registered() + (registered() ? (" name=/" + activeName + " aliases=" + activeAliases) : "")
                + " gaveUp=" + gaveUp + " register=" + registerInfo + " chat=" + chatInfo;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 只允许在客户端线程调用。返回 true 表示不用再试了（成功或彻底放弃）。
     */
    public static boolean tryRegister(ClassLoader mcLoader) {
        if (done()) return true;
        loader = mcLoader;
        try {
            Class<?> cmdIface = Class.forName(IFACE_CMD, false, mcLoader);
            Class<?> handlerCls = Class.forName(HANDLER, false, mcLoader);
            Object handler = instanceOf(handlerCls);
            if (handler == null) {
                registerInfo = "ClientCommandHandler.instance 未找到，下帧重试";
                return false;
            }

            Map<?, ?> existing = commands(handler);
            String name = pickName(existing);
            if (name == null) {
                gaveUp = true;
                registerInfo = "候选命令名全被占用，放弃注册";
                FrameSpike.note("[cmd] " + registerInfo);
                return true;
            }

            Object proxy = Proxy.newProxyInstance(cmdIface.getClassLoader(),
                    new Class<?>[]{cmdIface}, new Handler());
            Method reg = findRegister(handler.getClass(), cmdIface);
            if (reg == null) {
                gaveUp = true;
                registerInfo = "找不到注册方法（参数=ICommand、返回类型=ICommand）";
                FrameSpike.note("[cmd] " + registerInfo);
                return true;
            }

            activeName = name;
            activeAliases = aliasesFor(name);
            reg.invoke(handler, proxy);
            commandInstance = proxy;
            registerInfo = "ok, via " + reg.getDeclaringClass().getName() + "." + reg.getName()
                    + regDesc(reg);
            FrameSpike.note("[cmd] 客户端命令注册成功: /" + activeName + " " + activeAliases
                    + "  (" + registerInfo + ")");
            resolveChatSink(Class.forName(IFACE_SENDER, false, mcLoader));
            return true;
        } catch (Throwable t) {
            registerInfo = "失败: " + t;
            FrameSpike.note("[cmd] 注册尝试失败（下帧重试）: " + t);
            return false;
        }
    }

    private static String regDesc(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(p[i].getName());
        }
        return sb.append(")").append(m.getReturnType().getName()).toString();
    }

    private static Object instanceOf(Class<?> handlerCls) {
        try {
            java.lang.reflect.Field f = handlerCls.getField("instance");
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                Object o = f.get(null);
                if (o != null && handlerCls.isInstance(o)) return o;
            }
        } catch (Throwable ignored) {
        }
        for (java.lang.reflect.Field f : handlerCls.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && handlerCls.isAssignableFrom(f.getType())) {
                try {
                    Object o = f.get(null);
                    if (o != null) return o;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Map<?, ?> commands(Object handler) {
        try {
            for (Method m : handler.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 && Map.class.isAssignableFrom(m.getReturnType())) {
                    Object o = m.invoke(handler);
                    if (o instanceof Map) return (Map<?, ?>) o;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findRegister(Class<?> handlerCls, Class<?> cmdIface) {
        List<Method> exact = new ArrayList<Method>();
        List<Method> loose = new ArrayList<Method>();
        for (Method m : handlerCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            Class<?> rt = m.getReturnType();
            boolean retOk = rt.equals(cmdIface) || rt.equals(void.class) || cmdIface.isAssignableFrom(rt);
            if (!retOk) continue;
            if (p[0].equals(cmdIface)) exact.add(m);
            else if (p[0].isAssignableFrom(cmdIface) && !p[0].equals(Object.class)) loose.add(m);
        }
        List<Method> picks = exact.size() == 1 ? exact : (exact.isEmpty() && loose.size() == 1 ? loose : null);
        if (picks == null) {
            StringBuilder sb = new StringBuilder("候选=");
            for (Method m : exact) sb.append(m.getName()).append(' ');
            for (Method m : loose) sb.append(m.getName()).append("(loose) ");
            FrameSpike.note("[cmd] 注册方法定位歧义或为空: exact=" + exact.size() + " loose=" + loose.size() + " " + sb);
            return null;
        }
        return picks.get(0);
    }

    private static String pickName(Map<?, ?> existing) {
        List<String> cands = new ArrayList<String>();
        if (Cfg.cmdName != null && Cfg.cmdName.trim().length() > 0) cands.add(Cfg.cmdName.trim());
        for (String a : splitAliases(Cfg.cmdAliases)) cands.add(a);
        cands.add("framespike");
        cands.add("fspike");
        cands.add("fsx");
        for (String c : cands) {
            if (existing == null || !existing.containsKey(c)) return c;
        }
        return null;
    }

    private static List<String> aliasesFor(String name) {
        List<String> out = new ArrayList<String>();
        for (String a : splitAliases(Cfg.cmdAliases)) {
            if (!a.equals(name) && !out.contains(a)) out.add(a);
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> splitAliases(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            String t = p.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ 聊天输出

    private static void resolveChatSink(Class<?> senderIface) {
        try {
            Method unique = null;
            int count = 0;
            List<Method> byName = new ArrayList<Method>();
            for (Method m : senderIface.getMethods()) {
                if (m.getParameterTypes().length == 1 && m.getReturnType().equals(void.class)) {
                    unique = m;
                    count++;
                    if (m.getParameterTypes()[0].getSimpleName().contains("Component")) byName.add(m);
                }
            }
            if (count != 1) {
                unique = byName.size() == 1 ? byName.get(0) : null;
            }
            if (unique == null) {
                chatInfo = "sender 上没有唯一的「单参 + void」方法，聊天输出关闭（只写日志）";
                return;
            }
            Class<?> arg = unique.getParameterTypes()[0];
            for (String cn : TEXT_IMPL_CANDIDATES) {
                try {
                    Class<?> impl = Class.forName(cn, false, senderIface.getClassLoader());
                    if (!arg.isAssignableFrom(impl)) continue;
                    for (Constructor<?> c : impl.getConstructors()) {
                        Class<?>[] cp = c.getParameterTypes();
                        if (cp.length == 1 && cp[0].equals(String.class)) {
                            chatMethod = unique;
                            textCtor = c;
                            chatInfo = "ok, " + arg.getName() + " <- " + impl.getName();
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            chatInfo = "找到 " + unique.getName() + "(" + arg.getName() + ") 但没有可用的单参 String 实现类";
        } catch (Throwable t) {
            chatInfo = "解析失败: " + t;
        }
    }

    /** sender 为 null 时只写日志（ctrl 文件通道走的这条路）。文本里已有 FrameSpike 字样就不加前缀，避免「[FrameSpike] FrameSpike v0.7.0」这种重复。 */
    public static void reply(Object sender, String text) {
        String t = (text != null && text.contains("FrameSpike")) ? text : "[FrameSpike] " + text;
        replyRaw(sender, t);
    }

    /** 不加前缀的原始输出。 */
    public static void replyRaw(Object sender, String text) {
        if (sinkSend(text)) return;
        if (sender == null || chatMethod == null || textCtor == null) {
            FrameSpike.note(text);
            return;
        }
        try {
            Object msg = textCtor.newInstance(text);
            chatMethod.invoke(sender, msg);
        } catch (Throwable t) {
            FrameSpike.note(text + "   (聊天输出失败: " + t + ")");
        }
    }

    /**
     * 带可点链接的回复：反射给聊天组件挂一个 ClickEvent(OPEN_URL, url)，
     * 点一下就能唤起浏览器（file:/// 也能开）。任何一步失败都退回纯文本，绝不影响正事。
     */
    public static void replyLink(Object sender, String text, String url) {
        if (sinkLink(text, url)) return;
        if (sender == null || chatMethod == null || textCtor == null || url == null) {
            replyRaw(sender, text);
            return;
        }
        try {
            Object msg = textCtor.newInstance(text);
            ClassLoader cl = msg.getClass().getClassLoader();
            Class<?> styleCls = Class.forName("net.minecraft.util.ChatStyle", false, cl);
            Class<?> clickCls = Class.forName("net.minecraft.event.ClickEvent", false, cl);
            Class<?> actionCls = Class.forName("net.minecraft.event.ClickEvent$Action", false, cl);
            Object openUrl = null;
            Object[] acts = actionCls.getEnumConstants();
            if (acts != null) {
                for (int i = 0; i < acts.length; i++) {
                    if ("OPEN_URL".equals(String.valueOf(acts[i]))) { openUrl = acts[i]; break; }
                }
            }
            if (openUrl == null) { replyRaw(sender, text); return; }
            Object click = clickCls.getConstructor(actionCls, String.class).newInstance(openUrl, url);
            Object style = styleCls.getConstructor().newInstance();
            styleCls.getMethod("setChatClickEvent", clickCls).invoke(style, click);
            msg.getClass().getMethod("setChatStyle", styleCls).invoke(msg, style);
            chatMethod.invoke(sender, msg);
        } catch (Throwable t) {
            replyRaw(sender, text);
        }
    }

    /**
     * 卡顿提示用：没有现成的 sender 时，反射把客户端玩家当成 ICommandSender。
     * 全程只需在客户端线程调用（printChatMessage 会碰 GUI）。
     */
    public static void chat(String text) {
        if (sinkSend(text)) return;
        Object s = lastSender;
        if (s == null) {
            s = cachedPlayer;
            if (s == null) {
                s = resolvePlayerSender();
                cachedPlayer = s;
            }
        }
        if (s == null) {
            FrameSpike.note("[chat] " + text);
            return;
        }
        replyRaw(s, text);
    }

    public static void chatLink(String text, String url) {
        if (sinkLink(text, url)) return;
        Object s = lastSender;
        if (s == null) {
            s = cachedPlayer;
            if (s == null) {
                s = resolvePlayerSender();
                cachedPlayer = s;
            }
        }
        if (s == null) {
            FrameSpike.note("[chat] " + text);
            return;
        }
        replyLink(s, text, url);
    }

    public static void rememberSender(Object sender) {
        if (sender != null) lastSender = sender;
    }

    /** 玩家对象（thePlayer），给世界上下文用；找不到返回 null */
    static Object playerObject() {
        try {
            Object s = cachedPlayer;
            if (s == null) {
                s = resolvePlayerSender();
                cachedPlayer = s;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 形态匹配：Minecraft 上「静态 + 无参 + 返回类型是 Minecraft」的方法 = getMinecraft；
     *  它身上「类型实现了 ICommandSender 的实例字段」= thePlayer。都不看名字。 */
    /** 反射失败只记一次日志（每会话），别把日志刷满 */
    private static volatile boolean chatFailLogged = false;

    /**
     * 取玩家对象当聊天通道：
     *   1) 遍历 declaredMethods（含父类）找「静态 + 无参 + 返回 Minecraft」，逐个 try/catch ——
     *      只试第一个候选会被 Lunar 的多层类加载器坑到（报过 void <init>() not found）
     *   2) 退路：静态字段里类型就是 Minecraft 的
     *   3) 在 Minecraft 的实例字段里找 ICommandSender（thePlayer）
     * 全程跳过 <init> / <clinit>。
     */
    private static Object resolvePlayerSender() {
        try {
            ClassLoader cl = loader != null ? loader : Cmd.class.getClassLoader();
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft", false, cl);
            Object inst = null;
            for (Class<?> c = mc; c != null && c != Object.class && inst == null; c = c.getSuperclass()) {
                Method[] ms = c.getDeclaredMethods();
                for (int i = 0; i < ms.length && inst == null; i++) {
                    Method m = ms[i];
                    if (m.getName().startsWith("<")) continue;
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterTypes().length != 0 || !m.getReturnType().equals(mc)) continue;
                    try { m.setAccessible(true); inst = m.invoke(null); } catch (Throwable ignored) { }
                }
            }
            if (inst == null) {
                for (Class<?> c = mc; c != null && c != Object.class && inst == null; c = c.getSuperclass()) {
                    java.lang.reflect.Field[] fs = c.getDeclaredFields();
                    for (int i = 0; i < fs.length && inst == null; i++) {
                        java.lang.reflect.Field f = fs[i];
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        if (!f.getType().equals(mc)) continue;
                        try { f.setAccessible(true); inst = f.get(null); } catch (Throwable ignored) { }
                    }
                }
            }
            if (inst == null) return null;
            Class<?> senderIface = Class.forName("net.minecraft.command.ICommandSender", false, cl);
            for (Class<?> c = mc; c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field[] fs = c.getDeclaredFields();
                for (int i = 0; i < fs.length; i++) {
                    java.lang.reflect.Field f = fs[i];
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!senderIface.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object p = f.get(inst);
                        if (p != null) return p;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            if (!chatFailLogged) {
                chatFailLogged = true;
                FrameSpike.note("[chat] 反射取玩家失败（同类失败本会话不再刷日志）: " + t);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Proxy 分派

    private static final class Handler implements InvocationHandler {
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String n = method.getName();
            Class<?>[] pt = method.getParameterTypes();
            Class<?> rt = method.getReturnType();
            int pc = pt.length;

            // Object 的三个方法：Proxy 也会路由进来，不处理会让 map 比较出怪事
            if (pc == 0 && "hashCode".equals(n)) return Integer.valueOf(31);
            if (pc == 1 && "equals".equals(n)) return Boolean.valueOf(proxy == args[0]);
            if (pc == 0 && "toString".equals(n)) return "FrameSpikeCommand";
            if (pc == 1 && "compareTo".equals(n)) return Integer.valueOf(0);

            if (pc == 0 && rt.equals(String.class)) return activeName;
            if (pc == 0 && List.class.isAssignableFrom(rt)) return activeAliases;
            if (pc == 1 && rt.equals(String.class)) return FrameSpike.usage();
            if (pc == 2 && rt.equals(void.class) && pt[1].equals(String[].class)) {
                rememberSender(args[0]);
                FrameSpike.handleCommand(args[0], (String[]) args[1]);
                return null;
            }
            /* 1.12.2 的 processCommand(MinecraftServer, ICommandSender, String[]) 是三参 void ——
               只有两参分派的话 1.12.2 执行命令会静默没反应（实测踩到） */
            if (pc == 3 && rt.equals(void.class) && pt[2].equals(String[].class)) {
                rememberSender(args[1]);
                FrameSpike.handleCommand(args[1], (String[]) args[2]);
                return null;
            }
            // 1.8.9: addTabCompletionOptions(ICommandSender, String[], BlockPos) -> List
            if (pc == 3 && List.class.isAssignableFrom(rt)) {
                return FrameSpike.tabComplete(args != null && args[1] instanceof String[]
                        ? (String[]) args[1] : null);
            }
            /* 1.12.2: getTabCompletions(MinecraftServer, ICommandSender, String[], BlockPos) -> List */
            if (pc == 4 && List.class.isAssignableFrom(rt)) {
                return FrameSpike.tabComplete(args != null && args[2] instanceof String[]
                        ? (String[]) args[2] : null);
            }
            if (pc == 1 && rt.equals(boolean.class)) return Boolean.TRUE;
            if (pc == 2 && rt.equals(boolean.class)) {
                /* 1.12.2 的 ICommand.checkPermission(MinecraftServer, ICommandSender) 是两参 boolean，
                   返回 FALSE 会变成「你没有使用此命令的权限」（实测踩到）；
                   isUsernameIndex(String[], int) 也是两参 boolean，TRUE 都对。 */
                return Boolean.TRUE;
            }
            if (List.class.isAssignableFrom(rt)) return Collections.emptyList();

            return defaultValue(rt);
        }
    }

    private static Object defaultValue(Class<?> rt) {
        if (!rt.isPrimitive()) return null;
        if (rt.equals(boolean.class)) return Boolean.FALSE;
        if (rt.equals(void.class)) return null;
        if (rt.equals(long.class)) return Long.valueOf(0L);
        if (rt.equals(double.class)) return Double.valueOf(0d);
        if (rt.equals(float.class)) return Float.valueOf(0f);
        if (rt.equals(short.class)) return Short.valueOf((short) 0);
        if (rt.equals(byte.class)) return Byte.valueOf((byte) 0);
        if (rt.equals(char.class)) return Character.valueOf((char) 0);
        return Integer.valueOf(0);
    }

    /** 供离线自检直接构造一个派发入口（不开游戏也能验形态分派）。 */
    public static Object buildForTest(Class<?> cmdIface) {
        return Proxy.newProxyInstance(cmdIface.getClassLoader(), new Class<?>[]{cmdIface}, new Handler());
    }

    /** 供离线自检注入 sender 接口 + 假实现类候选。 */
    public static void resolveChatSinkForTest(Class<?> senderIface, Class<?> textImpl) {
        try {
            Method unique = null;
            int count = 0;
            for (Method m : senderIface.getMethods()) {
                if (m.getParameterTypes().length == 1 && m.getReturnType().equals(void.class)) {
                    unique = m;
                    count++;
                }
            }
            if (count != 1 || unique == null || textImpl == null) {
                chatInfo = "test: 未解析";
                return;
            }
            for (Constructor<?> c : textImpl.getConstructors()) {
                Class<?>[] cp = c.getParameterTypes();
                if (cp.length == 1 && cp[0].equals(String.class)) {
                    chatMethod = unique;
                    textCtor = c;
                    chatInfo = "ok(test), " + unique.getParameterTypes()[0].getName() + " <- " + textImpl.getName();
                    return;
                }
            }
            chatInfo = "test: 无单参 String 构造器";
        } catch (Throwable t) {
            chatInfo = "test 解析失败: " + t;
        }
    }

    public static void resetForTest() {
        commandInstance = null;
        chatMethod = null;
        textCtor = null;
        gaveUp = false;
        activeName = Cfg.cmdName;
        activeAliases = aliasesFor(Cfg.cmdName);
        registerInfo = "not attempted";
        chatInfo = "not resolved";
    }

    static {
        activeName = Cfg.cmdName;
        activeAliases = aliasesFor(Cfg.cmdName);
    }

    /** 未被使用的占位，保持 API 明确 */
    public static String debugDump() {
        return "activeName=" + activeName + " aliases=" + activeAliases
                + " textCandidates=" + Arrays.toString(TEXT_IMPL_CANDIDATES);
    }
}
