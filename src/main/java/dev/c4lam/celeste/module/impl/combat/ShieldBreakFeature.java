package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;

public final class ShieldBreakFeature extends CombatFeature {
    private static final int PRIORITY = 70;
    private static final double RANGE = 3.5D;
    private PlayerEntity target;

    @Override
    public String name() {
        return "Shield Break";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        target = null;
        if (context.client().crosshairTarget instanceof EntityHitResult hit) {
            Entity entity = hit.getEntity();
            if (entity instanceof PlayerEntity player 
                && isHoldingShield(player) 
                && validPlayer(context.client(), player, RANGE)) {
                target = player;
            }
        }
        if (target == null
            || !Targeting.attackReady(context.client(), 0.0D)
            || CombatLoadout.attackSlot(context.client(), target, CombatLoadout.Weapon.AXE) < 0) {
            target = null;
            releaseIntent();
            return;
        }
        requestRotation(target, PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        PlayerEntity selected = target;
        if (selected == null || !isHoldingShield(selected)) {
            return;
        }
        submitAttack(
            PRIORITY,
            selected,
            RANGE,
            0.0D,
            false,
            CombatLoadout.Weapon.AXE,
            client -> isHoldingShield(selected)
                && client.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() == selected
                && client.player != null
        );
    }

    @Override
    protected void clearTarget() {
        target = null;
    }

    private boolean isHoldingShield(PlayerEntity player) {
        if (player == null) {
            return false;
        }
        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();
        return mainHand.isOf(Items.SHIELD) || offHand.isOf(Items.SHIELD);
    }
}
