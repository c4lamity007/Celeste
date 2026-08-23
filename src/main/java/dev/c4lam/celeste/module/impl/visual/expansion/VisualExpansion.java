package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleManager;

/** Registers the nine clean-room visual modules and their shared render services. */
public final class VisualExpansion {
    private static boolean registered;

    private VisualExpansion() {
    }

    public static synchronized void register(ModuleManager manager) {
        if (registered) return;
        registered = true;

        TrajectoriesFeature trajectories = new TrajectoriesFeature();
        OreEspExpansionFeature ores = new OreEspExpansionFeature();
        ArrowEspFeature arrows = new ArrowEspFeature();
        ItemEspFeature items = new ItemEspFeature();
        TargetEspFeature targets = new TargetEspFeature();

        manager.register(trajectories);
        manager.register(ores);
        manager.register(arrows);
        manager.register(items);
        manager.register(targets);
        manager.register(new SwingSpeedFeature());
        manager.register(new FpsCounterFeature());
        manager.register(new WatermarkFeature());
        manager.register(new ArrayListFeature());

        VisualExpansionRenderer.initialize(trajectories, ores, arrows, items, targets);
    }
}
