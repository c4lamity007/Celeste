package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Generic3x3ContainerScreen.class)
public abstract class FakeGambleScreenMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void celeste$keepFakeGamblePrizes(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        FakeGambleFeature.ensureScreenPrizes((Generic3x3ContainerScreen) (Object) this);
    }
}
