package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class FakeGambleInteractMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void celeste$noteFakeGambleDispenser(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                                 CallbackInfoReturnable<ActionResult> cir) {
        FakeGambleFeature.noteUsedDispenser(hit);
    }
}
