package dev.c4lam.celeste.mixin.expansion;

import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scopes silent weapon selection to the user's original vanilla attack call. */
@Mixin(MinecraftClient.class)
public abstract class SwordExpansionMinecraftClientMixin {
    @Shadow
    private boolean doAttack() {
        throw new AssertionError("mixin shadow");
    }

    @Redirect(
        method = "handleInputEvents",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;doAttack()Z")
    )
    private boolean celeste$swordExpansionAttack(MinecraftClient client) {
        SwordAttackCoordinator.AttackScope scope = SwordAttackCoordinator.beginVanillaAttack(client);
        if (!scope.proceed()) {
            return false;
        }
        try {
            SwordAttackCoordinator.prepareVanillaClick(client, scope);
            if (scope.replaceVanillaClick()) {
                return SwordAttackCoordinator.performSwappedMelee(client, scope);
            }
            return doAttack();
        } finally {
            SwordAttackCoordinator.finishVanillaAttack(client, scope);
        }
    }

    /**
     * Vanilla {@code doAttack} reads MAINHAND from {@code getStackInHand}. Use
     * the stack that was just swapped in so the click matches a sword swap.
     */
    @Redirect(
        method = "doAttack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getStackInHand(Lnet/minecraft/util/Hand;)Lnet/minecraft/item/ItemStack;"
        )
    )
    private ItemStack celeste$swappedMainHand(ClientPlayerEntity player, Hand hand) {
        ItemStack vanilla = player.getStackInHand(hand);
        if (hand != Hand.MAIN_HAND) {
            return vanilla;
        }
        return SwordAttackCoordinator.mainHandForVanillaClick(vanilla);
    }
}
