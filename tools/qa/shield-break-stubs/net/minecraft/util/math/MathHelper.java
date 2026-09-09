package net.minecraft.util.math;
public final class MathHelper {
    private MathHelper() {}
    public static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
