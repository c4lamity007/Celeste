package dev.c4lam.celeste.mixin.expansion;

import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import net.minecraft.client.MinecraftClient;
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
            return doAttack();
        } finally {
            SwordAttackCoordinator.finishVanillaAttack(client, scope);
        }
    }
}
