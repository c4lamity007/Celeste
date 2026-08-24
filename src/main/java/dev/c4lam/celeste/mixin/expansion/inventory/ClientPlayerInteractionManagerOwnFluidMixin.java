package dev.c4lam.celeste.mixin.expansion.inventory;

import dev.c4lam.celeste.module.expansion.inventory.OwnFluidTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerOwnFluidMixin {
    @Unique
    private BlockPos celeste$pendingOwnFluid;

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void celeste$captureOwnFluidPlace(PlayerEntity player, Hand hand,
                                              CallbackInfoReturnable<ActionResult> cir) {
        celeste$pendingOwnFluid = null;
        ItemStack stack = player.getStackInHand(hand);
        if (!stack.isOf(Items.WATER_BUCKET) && !stack.isOf(Items.LAVA_BUCKET)) {
            return;
        }
        celeste$pendingOwnFluid = OwnFluidTracker.predictFilledBucketPlace(MinecraftClient.getInstance());
    }

    @Inject(method = "interactItem", at = @At("RETURN"))
    private void celeste$markOwnFluidPlace(PlayerEntity player, Hand hand,
                                           CallbackInfoReturnable<ActionResult> cir) {
        BlockPos pending = celeste$pendingOwnFluid;
        celeste$pendingOwnFluid = null;
        if (pending != null && cir.getReturnValue() instanceof ActionResult.Success) {
            OwnFluidTracker.getInstance().mark(pending);
        }
    }
}
