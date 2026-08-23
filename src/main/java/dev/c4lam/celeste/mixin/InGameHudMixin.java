package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.hud.HudEditorScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents the game HUD and crosshair from showing behind Celeste's GUI. */
@Mixin(value = InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void celeste$hideHud(
        DrawContext context,
        RenderTickCounter tickCounter,
        CallbackInfo callback
    ) {
        if (MinecraftClient.getInstance().currentScreen instanceof HudEditorScreen) {
            ((InGameHudAccessor) this).celeste$renderHotbar(context, tickCounter);
            callback.cancel();
        } else if (MinecraftClient.getInstance().currentScreen instanceof CelesteScreen) {
            callback.cancel();
        }
    }
}
