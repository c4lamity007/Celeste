package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerRotationMixin {
    @Redirect(
        method = "method_41929",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;getYaw()F"
        )
    )
    private float celeste$itemUsePacketYaw(PlayerEntity player) {
        if (MinecraftClient.getInstance().player != player) {
            return player.getYaw();
        }
        return RotationManager.getInstance().outgoingYawOr(player.getYaw());
    }

    @Redirect(
        method = "method_41929",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;getPitch()F"
        )
    )
    private float celeste$itemUsePacketPitch(PlayerEntity player) {
        if (MinecraftClient.getInstance().player != player) {
            return player.getPitch();
        }
        return RotationManager.getInstance().serverPitchOr(player.getPitch());
    }
}
