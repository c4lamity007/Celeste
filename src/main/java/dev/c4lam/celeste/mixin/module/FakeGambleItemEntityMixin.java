package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class FakeGambleItemEntityMixin {
    @Shadow
    private void tryMerge() {
    }

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGamblePickup(PlayerEntity player, CallbackInfo ci) {
        if (FakeGambleFeature.tryPickup((ItemEntity) (Object) this, player)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void celeste$fakeGambleClientPhysics(CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        World world = self.getEntityWorld();
        if (!world.isClient() || !FakeGambleFeature.isFakeDrop(self) || self.isRemoved()) {
            return;
        }
        boolean moved = MathHelper.floor(self.lastX) != MathHelper.floor(self.getX())
            || MathHelper.floor(self.lastY) != MathHelper.floor(self.getY())
            || MathHelper.floor(self.lastZ) != MathHelper.floor(self.getZ());
        if (self.age % (moved ? 2 : 40) == 0) {
            this.tryMerge();
        }
        FakeGambleFeature.clientItemTick(self);
    }
}
