package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Gliding adapter around the shared arbiter, not a competing rotation owner. */
public final class ElytraSilentAim {
    private ElytraSilentAim() { }

    /** Keeps the solved item angle exact without rotating the camera or applying ground movefix. */
    public static boolean submit(Object owner, Rotation target, int priority) {
        var player = MinecraftClient.getInstance().player;
        if (player == null || !player.isGliding()) return false;
        return RotationManager.getInstance().submit(RotationTask.builder(owner, target)
            .priority(priority).lifetimeTicks(1).smoothing(180, 90)
            .sensitivityQuantized(false).moveFix(MoveFixMode.OFF).build());
    }

    /**
     * Runs at LivingEntityRenderer.render HEAD, after PlayerEntityRenderer finishes updateGliding.
     * Setting banking at the parent updateRenderState tail would be overwritten by camera-based
     * banking in the subclass. All projected flight angles must use the same interpolated look.
     */
    public static void finishRender(LivingEntityRenderState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (!(state instanceof PlayerEntityRenderState pose) || player == null
            || pose.id != player.getId() || !pose.isGliding || !player.isGliding()
            || !SeeRotationsFeature.shouldApply(client)) return;
        float tickDelta = MathHelper.clamp(pose.age - player.age, 0, 1);
        Rotation look = RotationManager.getInstance().renderRotation(tickDelta);
        Vec3d movement = player.getState().getVelocity().lerp(player.getVelocity(), tickDelta);
        Vec3d direction = player.getRotationVector(look.pitch(), look.yaw());
        ElytraRotationMath.Bank bank = ElytraRotationMath.bank(movement.x, movement.z, direction.x, direction.z);
        pose.bodyYaw = look.yaw();
        pose.relativeHeadYaw = 0;
        pose.pitch = look.pitch();
        pose.applyFlyingRotation = bank.apply();
        pose.flyingRotation = bank.radians();
        // Leave gliding progress, the vanilla -45-degree gliding head, wings and actual movement alone.
    }
}
