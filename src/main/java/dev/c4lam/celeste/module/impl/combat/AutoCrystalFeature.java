package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Villians-style held-use crystal sequence backed by the shared coordinator. */
public final class AutoCrystalFeature implements ModuleFeature {
    @Override
    public String name() {
        return "Auto Crystal";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        CrystalCombatCoordinator.INSTANCE.onStartTick(context);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        CrystalCombatCoordinator.INSTANCE.onActionTick(context);
    }

    @Override
    public void onDisable(ModuleContext context) {
        CrystalCombatCoordinator.INSTANCE.onModuleDisabled(
            CrystalCombatCoordinator.ModuleKind.AUTO_CRYSTAL, context.client());
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        CrystalCombatCoordinator.INSTANCE.onWorldChanged(context.client());
    }
}
