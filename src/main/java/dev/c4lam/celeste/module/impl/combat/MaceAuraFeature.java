package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

/** Automatic mace targeting only. Physical Stun Slam belongs to Mace Swap. */
public final class MaceAuraFeature extends CombatFeature {
    private static final int PRIORITY = 80;
    private static final double WEAPON_REACH = 4.675D;
    private PlayerEntity target;

    @Override
    public String name() {
        return "Mace Aura";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        String mode = context.mode("Mode", "Mace only");
        double range = targetingRange(context, mode);
        double fov = context.slider("FOV", 60.0D);
        if (!holdingValidItem(context, mode)) {
            target = null;
            releaseIntent();
            return;
        }

        target = Targeting.selectPlayer(context.client(), friends, range, fov,
            context.mode("Target", "Distance"));
        boolean fallCheck = context.toggle("Fall check", true);
        if (target == null
            || CombatLoadout.attackSlot(context.client(), target, weaponForMode(mode)) < 0) {
            target = null;
            releaseIntent();
            return;
        }
        // Same silent-aim path as Auto Mace: hand the shielded target to Mace Swap
        // this tick so it can own the look, then axe+mace. Do not require the
        // camera to already be on them.
        if (target.isBlocking()
            && CombatLoadout.enabled("Mace Swap")
            && CombatLoadout.maceSwapAutoStunSlam()) {
            SwordAttackCoordinator.queueAutomaticMaceStun(context.client(), target);
            target = null;
            releaseIntent();
            return;
        }
        if (fallCheck && !Targeting.inMaceSmashWindow(context.client().player)) {
            target = null;
            releaseIntent();
            return;
        }
        requestRotation(target, PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        PlayerEntity selected = target;
        String mode = context.mode("Mode", "Mace only");
        boolean fallCheck = context.toggle("Fall check", true);
        if (selected == null
            || (fallCheck && !Targeting.inMaceSmashWindow(context.client().player))) {
            return;
        }
        if (!CombatLoadout.maceCooldownElapsed()) {
            return;
        }
        double range = targetingRange(context, mode);
        submitCappedAuraAttack(
            PRIORITY,
            selected,
            range,
            0.0D,
            false,
            weaponForMode(mode),
            client -> client.player != null
                && inSilentFov(client, selected, context.slider("FOV", 60.0D))
                && (!fallCheck || Targeting.inMaceSmashWindow(client.player)),
            CombatLoadout::markMaceAction
        );
    }

    private boolean holdingValidItem(ModuleContext context, String mode) {
        if (context.client().player == null) return false;
        ItemStack held = context.client().player.getMainHandStack();
        return switch (mode) {
            case "Mace only" -> held.isOf(Items.MACE)
                || (CombatLoadout.enabled("Mace Swap")
                    && CombatLoadout.findItem(context.client(), Items.MACE) >= 0);
            case "Weapon only" -> isWeapon(held);
            case "All items" -> true;
            default -> true;
        };
    }

    private double targetingRange(ModuleContext context, String mode) {
        double configured = "Weapon only".equals(mode) ? WEAPON_REACH : context.slider("Range", 3.0D);
        return Math.max(configured, VanillaCombatReach.heldHitRange(context.client()));
    }

    private static boolean inSilentFov(MinecraftClient client, PlayerEntity target, double halfFovDegrees) {
        if (client.player == null || target == null) {
            return false;
        }
        return RotationManager.getInstance().serverRotation()
            .angularDistanceTo(Rotation.lookingAt(client.player, Targeting.aimPoint(target))) <= halfFovDegrees;
    }

    private boolean isWeapon(ItemStack stack) {
        return stack.isOf(Items.MACE)
            || stack.isIn(ItemTags.SWORDS)
            || stack.isIn(ItemTags.AXES)
            || stack.get(net.minecraft.component.DataComponentTypes.PIERCING_WEAPON) != null
            || stack.get(net.minecraft.component.DataComponentTypes.ATTACK_RANGE) != null;
    }

    private CombatLoadout.Weapon weaponForMode(String mode) {
        if (CombatLoadout.enabled("Mace Swap")) return CombatLoadout.Weapon.MACE;
        return switch (mode) {
            case "Mace only" -> CombatLoadout.Weapon.MACE;
            case "Weapon only", "All items" -> CombatLoadout.Weapon.CURRENT;
            default -> CombatLoadout.Weapon.MACE;
        };
    }

    @Override
    protected void clearTarget() {
        target = null;
    }

}
