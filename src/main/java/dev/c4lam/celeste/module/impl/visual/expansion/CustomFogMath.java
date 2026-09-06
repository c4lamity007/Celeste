package dev.c4lam.celeste.module.impl.visual.expansion;

/** Native distance-fog parameters, independent of Minecraft and GPU state. */
public final class CustomFogMath {
    private CustomFogMath() { }

    public record Color(double r, double g, double b, double a) { }
    public record Fog(Color color, double environmentalStart, double environmentalEnd,
                      double renderDistanceStart, double renderDistanceEnd, double skyEnd, double cloudEnd) { }
    public record Settings(String style, double density, double distance) { }
    public enum Medium { AIR, WATER, LAVA, POWDER_SNOW, OTHER }
    public record Conditions(boolean enabled, boolean overworld, Medium medium, boolean blindness,
                             boolean darkness, boolean bossFog, boolean voidDarkness) {
        public boolean allowed() {
            return enabled && overworld && medium == Medium.AIR && !blindness && !darkness
                && !bossFog && !voidDarkness;
        }
    }

    /** Unsafe environments and density zero return the original object, not a reconstructed approximation. */
    public static Fog apply(Fog vanilla, Settings settings, Conditions conditions) {
        if (vanilla == null || settings == null || conditions == null || !conditions.allowed() || !valid(vanilla))
            return vanilla;
        double density = bounded(settings.density, 0, 1, 0.45);
        if (density == 0 || vanilla.renderDistanceEnd < 16 || vanilla.environmentalEnd <= 96
            || vanilla.environmentalStart < 0) return vanilla;
        double distance = bounded(settings.distance, 32, 512, 160);
        // Native cylindrical render-distance fade is left untouched. Environmental
        // spherical fog adds a broad transition before that edge, never a nearer wall.
        double targetEnd = Math.min(vanilla.environmentalEnd,
            Math.min(vanilla.renderDistanceEnd * 0.96, distance * (1.35 - density * 0.45)));
        double targetStart = Math.max(8, Math.min(targetEnd - 0.5, distance * (0.30 - density * 0.14)));
        // Dense/negative-start environmental fog was left untouched above. In
        // ordinary air, an eight-block clear foreground also leaves the hand
        // unaffected even though vanilla draws it with the world fog buffer.
        // Blend from the original environment, not the shortened render-distance
        // target: an infinitesimal density must not suddenly add a far fog wall.
        double start = Math.max(8, lerp(vanilla.environmentalStart, targetStart, density));
        double end = lerp(vanilla.environmentalEnd, targetEnd, density);
        if (end <= start) return vanilla;

        int tint = palette(settings.style);
        Color original = vanilla.color;
        double brightness = Math.max(original.r, Math.max(original.g, original.b));
        double blend = density * 0.58;
        // Preserve vanilla night/cave brightness instead of making fog self-lit.
        Color color = new Color(
            lerp(original.r, ((tint >> 16) & 255) / 255.0 * brightness, blend),
            lerp(original.g, ((tint >> 8) & 255) / 255.0 * brightness, blend),
            lerp(original.b, (tint & 255) / 255.0 * brightness, blend), original.a);
        return new Fog(color, start, end, vanilla.renderDistanceStart, vanilla.renderDistanceEnd,
            vanilla.skyEnd, vanilla.cloudEnd);
    }

    public static int palette(String style) {
        if ("Dusk".equalsIgnoreCase(style)) return 0xB49BAD;
        if ("Frost".equalsIgnoreCase(style)) return 0xB8CFDF;
        return 0x99BCC2;
    }

    private static boolean valid(Fog fog) {
        Color color = fog.color;
        return color != null && unit(color.r) && unit(color.g) && unit(color.b) && unit(color.a)
            && Double.isFinite(fog.environmentalStart) && Double.isFinite(fog.environmentalEnd)
            && fog.environmentalStart < fog.environmentalEnd
            && Double.isFinite(fog.renderDistanceStart) && Double.isFinite(fog.renderDistanceEnd)
            && fog.renderDistanceStart < fog.renderDistanceEnd
            && Double.isFinite(fog.skyEnd) && Double.isFinite(fog.cloudEnd);
    }

    private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static double lerp(double from, double to, double fraction) { return from + (to - from) * fraction; }
    private static double bounded(double value, double low, double high, double fallback) {
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : fallback;
    }
}
