package dev.c4lam.celeste.module.impl.expansion;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
public final class SeeRotationsFeature {
    public static boolean enabled = true;
    public static boolean shouldApply(MinecraftClient client) {
        return enabled && client.player != null && RotationManager.getInstance().isActive();
    }
}
