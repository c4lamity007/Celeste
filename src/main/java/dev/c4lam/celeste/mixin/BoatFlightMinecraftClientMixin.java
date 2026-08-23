package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class BoatFlightMinecraftClientMixin {
    @Inject(
        method = "tick",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/network/packet/c2s/play/ClientTickEndC2SPacket;INSTANCE:Lnet/minecraft/network/packet/c2s/play/ClientTickEndC2SPacket;",
            opcode = Opcodes.GETSTATIC
        )
    )
    private void celeste$flushBoatFlightBeforeClientTickEnd(CallbackInfo callbackInfo) {
        BoatFlightFeature.onMovementPacketsComplete(MinecraftClient.getInstance().player);
    }
}
