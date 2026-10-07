package framespike.fabric.mixin;

import framespike.Cfg;
import framespike.FrameSpike;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端主循环的两个检查点。
 *
 * 标签名刻意跟 1.8.9 coremod 用的一样（runGameLoop / runTick）——
 * 核心的帧率、tick 率、分档、时间线全按这两个标签统计，同口径才能共用同一份报告页。
 *
 * 为什么只挑「参数全是原始类型」的方法：
 *   描述符里一旦出现 MC 类型，就得靠 refmap 把 Yarn 名 remap 到 intermediary，
 *   换版本时容易在 Mixin 应用阶段炸掉；原始类型描述符跨版本是稳定的。
 *   require = 0 是刻意的：**宁可少注入，也绝不让游戏起不来**。
 *   真没命中会被 FrameSpikeFabric 的 20 秒自检喊出来（frames=0）。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "render(Z)V", at = @At("HEAD"), require = 0)
    private void framespike$frame(boolean tick, CallbackInfo ci) {
        if (Cfg.enabled) FrameSpike.checkpoint("runGameLoop");
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void framespike$tick(CallbackInfo ci) {
        if (Cfg.enabled) FrameSpike.checkpoint("runTick");
    }
}
