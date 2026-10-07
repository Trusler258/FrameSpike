/*
 * FrameSpike - Minecraft 1.8.9 / Lunar Client 帧暂停取证 coremod
 * Author: Trusler
 */
package framespike;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * coremod 入口。MANIFEST.MF 里写 FMLCorePlugin: framespike.FrameSpikePlugin，
 * 放进 mods/forge-1.8.9/ 即被 FML 的 CoreModManager 发现（和 CrashAssistant 同一条路径）。
 */
/*
 * 刻意**不写** @IFMLLoadingPlugin.MCVersion：
 * 反编译本机 Forge 1.8.9 的 CoreModManager 确认，它对这个注解是严格字符串相等 ——
 *     if (requiredMCVersion != null && !FMLInjectionData.mccversion.equals(requiredMCVersion.value())) {
 *         FMLRelaunchLog.log(ERROR, "The coremod %s is requesting minecraft version %s and minecraft is %s. It will be ignored.");
 *         return null;   // 真的跳过这个 coremod，不是只记日志
 *     }
 * 而且它**不支持版本范围**（写 "[1.8.9,1.12.2]" 只会变成字符串不等）。
 * 所以一个 jar 想同时覆盖 1.8.9 与 1.12.2，就只能省掉这个注解 —— 代价是 FML 会打一行
 * WARN "does not have a MCVersion annotation, it may cause issues"（无害）。
 * "名字对不对" 由 transformer 自己兜：命中数会打进日志（hooks=X/N），
 * 0 命中时还会把该类的真实方法名 dump 出来，比一个静态版本锁有用得多。
 */
@IFMLLoadingPlugin.TransformerExclusions({"framespike."})
public class FrameSpikePlugin implements IFMLLoadingPlugin {

    public FrameSpikePlugin() {
        try {
            FrameSpike.LOADER = "Forge coremod";
            Cfg.load();
            FrameSpike.note("=== " + FrameSpike.SIGN + " ===");
            FrameSpike.note("=== FrameSpike coremod constructed === " + Cfg.summary());
            // 必须在任何 transform 之前决定 glFinish 走不走代理：解析不到句柄就把 mode 降级为 off
            FrameSpike.prepareGlFinish();
        } catch (Throwable t) {
            try { FrameSpike.note("coremod init failed: " + t); } catch (Throwable ignored) { }
        }
    }

    public String[] getASMTransformerClass() {
        return new String[]{"framespike.FrameSpikeTransformer"};
    }

    public String getModContainerClass() {
        return null;
    }

    public String getSetupClass() {
        return null;
    }

    /**
     * FML 在 coremod 构造之后调这里，data 里有 mcLocation（游戏目录）——
     * 这是 coremod 阶段**唯一可靠**的 gameDir 来源（比 -D 参数和 CWD 都稳）。
     * 1.8.9 与 1.12.2 都走这里：Lunar 1.8 的 mcLocation 就是 profiles/1.8（已核实它有
     * options.txt/servers.dat，是 gameDir），路径与旧版一致；1.12.2 则落进它自己的游戏目录。
     */
    public void injectData(Map<String, Object> data) {
        try {
            Object mcLoc = data == null ? null : data.get("mcLocation");
            if (mcLoc instanceof File) {
                File game = (File) mcLoc;
                Cfg.setBaseDir(game);
                Cfg.load();                    // 重新定位 ini / 日志
                FrameSpike.resetLogWriter();   // 已打开的 writer 换到 gameDir 下的新路径
                FrameSpike.note("gameDir 来自 FML injectData.mcLocation = " + game.getAbsolutePath());
            }
            // 同一个 jar 要同时跑 1.8.9 和 1.12.2 —— 从 FMLInjectionData.mccversion（静态字段，
            // CoreModManager 内部就用它做版本校验）读真实 MC 版本，MOD_TITLE/MC_VER 才不会一直显示 1.8.9
            try {
                Class<?> inj = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData");
                Field f = inj.getField("mccversion");
                Object v = f.get(null);
                if (v instanceof String && ((String) v).length() > 0) {
                    FrameSpike.setGameVersion((String) v);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            try { FrameSpike.note("injectData 处理失败: " + t); } catch (Throwable ignored) { }
        }
    }

    public String getAccessTransformerClass() {
        return null;
    }
}
