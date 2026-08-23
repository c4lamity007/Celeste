package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.rotation.MoveFix;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMoveFixMixin {
    @Redirect(
        method = "calcGlidingVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;")
    )
    private Vec3d celeste$glidingRotationVector(LivingEntity entity) {
        RotationManager rotations = RotationManager.getInstance();
        if (MinecraftClient.getInstance().player != entity
            || !rotations.isActive()
            || rotations.moveFixMode() == MoveFixMode.OFF) {
            return entity.getRotationVector();
        }
        return rotations.serverRotation().direction();
    }

    @Redirect(
        method = "calcGlidingVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getPitch()F")
    )
    private float celeste$glidingPitch(LivingEntity entity) {
        RotationManager rotations = RotationManager.getInstance();
        if (MinecraftClient.getInstance().player != entity
            || !rotations.isActive()
            || rotations.moveFixMode() == MoveFixMode.OFF) {
            return entity.getPitch();
        }
        return rotations.serverRotation().pitch();
    }

    @Redirect(
        method = "jump",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F")
    )
    private float celeste$sprintJumpYaw(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        float cameraYaw = entity.getYaw();
        if (client.player != entity || !rotations.isActive() || mode == MoveFixMode.OFF) {
            return cameraYaw;
        }
        return MoveFix.sprintJumpYaw(cameraYaw, rotations.serverRotation().yaw(), mode);
    }
}
