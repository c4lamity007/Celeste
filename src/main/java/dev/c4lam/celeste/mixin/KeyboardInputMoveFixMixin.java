package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.rotation.MoveFix;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMoveFixMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void celeste$correctSilentMovement(CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Do not depend on the player retaining this exact input instance:
        // opening the blank InventoryScreen must neutralize every keyboard
        // input pass just like a normal inventory does.
        if (SilentInventoryScreen.blocksMovement(client)) {
            movementVector = Vec2f.ZERO;
            playerInput = PlayerInput.DEFAULT;
            return;
        }
        if (client.player == null || client.player.input != (Object) this || client.player.hasVehicle()) {
            return;
        }

        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        if (!rotations.isActive() || mode == MoveFixMode.OFF) {
            return;
        }

        MoveFix.Correction correction = MoveFix.correct(
            movementVector,
            playerInput,
            client.player.getYaw(),
            rotations.serverRotation().yaw(),
            mode
        );
        movementVector = correction.movement();
        playerInput = correction.keys();
    }
}
