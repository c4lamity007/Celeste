package dev.c4lam.celeste.module;

import dev.c4lam.celeste.module.impl.combat.AnchorAuraFeature;
import dev.c4lam.celeste.module.impl.combat.AnchorMacroFeature;
import dev.c4lam.celeste.module.impl.combat.AutoTrapFeature;
import dev.c4lam.celeste.module.impl.combat.AutoCrystalAuraFeature;
import dev.c4lam.celeste.module.impl.combat.AutoCrystalFeature;
import dev.c4lam.celeste.module.impl.combat.AutoHitCrystalFeature;
import dev.c4lam.celeste.module.impl.combat.AutoSwordFeature;
import dev.c4lam.celeste.module.impl.combat.CombatOverlayRenderer;
import dev.c4lam.celeste.module.impl.combat.DamageRenderFeature;
import dev.c4lam.celeste.module.impl.combat.MaceAuraFeature;
import dev.c4lam.celeste.module.impl.combat.MaceSwapFeature;
import dev.c4lam.celeste.module.impl.combat.ShieldBreakFeature;
import dev.c4lam.celeste.module.impl.combat.SmashGuideFeature;
import dev.c4lam.celeste.module.impl.combat.SurroundFeature;
import dev.c4lam.celeste.module.impl.combat.SwordAuraFeature;
import dev.c4lam.celeste.module.impl.combat.TriggerFeature;
import dev.c4lam.celeste.module.impl.combat.WindChargeFeature;
import dev.c4lam.celeste.module.impl.combat.TBOTFeature;
import dev.c4lam.celeste.module.impl.movement.ElytraFeature;
import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import dev.c4lam.celeste.module.impl.movement.FakeFlyFeature;
import dev.c4lam.celeste.module.impl.movement.SafeWalkFeature;
import dev.c4lam.celeste.module.impl.movement.SprintFeature;
import dev.c4lam.celeste.module.impl.visual.EntityEspFeature;
import dev.c4lam.celeste.module.impl.visual.FullbrightFeature;
import dev.c4lam.celeste.module.impl.visual.HoleEspFeature;
import dev.c4lam.celeste.module.impl.visual.VisualWorldRenderer;
import dev.c4lam.celeste.module.impl.expansion.ExpansionFeatures;
import dev.c4lam.celeste.module.impl.visual.expansion.VisualExpansion;
import dev.c4lam.celeste.module.expansion.inventory.InventoryExpansion;
import dev.c4lam.celeste.module.expansion.sword.SwordExpansion;
import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;

/** One explicit registry keeps GUI names and runtime implementations auditable. */
public final class BuiltInFeatures {
    private static boolean registered;

    private BuiltInFeatures() {
    }

    public static synchronized void register(ModuleManager modules) {
        if (registered) {
            return;
        }
        registered = true;

        modules.register(new SwordAuraFeature());
        modules.register(new AutoSwordFeature());
        modules.register(new ShieldBreakFeature());
        modules.register(new TriggerFeature());
        modules.register(new TBOTFeature());
        modules.register(new MaceAuraFeature());
        modules.register(new WindChargeFeature());
        MaceSwapFeature maceSwap = new MaceSwapFeature();
        modules.register(maceSwap);
        SwordAttackCoordinator.installMaceSwap(maceSwap);
        SmashGuideFeature smashGuide = new SmashGuideFeature();
        modules.register(smashGuide);
        modules.register(new AutoCrystalAuraFeature());
        modules.register(new SurroundFeature());
        modules.register(new AutoTrapFeature());
        modules.register(new AnchorAuraFeature());
        DamageRenderFeature damageRender = new DamageRenderFeature();
        modules.register(damageRender);
        modules.register(new AutoCrystalFeature());
        modules.register(new AutoHitCrystalFeature());
        modules.register(new AnchorMacroFeature());
        CombatOverlayRenderer.initialize(smashGuide, damageRender);
        modules.register(new SprintFeature());
        modules.register(new SafeWalkFeature());
        modules.register(new ElytraFeature());
        modules.register(new BoatFlightFeature());
        modules.register(new FakeFlyFeature());
        modules.register(new FullbrightFeature());
        
        EntityEspFeature entityEsp = new EntityEspFeature();
        HoleEspFeature holeEsp = new HoleEspFeature();
        modules.register(entityEsp);
        modules.register(holeEsp);
        VisualWorldRenderer.initialize(entityEsp, holeEsp);
        modules.register(new SeeRotationsFeature());
        
        ExpansionFeatures.register(modules);
        VisualExpansion.register(modules);
        InventoryExpansion.register(modules);
        SwordExpansion.register(modules);
    }
}
