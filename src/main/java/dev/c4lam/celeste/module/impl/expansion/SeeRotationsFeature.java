package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.LivingEntity;

public final class SeeRotationsFeature implements ModuleFeature {
    private static volatile boolean enabled;

    @Override
    public String name() {
        return "See Rotations";
    }

    @Override
    public void onEnable(ModuleContext context) {
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
    }

    @Override
    public void onStartTick(ModuleContext context) {
    }

    @Override
    public void onActionTick(ModuleContext context) {
    }

    public static boolean shouldApply(MinecraftClient client) {
        return enabled
                && client.player != null
                && RotationManager.getInstance().isActive()
                && client.options.getPerspective().ordinal() != 0;
    }

    public static float getRenderYaw(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == client.player && shouldApply(client)) {
            return RotationManager.getInstance().serverYawOr(entity.getYaw());
        }
        return entity.getYaw();
    }

    public static float getRenderPitch(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == client.player && shouldApply(client)) {
            return RotationManager.getInstance().serverPitchOr(entity.getPitch());
        }
        return entity.getPitch();
    }

    public static boolean shouldQuantizeSensitivity() {
        MinecraftClient client = MinecraftClient.getInstance();
        return !shouldApply(client);
    }
}
