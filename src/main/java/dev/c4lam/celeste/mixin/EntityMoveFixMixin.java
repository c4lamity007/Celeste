package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.rotation.MoveFix;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public abstract class EntityMoveFixMixin {
    @Redirect(
        method = "updateVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F")
    )
    private float celeste$movementYaw(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        float cameraYaw = entity.getYaw();
        if (client.player != entity || !rotations.isActive() || mode == MoveFixMode.OFF) {
            return cameraYaw;
        }
        return MoveFix.movementYaw(cameraYaw, rotations.serverRotation().yaw(), mode);
    }
}
