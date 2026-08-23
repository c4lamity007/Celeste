package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class BoatFlightNetworkMixin {
    @Inject(method = "onPlayerPositionLook", at = @At("HEAD"))
    private void celeste$observeBoatFlightCorrection(PlayerPositionLookS2CPacket packet, CallbackInfo callbackInfo) {
        BoatFlightFeature.onServerPacket(packet);
    }

    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void celeste$completeBoatFlightCorrection(PlayerPositionLookS2CPacket packet, CallbackInfo callbackInfo) {
        BoatFlightFeature.onServerTeleportApplied();
    }
}
