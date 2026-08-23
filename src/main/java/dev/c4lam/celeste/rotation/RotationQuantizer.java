package dev.c4lam.celeste.rotation;

import net.minecraft.util.math.MathHelper;

/** Reproduces vanilla mouse-sensitivity angular granularity. */
public final class RotationQuantizer {
    private RotationQuantizer() {
    }

    public static float stepForSensitivity(double sensitivity) {
        double clamped = MathHelper.clamp(sensitivity, 0.0D, 1.0D);
        double scaled = clamped * 0.6D + 0.2D;
        return (float) (scaled * scaled * scaled * 8.0D * 0.15D);
    }

    public static Rotation quantize(Rotation base, Rotation target, double sensitivity) {
        float step = stepForSensitivity(sensitivity);
        if (step <= 0.0F) {
            return target;
        }

        float yawDelta = MathHelper.wrapDegrees(target.yaw() - base.yaw());
        float pitchDelta = target.pitch() - base.pitch();
        float yaw = base.yaw() + Math.round(yawDelta / step) * step;
        float pitch = base.pitch() + Math.round(pitchDelta / step) * step;
        return new Rotation(yaw, pitch);
    }
}
