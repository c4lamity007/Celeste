package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.ItemTags;
import dev.c4lam.celeste.module.impl.combat.VanillaCombatReach;

/** Projects a Breach mace only for the duration of one validated vanilla attack. */
public final class BreachSwapFeature extends InterceptFeature {
    static final int PRIORITY = 180;
    private boolean groundOnly = true;
    private boolean swordOnly = true;
    private boolean silentSwap = true;

    @Override
    public String name() {
        return "Breach Swap";
    }

    @Override
    protected void refresh(ModuleContext context) {
        groundOnly = context.toggle("Ground only", true);
        swordOnly = context.toggle("Sword only", true);
        silentSwap = context.toggle("Silent swap", true);
    }

    SwordAttackCoordinator.SwapIntent select(MinecraftClient client) {
        if (!active() || client.player == null || client.currentScreen != null
            || client.player.hasVehicle()
            || client.player.isUsingItem() || client.interactionManager == null
            || client.interactionManager.isBreakingBlock()
            || (groundOnly && !client.player.isOnGround())
            || (swordOnly && !client.player.getMainHandStack().isIn(ItemTags.SWORDS))) {
            return null;
        }
        LivingEntity target = SwordExpansionTargets.crosshairLiving(client, 3.5D);
        int slot = target == null ? -1 : EnchantedHotbar.breachMace(client);
        return slot < 0 || !VanillaCombatReach.canAttack(client, target, slot, 3.5D, true)
            || client.player.isBelowMinimumAttackCharge(
            client.player.getInventory().getStack(slot), 0
        ) ? null : new SwordAttackCoordinator.SwapIntent(this, slot, PRIORITY, silentSwap);
    }
}
