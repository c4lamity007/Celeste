package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/** Client-side local-player swing duration. It never changes attack cooldown or packets. */
public final class SwingSpeedFeature implements ModuleFeature {
    private static volatile boolean enabled;
    private static volatile int duration = 6;
    private static volatile String mode = "Custom";
    private static volatile boolean easeChanges = true;
    private static LivingEntity trackedPlayer;
    private static int activeDuration;
    private static int previousSwingTick;
    private static boolean swingActive;

    @Override
    public String name() {
        return "Swing Speed";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        trackedPlayer = null;
        swingActive = false;
    }

    public static int durationFor(LivingEntity entity, int vanillaDuration) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity != client.player) return vanillaDuration;
        int requested = enabled ? switch (mode) {
            case "Vanilla" -> vanillaDuration;
            case "Cinematic" -> MathHelper.clamp(Math.round(vanillaDuration * 1.5F), 1, 30);
            case "Snappy" -> MathHelper.clamp(Math.round(vanillaDuration * 0.65F), 1, 20);
            default -> duration;
        } : vanillaDuration;
        requested = Math.max(1, requested);
        if (trackedPlayer != entity) {
            trackedPlayer = entity;
            swingActive = false;
        }

        int swingTick = entity.handSwingTicks;
        // LivingEntity resets handSwingTicks to -1 when a fresh swing is accepted.
        // Latching its duration prevents an options change (or disable) from
        // changing the progress denominator halfway through that same animation.
        if (!entity.handSwinging || !swingActive || swingTick < previousSwingTick) {
            activeDuration = requested;
        }
        swingActive = entity.handSwinging;
        previousSwingTick = swingTick;
        return easeChanges && swingActive ? activeDuration : requested;
    }

    private static void update(ModuleContext context) {
        double configured = context.slider("Speed", 6.0D);
        duration = MathHelper.clamp((int) Math.round(configured), 1, 20);
        mode = context.mode("Mode", "Custom");
        easeChanges = context.toggle("Ease changes", true);
    }
}
