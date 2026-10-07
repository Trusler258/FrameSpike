package framespike.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import framespike.Cfg;
import framespike.Cmd;
import framespike.FrameSpike;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 侧的 /fs 注册。
 *
 * 关键取舍：**只注册一条 greedy 字符串参数**，把所有子命令原样转给 1.8.9 那套命令引擎
 * （FrameSpike.handleCommand）。理由：
 *   - 15 个子命令的语义、当前值、帮助、Tab 补全在核心都已经写好了，用 Brigadier 再实现一遍
 *     就是两份会漂移的真相
 *   - 换游戏版本 / 换加载器时这里零改动
 * 代价是 Brigadier 不认识子命令（补全得自己提供），所以下面挂了 suggests。
 *
 * 这个类只在 Fabric API 存在时才会被加载（软依赖），缺 API 时不会触发 NoClassDefFoundError。
 */
public final class FabricCommands {

    private FabricCommands() {}

    public static void register() {
        final String name = (Cfg.cmdName == null || Cfg.cmdName.trim().length() == 0)
                ? "fs" : Cfg.cmdName.trim();
        final List<String> aliases = csv(Cfg.cmdAliases);
        aliases.remove(name);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(tree(name));
            for (int i = 0; i < aliases.size(); i++) {
                try {
                    dispatcher.register(tree(aliases.get(i)));
                } catch (Throwable ignored) {
                    // 别名跟别的 mod 撞了就算了，主名字还在
                }
            }
            Cmd.markExternalRegistration(name, aliases);
            FrameSpike.note("[cmd] Fabric 命令注册成功: /" + name
                    + (aliases.isEmpty() ? "" : " 别名 " + aliases)
                    + "  （全部子命令与当前值见 /" + name + " help）");
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String name) {
        return ClientCommandManager.literal(name)
                .executes(ctx -> {
                    run(new String[0]);
                    return 1;
                })
                .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            String[] parts = split(b.getRemaining());
                            // 末段是「正在敲的那个词」，前面是已确定的部分 —— 正好是核心约定的形状
                            String prefix = parts.length == 0 ? "" : parts[parts.length - 1].toLowerCase();
                            List<String> cands = FrameSpike.tabComplete(parts);
                            for (int i = 0; i < cands.size(); i++) {
                                String c = cands.get(i);
                                if (prefix.length() == 0 || c.toLowerCase().startsWith(prefix)) b.suggest(c);
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            run(split(StringArgumentType.getString(ctx, "args")));
                            return 1;
                        }));
    }

    /** sender 传 null：核心把 null 认成「来自 ctrl 文件通道」，而回显统一走 Cmd.Sink。 */
    private static void run(String[] argv) {
        FrameSpike.handleCommand(null, argv);
    }

    /**
     * 按空格切，保留末尾空串（Brigadier 的 getRemaining 在行尾会带一个空格，
     * 丢掉它 Tab 就不知道该补什么了）。
     */
    static String[] split(String s) {
        if (s == null) return new String[0];
        if (s.length() == 0) return new String[0];
        return s.split(" ", -1);
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            String v = parts[i].trim();
            if (v.length() > 0 && !out.contains(v)) out.add(v);
        }
        return out;
    }
}
