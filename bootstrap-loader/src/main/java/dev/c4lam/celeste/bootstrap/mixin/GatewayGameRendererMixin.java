package dev.c4lam.celeste.bootstrap.mixin;

import dev.c4lam.celeste.render.GuiRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flushes the gateway after Minecraft has composed its GUI and menu blur. */
@Mixin(GameRenderer.class)
public abstract class GatewayGameRendererMixin {
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
            shift = At.Shift.AFTER
        )
    )
    private void celesteBootstrap$flushGateway(CallbackInfo ci) {
        GuiRenderer.getInstance().flush();
    }
}
