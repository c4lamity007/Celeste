package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;

public final class TriggerFeature extends CombatFeature {
    private static final int PRIORITY = 40;
    private static final double RANGE = 3.25D;
    private LivingEntity target;

    @Override
    public String name() {
        return "Trigger";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        target = crosshairTarget(context);
        if (target == null
            || !Targeting.attackReady(context.client(), context.slider("Cooldown", 0.9D))
            || CombatLoadout.attackSlot(context.client(), target, CombatLoadout.Weapon.CURRENT) < 0) {
            target = null;
            releaseIntent();
            return;
        }
        requestRotation(target, PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        LivingEntity selected = target;
        if (selected == null) {
            return;
        }
        submitAttack(
            PRIORITY,
            selected,
            RANGE,
            context.slider("Cooldown", 0.9D),
            false,
            CombatLoadout.Weapon.CURRENT,
            client -> client.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() == selected
                && client.player != null
                && Targeting.inFov(client.player, selected, 8.0D)
        );
    }

    @Override
    protected void clearTarget() {
        target = null;
    }

    private LivingEntity crosshairTarget(ModuleContext context) {
        if (!(context.client().crosshairTarget instanceof EntityHitResult hit)) {
            return null;
        }
        Entity entity = hit.getEntity();
        if (!(entity instanceof LivingEntity living)
            || !Targeting.validLivingTarget(context.client(), living, friends, RANGE, true)
            || !Targeting.inFov(context.client().player, living, 8.0D)) {
            return null;
        }
        return living;
    }
}
