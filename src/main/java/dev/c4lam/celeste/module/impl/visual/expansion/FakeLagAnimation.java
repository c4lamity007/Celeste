package dev.c4lam.celeste.module.impl.visual.expansion;

/** Render-only math, deliberately independent of Minecraft and network state. */
final class FakeLagAnimation {
    private FakeLagAnimation() { }

    static double factor(double seconds, double speed) {
        return seconds <= 0.0D ? 0.0D : -Math.expm1(-seconds * speed);
    }

    static double follow(double current, double target, double seconds, double speed) {
        return current + (target - current) * factor(seconds, speed);
    }

    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) wrapped -= 360.0D;
        if (wrapped < -180.0D) wrapped += 360.0D;
        return wrapped;
    }

    static double followYaw(double current, double target, double seconds) {
        return current + wrapDegrees(target - current) * factor(seconds, 20.0D);
    }

    static boolean shouldSnap(double distanceSquared) {
        return !Double.isFinite(distanceSquared) || distanceSquared > 16.0D;
    }
}
