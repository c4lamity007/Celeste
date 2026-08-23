package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.hud.HudElementType;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Module switch for Celeste's existing UID watermark widget. */
public final class WatermarkFeature implements ModuleFeature {
    @Override
    public String name() {
        return "Watermark";
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
        HudManager.getInstance().state(HudElementType.WATERMARK).setEnabled(enabled);
    }
}
