package framespike.fabric;

import framespike.Cfg;
import framespike.Cmd;
import framespike.FrameSpike;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 1.16.5+ 入口。
 *
 * 核心（Cfg / FrameSpike / Report / Cmd）跟 1.8.9 的 coremod 是**同一份源码**，
 * 这里只补加载器相关的那点事：
 *   1) 把 gameDir 与真实游戏版本告诉核心 —— 核心据此重算标题，并把 ini / 日志 / 报告
 *      落到 &lt;gameDir&gt;/framespike/（1.8.9 下还是 Lunar 那个老路径，行为不变）
 *   2) 装一个聊天出口（Cmd.Sink）—— 现代 MC 的聊天组件与 1.8.9 完全不是一回事
 *   3) 注册 /fs（走 Fabric API；没装 API 就降级到 ctrl 文件通道，不静默失效）
 *
 * 每帧的检查点由两个 mixin 打：MinecraftClient.render / tick、GameRenderer.render。
 * 用的是 1.8.9 那套标签名（runGameLoop / runTick），这样报告的分档、帧率、时间线
 * 跟老版本是同一个口径，报告页不用分叉。
 */
public class FrameSpikeFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        try {
            File gameDir = loader.getGameDir().toFile();
            Cfg.setBaseDir(gameDir);
            loader.getModContainer("minecraft").ifPresent(c ->
                    FrameSpike.setGameVersion(c.getMetadata().getVersion().getFriendlyString()));
            loader.getModContainer("fabricloader").ifPresent(c ->
                    FrameSpike.LOADER = "Fabric " + c.getMetadata().getVersion().getFriendlyString());
            Cfg.load();
            FrameSpike.note("=== " + FrameSpike.SIGN + " ===");
            FrameSpike.note("=== FrameSpike (Fabric) constructed === " + Cfg.summary());
            // 1.13+ 原版每帧已经不再调 glFinish，Fabric 这边也没有字节码改写通道，
            // 所以 proxy 模式在这个平台上根本不成立 —— 明确降级，别让 /fs status 假装"就绪"。
            if (Cfg.proxyGlFinish()) {
                Cfg.glFinishMode = "off";
                FrameSpike.note("[glfinish] 本平台没有 glFinish 改写通道（1.13+ 每帧也不再调 glFinish），"
                        + "glFinishMode 已强制降级为 off；其余功能不受影响。");
            }
            FrameSpike.prepareGlFinish();
            // 让 /fs version 显示真实环境（Forge 那条路由 transformer 上报，这里是加载器直接报）
            FrameSpike.reportEnv("Fabric / Yarn 映射（" + FrameSpike.MC_VER + "）", "named");
            Cmd.setSink(new FabricChat());
        } catch (Throwable t) {
            FrameSpike.note("Fabric init failed: " + t);
            return;
        }

        /*
         * 不管有没有 Fabric API，都要告诉核心「命令这条路不用再走 Forge 反射注册了」——
         * 否则 1.8.9 那套 ClientCommandHandler 探测会白跑 600 tick。
         */
        List<String> aliases = csv(Cfg.cmdAliases);
        Cmd.markExternalRegistration(Cfg.cmdName, aliases);
        if (loader.isModLoaded("fabric-command-api-v2")) {
            try {
                FabricCommands.register();
            } catch (Throwable t) {
                Cmd.gaveUp = true;
                FrameSpike.note("[cmd] Fabric 命令注册失败: " + t
                        + "  -> 改用 ctrl 文件通道（每行一条子命令）: " + Cfg.ctrlFile);
            }
        } else {
            Cmd.gaveUp = true;
            FrameSpike.note("[cmd] 没装 Fabric API -> /fs 不可用。"
                    + "改用 ctrl 文件通道（往这个文件里每行写一条子命令）: " + Cfg.ctrlFile);
        }

        mixinSelfCheck();
    }

    /**
     * 20 秒后如果 frames 还是 0，说明 mixin 一个都没命中 —— 换游戏版本时方法名对不上就会这样，
     * 而且**完全静默**（Mixin 那边我们故意用 require=0，宁可少注入也不许崩游戏）。
     * 这种时候必须自己喊出来，否则用户只会看到「装了个没反应的 mod」。
     */
    private static void mixinSelfCheck() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(20000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (FrameSpike.framesCount() == 0L) {
                    FrameSpike.note("[mixin] 20 秒内 frames=0 —— MinecraftClient.render 的注入没命中。"
                            + "当前游戏版本=" + FrameSpike.MC_VER
                            + "，请核对 gradle.properties 的 minecraft_version 与该版本的方法签名。");
                }
            }
        }, "FrameSpike-MixinCheck");
        t.setDaemon(true);
        t.start();
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
