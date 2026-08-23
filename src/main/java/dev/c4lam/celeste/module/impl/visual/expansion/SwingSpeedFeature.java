package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/** Client-side local-player swing duration. It never changes attack cooldown or packets. */
public final class SwingSpeedFeature implements ModuleFeature {
    private static volatile boolean enabled;
    private static volatile int duration = 12;

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
    }

    public static int durationFor(LivingEntity entity, int vanillaDuration) {
        MinecraftClient client = MinecraftClient.getInstance();
        return enabled && entity == client.player ? duration : vanillaDuration;
    }

    private static void update(ModuleContext context) {
        double configured = context.slider("Speed", 6.0D);
        duration = MathHelper.clamp((int) Math.round(configured), 1, 20);
    }
}
