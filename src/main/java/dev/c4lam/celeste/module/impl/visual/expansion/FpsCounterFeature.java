package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.hud.HudElementType;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Module switch for Celeste's real draggable FPS HUD widget. */
public final class FpsCounterFeature implements ModuleFeature {
    @Override
    public String name() {
        return "FPS Counter";
    }

    @Override
    public void onEnable(ModuleContext context) {
        set(true);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        set(true);
    }

    @Override
    public void onDisable(ModuleContext context) {
        set(false);
    }

    static void set(boolean enabled) {
        HudManager.getInstance().state(HudElementType.FPS).setEnabled(enabled);
    }
}
