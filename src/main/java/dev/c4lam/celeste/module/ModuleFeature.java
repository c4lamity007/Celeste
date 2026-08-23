package dev.c4lam.celeste.module;

import net.minecraft.client.MinecraftClient;

/** Client-thread gameplay behavior attached to one Click GUI module. */
public interface ModuleFeature {
    String name();

    default void onEnable(ModuleContext context) {
    }

    default void onDisable(ModuleContext context) {
    }

    /** Called before Minecraft's world/player tick. Rotation requests belong here. */
    default void onStartTick(ModuleContext context) {
    }

    /**
     * Called during START_CLIENT_TICK after rotations have been resolved.
     * Primary vanilla interactions are submitted here and flushed before the
     * game's player tick begins.
     */
    default void onActionTick(ModuleContext context) {
    }

    /** Called at END_CLIENT_TICK for presentation/input lease cleanup only. */
    default void onEndTick(ModuleContext context) {
    }

    /** Called once per rendered frame. Visible camera aim belongs here. */
    default void onRenderFrame(ModuleContext context) {
    }

    default void onWorldChanged(ModuleContext context) {
    }

    default boolean canRun(MinecraftClient client) {
        return client.player != null && client.world != null && client.interactionManager != null;
    }
}
