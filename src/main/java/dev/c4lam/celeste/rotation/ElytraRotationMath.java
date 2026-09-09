package dev.c4lam.celeste.rotation;

/** Render-only interpolation and vanilla elytra banking; never changes flight or packet aim. */
public final class ElytraRotationMath {
    private static final double MIN_HORIZONTAL_SQUARED = (double) 1.0E-5F;
    private static final Bank NO_BANK = new Bank(false, 0.0F);

    private ElytraRotationMath() { }

    public record Bank(boolean apply, float radians) { }

    /** Interpolate the shortest turn, retaining the previous yaw's continuous 360-degree phase. */
    public static float yaw(float previous, float current, float tickDelta) {
        if (!Float.isFinite(previous)) previous = Float.isFinite(current) ? current : 0.0F;
        if (!Float.isFinite(current)) current = previous;
        // Double subtraction avoids overflow between otherwise finite float endpoints.
        double change = ((double) current - previous) % 360.0D;
        if (change >= 180.0D) change -= 360.0D;
        if (change < -180.0D) change += 360.0D;
        return (float) (previous + change * frame(tickDelta));
    }

    public static float pitch(float previous, float current, float tickDelta) {
        if (!Float.isFinite(previous)) previous = Float.isFinite(current) ? current : 0.0F;
        if (!Float.isFinite(current)) current = previous;
        previous = Math.max(-90.0F, Math.min(90.0F, previous));
        current = Math.max(-90.0F, Math.min(90.0F, current));
        return previous + (current - previous) * frame(tickDelta);
    }

    /** Matches PlayerEntityRenderer.updateGliding, including its absolute-dot backward-look rule. */
    public static Bank bank(double velocityX, double velocityZ, double lookX, double lookZ) {
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityZ)
            || !Double.isFinite(lookX) || !Double.isFinite(lookZ)) return NO_BANK;
        double velocitySquared = velocityX * velocityX + velocityZ * velocityZ;
        double lookSquared = lookX * lookX + lookZ * lookZ;
        if (!Double.isFinite(velocitySquared) || !Double.isFinite(lookSquared)
            || velocitySquared <= MIN_HORIZONTAL_SQUARED || lookSquared <= MIN_HORIZONTAL_SQUARED) return NO_BANK;
        double velocityLength = Math.sqrt(velocitySquared), lookLength = Math.sqrt(lookSquared);
        double dot = velocityX / velocityLength * (lookX / lookLength)
            + velocityZ / velocityLength * (lookZ / lookLength);
        double cross = velocityX * lookZ - velocityZ * lookX;
        double angle = Math.signum(cross) * Math.acos(Math.min(1.0D, Math.abs(dot)));
        if (!Double.isFinite(angle)) return NO_BANK;
        return new Bank(true, (float) angle);
    }

    private static float frame(float tickDelta) {
        return Float.isFinite(tickDelta) ? Math.max(0.0F, Math.min(1.0F, tickDelta)) : 0.0F;
    }
}
