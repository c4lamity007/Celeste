package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Active-state bridge for features invoked from a narrowly scoped vanilla interaction hook. */
abstract class InterceptFeature implements ModuleFeature {
    private boolean active;

    @Override
    public final void onEnable(ModuleContext context) {
        active = true;
        refresh(context);
        enabled(context);
    }

    @Override
    public final void onStartTick(ModuleContext context) {
        refresh(context);
        tick(context);
    }

    @Override
    public final void onDisable(ModuleContext context) {
        active = false;
        disabled(context);
        GameplayFoundation.releaseOwner(this);
    }

    @Override
    public final void onWorldChanged(ModuleContext context) {
        active = false;
        disabled(context);
        GameplayFoundation.releaseOwner(this);
    }

    final boolean active() {
        return active;
    }

    protected abstract void refresh(ModuleContext context);

    protected void enabled(ModuleContext context) {
    }

    protected void tick(ModuleContext context) {
    }

    protected void disabled(ModuleContext context) {
    }
}
