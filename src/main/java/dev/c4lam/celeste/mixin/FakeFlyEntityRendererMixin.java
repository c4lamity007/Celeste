package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.FakeFlyFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class FakeFlyEntityRendererMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void celeste$keepFlightRigBodyVisible(Entity entity, Frustum frustum,
                                                   double x, double y, double z,
                                                   CallbackInfoReturnable<Boolean> callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == client.player && FakeFlyFeature.shouldRenderSimulatedBody(client)) {
            callbackInfo.setReturnValue(true);
        }
    }
}
