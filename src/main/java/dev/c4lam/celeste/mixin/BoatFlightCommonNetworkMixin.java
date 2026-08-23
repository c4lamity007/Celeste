package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class BoatFlightCommonNetworkMixin {
    @Inject(method = "onPing", at = @At("HEAD"))
    private void celeste$observeBoatFlightTransaction(CommonPingS2CPacket packet, CallbackInfo callbackInfo) {
        BoatFlightFeature.onServerPacket(packet);
    }
}
