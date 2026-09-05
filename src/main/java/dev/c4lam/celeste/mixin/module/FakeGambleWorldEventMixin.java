package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldEvents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldEventHandler.class)
public abstract class FakeGambleWorldEventMixin {
    @Shadow
    @Final
    private ClientWorld world;

    @Shadow
    public abstract void processWorldEvent(int eventId, BlockPos pos, int data);

    @Inject(method = "processWorldEvent", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGambleDispense(int eventId, BlockPos pos, int data, CallbackInfo ci) {
        if (eventId != WorldEvents.DISPENSER_FAILS) {
            return;
        }
        if (!FakeGambleFeature.onEmptyDispenserFired(this.world, pos)) {
            return;
        }
        ci.cancel();
        this.processWorldEvent(WorldEvents.DISPENSER_DISPENSES, pos, data);
        var state = this.world.getBlockState(pos);
        if (state.isOf(net.minecraft.block.Blocks.DISPENSER)) {
            this.processWorldEvent(WorldEvents.DISPENSER_ACTIVATED, pos, state.get(DispenserBlock.FACING).getIndex());
        }
    }
}
