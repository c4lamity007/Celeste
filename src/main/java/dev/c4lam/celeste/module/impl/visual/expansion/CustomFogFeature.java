package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.world.ClientWorld;

/** Immutable per-world fog preferences. No option, world, or global render-state mutation. */
public final class CustomFogFeature implements ModuleFeature {
    private record Snapshot(ClientWorld world, CustomFogMath.Settings settings) { }
    private static volatile Snapshot active;

    @Override public String name() { return "Custom Fog"; }
    @Override public void onEnable(ModuleContext context) { update(context); }
    @Override public void onStartTick(ModuleContext context) { update(context); }
    @Override public void onDisable(ModuleContext context) { active = null; }
    @Override public void onWorldChanged(ModuleContext context) { active = null; }

    private static void update(ModuleContext context) {
        ClientWorld world = context.client().world;
        active = world == null || context.client().player == null ? null : new Snapshot(world,
            new CustomFogMath.Settings(context.mode("Style", "Mist"), context.slider("Density", 0.45),
                context.slider("Distance", 160)));
    }

    static CustomFogMath.Settings settings(ClientWorld world) {
        Snapshot snapshot = active;
        return snapshot != null && snapshot.world == world ? snapshot.settings : null;
    }
}
