package dev.c4lam.celeste.module.impl.combat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
/** Vanilla range/raycast is an explicit authority result; no fake raycasting engine. */
final class VanillaCombatReach {
    static boolean allow = true;
    static int queries;
    static int queriedSlot;
    static LivingEntity queriedTarget;
    static double heldHitRange(MinecraftClient client) { return 3.0; }
    static boolean canAttack(MinecraftClient client, LivingEntity target, int slot, double range, boolean ray) {
        queries++; queriedSlot = slot; queriedTarget = target;
        return allow;
    }
}
