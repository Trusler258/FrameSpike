package framespike.neoforge;

import framespike.Cfg;
import framespike.Cmd;
import framespike.FrameSpike;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge 1.20.1 入口（Maven 坐标是 net.neoforged:forge:1.20.1-47.1.x）。
 *
 * ⚠ 包名陷阱（白编译过一次的那条）：
 *   NeoForge 1.20.1 只是换了 groupId（net.neoforged:forge），**包名仍然是 net.minecraftforge.***。
 *   `net.neoforged.*` 要到 1.20.2 之后才出现。所以这里写的是
 *     net.minecraftforge.fml.common.Mod / net.minecraftforge.common.MinecraftForge /
 *     net.minecraftforge.eventbus.api.SubscribeEvent / net.minecraftforge.event.RegisterCommandsEvent
 *   写成 net.neoforged.* 会报一串「程序包不存在」，很容易误判成依赖没拉到。
 *
 * ⚠ @Mod 在 1.20.1 只有 value()，**没有 dist 元素** —— 客户端限定要在构造里自己判 FMLEnvironment.dist。
 *
 * ⚠ **本模块编译期零 MC 类引用**（NeoChat 反射、NeoEvents 用 NeoForge 的 TickEvent、
 *    NeoCommands 纯 Brigadier）：NeoForge 1.20.1 运行期的 MC 是 SRG 名且 mod 代码**不被 remap**，
 *    任何 MC 类的编译期引用都会 NoSuchMethodError（SharedConstants / Commands.literal 实测踩到）。
 *    检查点用 NeoForge 的 TickEvent（RenderTickEvent=帧 / ClientTickEvent=tick），
 *    标签与 1.8.9 / Fabric 同一套，报告页全平台共用。
 */
@Mod("framespike")
public class FrameSpikeNeoForge {

    public FrameSpikeNeoForge() {
        // 纯客户端工具：服务端什么都不做，避免碰到 net.minecraft.client.* 的类
        if (FMLEnvironment.dist != Dist.CLIENT) return;

        try {
            // gameDir 必须用 FMLPaths.GAMEDIR：new File(".") 取的是**进程工作目录**，
            // 被 PCL/HMCL 这类启动器拉起时那是启动器自己的目录 —— 日志会写到别处，
            // 而且一行报错都没有（实测：mod 加载了，日志却整个丢掉）。
            // FMLPaths 在 NeoForge 1.20.1 的包名是 net.minecraftforge.fml.loading.*（1.20.2 才改 neoforge）
            Cfg.setBaseDir(FMLPaths.GAMEDIR.get().toFile());
            // 版本串用 FML 自己的 API（FML 类不在 MC 的映射文件里，不会被 remap，跨版本稳定）。
            // 之前用 SharedConstants.getCurrentVersion()，那是 MC 类 —— NeoForge 1.20.1 的运行期 MC
            // 是 SRG 名，构造函数直接 NoSuchMethodError（实测踩到，三份日志都是它）。
            FrameSpike.setGameVersion(FMLLoader.versionInfo().mcVersion());
            FrameSpike.LOADER = "NeoForge " + FMLLoader.versionInfo().forgeVersion();
            Cfg.load();
            FrameSpike.note("=== " + FrameSpike.SIGN + " ===");
            FrameSpike.note("=== FrameSpike (NeoForge) constructed === " + Cfg.summary());
            // 1.13+ 原版每帧已经不再调 glFinish，Fabric/NeoForge 这边也没有字节码改写通道，
            // 所以 proxy 模式在这两个平台上根本不成立 —— 明确降级，别让 /fs status 假装"就绪"。
            if (Cfg.proxyGlFinish()) {
                Cfg.glFinishMode = "off";
                FrameSpike.note("[glfinish] 本平台没有 glFinish 改写通道（1.13+ 每帧也不再调 glFinish），"
                        + "glFinishMode 已强制降级为 off；其余功能不受影响。");
            }
            FrameSpike.prepareGlFinish();
            // 让 /fs version 显示真实环境（Forge 那条路由 transformer 上报，这里是加载器直接报）
            FrameSpike.reportEnv("NeoForge / TickEvent（" + FrameSpike.MC_VER + "）", "named");
            Cmd.setSink(new NeoChat());
        } catch (Throwable t) {
            FrameSpike.note("NeoForge init failed: " + t);
            return;
        }

        List<String> aliases = csv(Cfg.cmdAliases);
        Cmd.markExternalRegistration(Cfg.cmdName, aliases);
        FrameSpike.note("[cmd] NeoForge：/fs 在进入世界后可用（RegisterCommandsEvent）；"
                + "主菜单阶段请用 ctrl 文件通道: " + Cfg.ctrlFile);
        MinecraftForge.EVENT_BUS.register(NeoCommands.class);
        MinecraftForge.EVENT_BUS.register(NeoEvents.class);
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
