package framespike.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import framespike.Cfg;
import framespike.Cmd;
import framespike.FrameSpike;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge 侧 /fs 注册：**纯 Brigadier**，零 MC 类引用。
 *
 * `Commands.literal` / `Commands.argument` 是 MC 类的静态助手（运行期 SRG 名，会 NoSuchMethodError，
 * 实测踩到）；Brigadier 自己的 `LiteralArgumentBuilder.literal` / `RequiredArgumentBuilder.argument`
 * 与映射无关，跨版本稳定。输出统一走 Cmd.Sink（发到玩家聊天栏），不碰 CommandSourceStack。
 *
 * 注意：泛型用裸类型（raw），避免编译产物里出现对 CommandSourceStack 的运行期引用。
 */
public final class NeoCommands {

    private NeoCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            final String name = (Cfg.cmdName == null || Cfg.cmdName.trim().length() == 0)
                    ? "fs" : Cfg.cmdName.trim();
            final List<String> aliases = csv(Cfg.cmdAliases);
            aliases.remove(name);

            event.getDispatcher().register(tree(name));
            for (int i = 0; i < aliases.size(); i++) {
                try {
                    event.getDispatcher().register(tree(aliases.get(i)));
                } catch (Throwable ignored) {
                    // 别名撞了就算了，主名字还在
                }
            }
            Cmd.markExternalRegistration(name, aliases);
            FrameSpike.note("[cmd] NeoForge 命令注册成功: /" + name
                    + (aliases.isEmpty() ? "" : " 别名 " + aliases)
                    + "  （全部子命令与当前值见 /" + name + " help）");
        } catch (Throwable t) {
            FrameSpike.note("[cmd] NeoForge 命令注册失败: " + t
                    + "  -> 改用 ctrl 文件通道: " + Cfg.ctrlFile);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static LiteralArgumentBuilder tree(String name) {
        return LiteralArgumentBuilder.literal(name)
                .executes(ctx -> {
                    run(new String[0]);
                    return 1;
                })
                .then(arg("args", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            String[] parts = split(b.getRemaining());
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

    /** Brigadier 自己的 RequiredArgumentBuilder.argument，不经过 MC 的 Commands 助手 */
    @SuppressWarnings("unchecked")
    private static RequiredArgumentBuilder arg(String name, StringArgumentType type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private static void run(String[] argv) {
        FrameSpike.handleCommand(null, argv);
    }

    /** 按空格切并保留末尾空串（Brigadier 行尾的空格是 Tab 补全的信号，不能丢）。 */
    static String[] split(String s) {
        if (s == null || s.length() == 0) return new String[0];
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
