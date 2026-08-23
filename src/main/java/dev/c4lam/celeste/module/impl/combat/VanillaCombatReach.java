package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.mixin.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Mirrors the 1.21.11 attack-range decision for the start-of-tick and action
 * stacks. The caller's range setting is only an additional, never expanding,
 * cap. This keeps a GUI value from granting reach the held items do not have.
 */
public final class VanillaCombatReach {
    private static final double EPSILON = 1.0E-7D;

    private VanillaCombatReach() {
    }

    public static boolean canAttack(MinecraftClient client, Entity target, int actionSlot,
                                    double configuredLimit, boolean requireLineOfSight) {
        if (client == null || client.player == null || client.world == null || target == null
            || target.getEntityWorld() != client.world || target.isRemoved()
            || actionSlot < 0 || actionSlot > 8 || configuredLimit <= 0.0D) {
            return false;
        }

        Box ordinaryBox = target.getBoundingBox();
        Vec3d eyes = client.player.getEyePos();
        if (ordinaryBox.squaredMagnitude(eyes) > configuredLimit * configuredLimit + EPSILON) {
            return false;
        }

        // Grim snapshots the previously server-held stack when vanilla's
        // selected-slot synchronizer emits a change immediately before the
        // attack. CPIM's cache is that exact pre-sync server slot; the visible
        // inventory index can already have changed via mouse wheel.
        int startSlot = client.interactionManager instanceof ClientPlayerInteractionManagerAccessor accessor
            ? accessor.celeste$getLastSelectedSlot()
            : client.player.getInventory().getSelectedSlot();
        if (startSlot < 0 || startSlot > 8) {
            return false;
        }
        ItemStack startStack = client.player.getInventory().getStack(startSlot);
        ItemStack actionStack = client.player.getInventory().getStack(actionSlot);
        AttackRangeComponent startRange = startStack.get(DataComponentTypes.ATTACK_RANGE);
        AttackRangeComponent actionRange = actionStack.get(DataComponentTypes.ATTACK_RANGE);

        double maximum;
        double margin = 0.0D;
        if (startRange == null) {
            // Vanilla deliberately retains ordinary entity-interaction reach
            // when the stack at the beginning of the tick had no component.
            maximum = client.player.getEntityInteractionRange();
        } else {
            maximum = startRange.getEffectiveMaxRange(client.player);
            margin = startRange.hitboxMargin();
            if (actionRange != null) {
                maximum = Math.min(maximum, actionRange.getEffectiveMaxRange(client.player));
                margin = Math.min(margin, actionRange.hitboxMargin());
            }
        }

        Box effectiveBox = margin > 0.0D ? ordinaryBox.expand(margin) : ordinaryBox;
        if (effectiveBox.squaredMagnitude(eyes) > maximum * maximum + EPSILON) {
            return false;
        }
        if (!requireLineOfSight) {
            return true;
        }
        Vec3d end = eyes.add(RotationManager.getInstance().serverRotation().direction().multiply(maximum));
        Vec3d intercept;
        if (effectiveBox.contains(eyes)) {
            intercept = eyes;
        } else {
            intercept = effectiveBox.raycast(eyes, end).orElse(null);
        }
        return intercept != null && unobstructed(client, eyes, intercept);
    }

    private static boolean unobstructed(MinecraftClient client, Vec3d origin, Vec3d destination) {
        HitResult result = client.world.raycast(new RaycastContext(
            origin, destination, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player
        ));
        return result.getType() == HitResult.Type.MISS
            || origin.squaredDistanceTo(result.getPos()) + EPSILON
                >= origin.squaredDistanceTo(destination);
    }
}
