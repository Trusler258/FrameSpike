package framespike.fabric.mixin;

import framespike.Cfg;
import framespike.FrameSpike;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染入口的检查点。
 *
 * 1.8.9 里这一段在 EntityRenderer，1.13+ 搬到了 GameRenderer —— 这正是「多版本」要各写一份的地方。
 * 描述符 (FJZ)V = (float, long, boolean)，全是原始类型，跨版本稳定，不需要 refmap 参与 remap。
 *
 * 时间线里两个检查点之间的间隔就是「渲染耗时」，跟 1.8.9 的 renderWorld 段是同一个用法。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render(FJZ)V", at = @At("HEAD"), require = 0)
    private void framespike$render(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (Cfg.enabled) FrameSpike.checkpoint("grender");
    }

    /* 渲染世界那一段 —— 时间轴里的「帧内阶段」。这个描述符带 MC 类型，
       靠 refmap remap（1.20.1 已验证 refmap 能正确映射）；require=0 保证
       换版本时名字对不上只是少一个检查点，不会崩。 */
    @Inject(method = "renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("HEAD"), require = 0)
    private void framespike$renderWorld(float tickDelta, long limitTime,
                                        net.minecraft.client.util.math.MatrixStack matrices, CallbackInfo ci) {
        if (Cfg.enabled) FrameSpike.checkpoint("renderWorld");
    }
}
