package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.hud.HudManager;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps media transport controls usable while the chat input owns the mouse. */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMediaMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void celeste$handleMediaControls(Click click, boolean doubled,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
            && HudManager.getInstance().handleMediaClick(click.x(), click.y())) {
            callback.setReturnValue(true);
        }
    }
}
