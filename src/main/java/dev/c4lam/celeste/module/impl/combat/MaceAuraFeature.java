package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
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
        double range = "Weapon only".equals(mode) ? WEAPON_REACH : context.slider("Range", 3.0D);
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
        // Stun Slam is owned by Mace Swap, but Auto Mace is the module that
        // discovers this shielded target.  Hand it off before Aura can submit
        // its ordinary mace hit, so the axe opener always wins this situation.
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
        double range = "Weapon only".equals(mode) ? WEAPON_REACH : context.slider("Range", 3.0D);
        submitCappedAuraAttack(
            PRIORITY,
            selected,
            range,
            0.0D,
            false,
            weaponForMode(mode),
            client -> client.player != null
                && Targeting.inFov(client.player, selected, context.slider("FOV", 60.0D))
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

    private boolean isWeapon(ItemStack stack) {
        return stack.isOf(Items.MACE)
            || stack.isIn(ItemTags.SWORDS)
            || stack.isIn(ItemTags.AXES)
            || stack.get(net.minecraft.component.DataComponentTypes.PIERCING_WEAPON) != null;
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
