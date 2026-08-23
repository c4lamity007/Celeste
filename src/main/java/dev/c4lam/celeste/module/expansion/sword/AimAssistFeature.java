package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;

/** Low-priority, server-rotation aim assistance with strict player validation. */
public final class AimAssistFeature implements ModuleFeature {
    private static final int PRIORITY = 20;
    private PlayerEntity target;

    @Override
    public String name() {
        return "Aim Assist";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        double range = context.slider("Range", 4.5D);
        double fov = context.slider("FOV", 90.0D);
        if (context.client().currentScreen != null
            || context.client().player == null || context.client().interactionManager == null
            || context.client().player.hasVehicle() || context.client().player.isUsingItem()
            || context.client().interactionManager.isBreakingBlock()
            || context.client().options.attackKey.isPressed()
            || context.client().options.useKey.isPressed()
            || (context.toggle("Weapons only", true) && !holdingWeapon(context))) {
            clear();
            return;
        }

        target = SwordExpansionTargets.nearestPlayer(context.client(), range, fov);
        if (target == null
            || (context.toggle("Stop on target", true)
            && context.client().crosshairTarget instanceof EntityHitResult hit
            && hit.getEntity() == target)) {
            clear();
            return;
        }

        float speed = (float) Math.max(1.0D, context.slider("Speed", 18.0D));
        RotationManager.getInstance().submit(RotationTask.builder(
                this,
                SwordExpansionTargets.rotationTo(context.client().player, target)
            )
            .priority(PRIORITY)
            .lifetimeTicks(1)
            .smoothing(speed, speed)
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());
    }

    @Override
    public void onDisable(ModuleContext context) {
        clear();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        clear();
    }

    private boolean holdingWeapon(ModuleContext context) {
        if (context.client().player == null) {
            return false;
        }
        ItemStack stack = context.client().player.getMainHandStack();
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)
            || stack.isIn(ItemTags.SPEARS) || stack.isOf(Items.MACE);
    }

    private void clear() {
        target = null;
        GameplayFoundation.releaseOwner(this);
    }
}
