package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.FakeFlyFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FakeFlyCameraMixin {
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow public abstract Vec3d getCameraPos();
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void celeste$applyFlightRigCamera(World area, Entity focusedEntity, boolean thirdPerson,
                                                boolean inverseView, float tickProgress, CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        Vec3d visualEye = FakeFlyFeature.cameraPosition(tickProgress);
        if (player == null || focusedEntity != player || visualEye == null) {
            return;
        }
        Vec3d playerEye = player.getCameraPosVec(tickProgress);
        Vec3d cameraOffset = getCameraPos().subtract(playerEye);
        float yawOffset = MathHelper.wrapDegrees(getYaw() - player.getYaw());
        float pitchOffset = getPitch() - player.getPitch();
        setPos(visualEye.add(cameraOffset));
        setRotation(FakeFlyFeature.cameraYaw() + yawOffset, FakeFlyFeature.cameraPitch() + pitchOffset);
    }
}
