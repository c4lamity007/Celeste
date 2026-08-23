package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity> {
    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void celeste$applyServerRotation(LivingEntity entity,
                                              LivingEntityRenderState state,
                                              float tickDelta,
                                              CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity != client.player) {
            return;
        }
        if (!SeeRotationsFeature.shouldApply(client)) {
            return;
        }
        state.bodyYaw = SeeRotationsFeature.getRenderYaw(entity);
        state.relativeHeadYaw = 0.0F;
        state.pitch = SeeRotationsFeature.getRenderPitch(entity);
    }
}
