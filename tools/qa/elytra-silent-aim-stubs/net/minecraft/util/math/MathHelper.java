package net.minecraft.util.math;

/** Minimal vanilla math boundary for the isolated render-adapter check. */
public final class MathHelper {
    private MathHelper() { }
    public static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    public static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
    public static float wrapDegrees(float value) {
        float result = value % 360;
        if (result >= 180) result -= 360;
        if (result < -180) result += 360;
        return result;
    }
    public static float sin(double angle) { return table((long) (angle * 10430.378350470453)); }
    public static float cos(double angle) { return table((long) (angle * 10430.378350470453 + 16384)); }
    private static float table(long index) { return (float) Math.sin((index & 65535L) / 10430.378350470453); }
}
