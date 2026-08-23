package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.util.math.MathHelper;

/** Animated lightmap state; the user's gamma option is never read or written here. */
public final class FullbrightFeature implements ModuleFeature {
    private static final double RESPONSE_PER_SECOND = 10.0D;

    private static Mode mode = Mode.GAMMA;
    private static double strength;
    private static double target;
    private static long lastSampleNanos = System.nanoTime();

    @Override
    public String name() {
        return "Fullbright";
    }

    @Override
    public void onEnable(ModuleContext context) {
        setEnabledState(context, true);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        setEnabledState(context, true);
    }

    @Override
    public void onDisable(ModuleContext context) {
        setTarget(0.0D);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        synchronized (FullbrightFeature.class) {
            strength = 0.0D;
            target = 0.0D;
            lastSampleNanos = System.nanoTime();
        }
    }

    /** Adjusts only the temporary gamma scalar uploaded into this frame's lightmap UBO. */
    public static float adjustGammaLightmap(float vanilla) {
        double amount = sampleStrength();
        if (mode != Mode.GAMMA || amount <= 0.0D) {
            return vanilla;
        }
        return MathHelper.lerp((float) amount, vanilla, 1.0F);
    }

    /** Emulates the visual lightmap contribution without adding a status effect. */
    public static float adjustNightVisionLightmap(float vanilla) {
        double amount = sampleStrength();
        if (mode != Mode.NIGHT_VISION || amount <= 0.0D) {
            return vanilla;
        }
        return Math.max(vanilla, (float) amount);
    }

    private static void setEnabledState(ModuleContext context, boolean enabled) {
        synchronized (FullbrightFeature.class) {
            mode = Mode.from(context.mode("Mode", "Gamma"));
        }
        setTarget(enabled ? 1.0D : 0.0D);
    }

    private static synchronized void setTarget(double value) {
        advance(System.nanoTime());
        target = MathHelper.clamp(value, 0.0D, 1.0D);
    }

    private static synchronized double sampleStrength() {
        advance(System.nanoTime());
        return strength;
    }

    private static void advance(long now) {
        double elapsed = Math.min(0.1D, Math.max(0.0D, (now - lastSampleNanos) / 1_000_000_000.0D));
        lastSampleNanos = now;
        double blend = 1.0D - Math.exp(-RESPONSE_PER_SECOND * elapsed);
        strength += (target - strength) * blend;
        if (Math.abs(target - strength) < 1.0E-4D) {
            strength = target;
        }
    }

    private enum Mode {
        GAMMA,
        NIGHT_VISION;

        private static Mode from(String value) {
            return value != null && value.equalsIgnoreCase("Night Vision") ? NIGHT_VISION : GAMMA;
        }
    }
}
