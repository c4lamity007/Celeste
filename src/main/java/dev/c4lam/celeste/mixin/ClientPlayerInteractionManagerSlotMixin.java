package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerSlotMixin {
    @Unique
    private final Deque<Integer> celeste$breakingProjectionSlots = new ArrayDeque<>();

    @Redirect(
        method = "syncSelectedSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerInventory;getSelectedSlot()I"
        )
    )
    private int celeste$keepSilentServerSlot(PlayerInventory inventory) {
        return SilentSlotManager.getInstance().serverSlotOr(inventory.getSelectedSlot());
    }

    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("HEAD"))
    private void celeste$projectSilentBreakingStack(BlockPos pos, Direction direction,
                                                     CallbackInfoReturnable<Boolean> cir) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null) {
            celeste$breakingProjectionSlots.push(SilentSlotManager.getInstance()
                .beginLocalProjection(client.player.getInventory()));
        } else {
            celeste$breakingProjectionSlots.push(-1);
        }
    }

    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("RETURN"))
    private void celeste$restoreVisibleBreakingStack(BlockPos pos, Direction direction,
                                                      CallbackInfoReturnable<Boolean> cir) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null) {
            int previousSlot = celeste$breakingProjectionSlots.isEmpty()
                ? -1 : celeste$breakingProjectionSlots.pop();
            SilentSlotManager.getInstance().endLocalProjection(
                client.player.getInventory(), previousSlot);
        } else if (!celeste$breakingProjectionSlots.isEmpty()) {
            celeste$breakingProjectionSlots.pop();
        }
    }
}
