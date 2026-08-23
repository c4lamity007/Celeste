package dev.c4lam.celeste.rotation;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/**
 * An immutable, canonical Minecraft rotation. Yaw is wrapped to [-180, 180)
 * and pitch is clamped to the range accepted by vanilla movement packets.
 */
public record Rotation(float yaw, float pitch) {
    public static final Rotation ZERO = new Rotation(0.0F, 0.0F);

    public Rotation {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Rotation components must be finite");
        }

        yaw = MathHelper.wrapDegrees(yaw);
        pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
    }

    public static Rotation lookingAt(Vec3d origin, Vec3d target) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");

        double x = target.x - origin.x;
        double y = target.y - origin.y;
        double z = target.z - origin.z;
        double horizontal = Math.hypot(x, z);

        if (horizontal < 1.0E-9D && Math.abs(y) < 1.0E-9D) {
            return ZERO;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(z, x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(y, horizontal));
        return new Rotation(yaw, pitch);
    }

    public static Rotation lookingAt(Entity observer, Vec3d target) {
        Objects.requireNonNull(observer, "observer");
        return lookingAt(observer.getEyePos(), target);
    }

    public static Rotation lookingAt(Entity observer, Entity target) {
        Objects.requireNonNull(target, "target");
        return lookingAt(observer, target.getBoundingBox().getCenter());
    }

    public Vec3d direction() {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        return new Vec3d(
            -Math.sin(yawRadians) * horizontal,
            -Math.sin(pitchRadians),
            Math.cos(yawRadians) * horizontal
        );
    }

    public float yawDeltaTo(Rotation target) {
        return MathHelper.wrapDegrees(target.yaw - yaw);
    }

    public float pitchDeltaTo(Rotation target) {
        return target.pitch - pitch;
    }

    public double angularDistanceTo(Rotation target) {
        return Math.hypot(yawDeltaTo(target), pitchDeltaTo(target));
    }

    public Rotation stepToward(Rotation target, float maximumYawStep, float maximumPitchStep) {
        if (!Float.isFinite(maximumYawStep) || maximumYawStep <= 0.0F
            || !Float.isFinite(maximumPitchStep) || maximumPitchStep <= 0.0F) {
            throw new IllegalArgumentException("Rotation steps must be positive and finite");
        }

        float yawStep = MathHelper.clamp(yawDeltaTo(target), -maximumYawStep, maximumYawStep);
        float pitchStep = MathHelper.clamp(pitchDeltaTo(target), -maximumPitchStep, maximumPitchStep);
        return new Rotation(yaw + yawStep, pitch + pitchStep);
    }
}
