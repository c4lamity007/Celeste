package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityRotationMixin {
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"
        )
    )
    private float celeste$mountedMovementPacketYaw(ClientPlayerEntity player) {
        return RotationManager.getInstance().outgoingYawOr(player.getYaw());
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"
        )
    )
    private float celeste$mountedMovementPacketPitch(ClientPlayerEntity player) {
        return RotationManager.getInstance().serverPitchOr(player.getPitch());
    }

    @Redirect(
        method = "sendMovementPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"
        )
    )
    private float celeste$movementPacketYaw(ClientPlayerEntity player) {
        return RotationManager.getInstance().outgoingYawOr(player.getYaw());
    }

    @Redirect(
        method = "sendMovementPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"
        )
    )
    private float celeste$movementPacketPitch(ClientPlayerEntity player) {
        return RotationManager.getInstance().serverPitchOr(player.getPitch());
    }
}
