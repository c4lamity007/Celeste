package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.hud.HudElementType;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Module switch for the animated enabled-module HUD list. */
public final class ArrayListFeature implements ModuleFeature {
    @Override
    public String name() {
        return "Array List";
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
        HudManager.getInstance().state(HudElementType.ARRAYLIST).setEnabled(enabled);
    }
}
