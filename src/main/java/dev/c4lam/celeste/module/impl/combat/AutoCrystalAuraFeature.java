package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleContext;

/** Silent sword-lift/crystal sequence, driven by observed target and server state. */
public final class AutoCrystalAuraFeature implements ModuleFeature {
    private final ExplosiveAuraRuntime runtime = new ExplosiveAuraRuntime(this, false);
    @Override
    public String name() {
        return "Auto Crystal Aura";
    }
    @Override public void onEnable(ModuleContext context) { runtime.clear(); }
    @Override public void onStartTick(ModuleContext context) { runtime.start(context); }
    @Override public void onActionTick(ModuleContext context) { runtime.action(context); }
    @Override public void onEndTick(ModuleContext context) { runtime.end(); }
    @Override public void onDisable(ModuleContext context) { runtime.clear(); }
    @Override public void onWorldChanged(ModuleContext context) { runtime.clear(); }
}
