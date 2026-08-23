package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class BoatFlightClientPlayerMixin {
    @Inject(method = "dismountVehicle", at = @At("HEAD"))
    private void celeste$startBoatFlightDismount(CallbackInfo callbackInfo) {
        BoatFlightFeature.onDismountStart((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "dismountVehicle", at = @At("RETURN"))
    private void celeste$finishBoatFlightDismount(CallbackInfo callbackInfo) {
        BoatFlightFeature.onDismountComplete((ClientPlayerEntity) (Object) this);
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void celeste$prepareBoatFlightStep(CallbackInfo callbackInfo) {
        BoatFlightFeature.prepareGuidedMovement((ClientPlayerEntity) (Object) this);
    }

    /**
     * KeyboardInput normally owns this state, but an open screen can skip a
     * keyboard pass and leave a held movement vector from the previous tick.
     * Clear it immediately after vanilla samples input so Silent Totem behaves
     * exactly like an open inventory for movement and sprinting.
     */
    @Inject(
        method = "tickMovement",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/input/Input;tick()V", shift = At.Shift.AFTER)
    )
    private void celeste$neutralizeSilentTotemInput(CallbackInfo callbackInfo) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!SilentInventoryScreen.blocksMovement(client)) {
            return;
        }
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        player.input.playerInput = PlayerInput.DEFAULT;
        player.setSprinting(false);
    }
}
