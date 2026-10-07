package framespike.neoforge;

import framespike.Cfg;
import framespike.FrameSpike;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 检查点：用 NeoForge 自己的 TickEvent，不用 mixin。
 *
 * 为什么不用 mixin：NeoForge 1.20.1 运行期的 MC 是 SRG 名（mod 列表里是 client-...-srg.jar），
 * 而 mod 代码**不被 remap** —— mixin 的 official 名（Minecraft.runTick）不会命中（实测 frames=0）。
 * TickEvent 是 NeoForge 自己的类，与 MC 的映射无关，跨版本稳定。
 *
 * 注册由 FrameSpikeNeoForge 显式调 MinecraftForge.EVENT_BUS.register（不用 @EventBusSubscriber，
 * 它的 Bus 枚举在 NeoForge 1.20.1 里名字对不上）。
 *
 * 检查点标签与 1.8.9 / Fabric 同一套：runGameLoop（每帧）/ runTick（每 tick），
 * 这样帧率、tick 率、分档、时间线、报告页全平台共用。
 */
public final class NeoEvents {

    private NeoEvents() {}

    /** 每个渲染帧一次（START 阶段）= 帧检查点 */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (Cfg.enabled) FrameSpike.checkpoint("runGameLoop");
    }

    /** 每个客户端 tick 一次（END 阶段）= tick 检查点 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Cfg.enabled) FrameSpike.checkpoint("runTick");
    }
}
