package net.minecraftforge.client;

import net.minecraft.command.ICommand;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线自检用的假 ClientCommandHandler。
 * 刻意复刻运行时的两个关键形态：
 *   1. public static final instance
 *   2. 注册方法返回 ICommand（不是 void），参数是 ICommand
 *   3. 重名会抛 RuntimeException（用来验证我们的预检确实生效）
 */
public class ClientCommandHandler {

    public static final ClientCommandHandler instance = new ClientCommandHandler();

    private final Map<String, ICommand> commandMap = new HashMap<String, ICommand>();

    public ClientCommandHandler() {
    }

    public ICommand func_71560_a(ICommand command) {
        String name = (String) call(command, "func_71517_b");
        if (commandMap.containsKey(name)) {
            throw new RuntimeException("Command " + name + " already registered");
        }
        commandMap.put(name, command);
        Object aliases = call(command, "func_71514_a");
        if (aliases instanceof Iterable) {
            for (Object a : (Iterable<?>) aliases) {
                String s = String.valueOf(a);
                if (commandMap.containsKey(s)) {
                    throw new RuntimeException("Alias " + s + " already registered");
                }
                commandMap.put(s, command);
            }
        }
        return command;
    }

    public Map func_71555_a() {
        return commandMap;
    }

    /** 测试用：预置一个占位命令，制造重名场景。 */
    public void preload(final String name) {
        ICommand dummy = (ICommand) java.lang.reflect.Proxy.newProxyInstance(
                ICommand.class.getClassLoader(),
                new Class<?>[]{ICommand.class},
                new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                        if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) return name;
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (m.getReturnType() == int.class) return Integer.valueOf(0);
                        return null;
                    }
                });
        commandMap.put(name, dummy);
    }

    private static Object call(ICommand c, String method) {
        try {
            return c.getClass().getMethod(method).invoke(c);
        } catch (Throwable t) {
            return null;
        }
    }
}
