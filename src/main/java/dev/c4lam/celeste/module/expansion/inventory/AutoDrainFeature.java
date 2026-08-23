package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.expansion.LocalRotationProjection;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Volt/Silk AutoDrain rebuilt as a crosshair source-water bucket interaction. */
final class AutoDrainFeature implements ModuleFeature {
    private static final int PRIORITY = 145;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private Plan plan;

    @Override
    public String name() {
        return "Auto Drain";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        plan = null;
        MinecraftClient client = context.client();
        if (!InventorySupport.playable(client) || !delayElapsed(context)
            || !(client.crosshairTarget instanceof BlockHitResult hit)) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        BlockPos source = source(client, hit);
        int slot = InventorySupport.hotbarSlot(client, Items.BUCKET);
        if (source == null || slot < 0 || client.player.squaredDistanceTo(hit.getPos()) > 20.25D) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        plan = new Plan(client.world, hit, source, slot, InventorySupport.lookAt(client.player, hit.getPos()));
        InventorySupport.requestRotation(this, client.player, hit.getPos(), PRIORITY, 180.0F, 90.0F);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        Plan selected = plan;
        if (selected == null || !InventorySupport.rotationReady(this, selected.rotation, 8.0F)) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!InventorySupport.playable(client) || client.world != selected.world || !context.module().enabled()
                || !delayElapsed(context) || !InventorySupport.sameHit(client.crosshairTarget, selected.hit)
                || !isSource(client, selected.source) || selected.slot > 8
                || !client.player.getInventory().getStack(selected.slot).isOf(Items.BUCKET)
                || !InventorySupport.rotationReady(this, selected.rotation, 8.0F)) return;
            SilentSlotManager.getInstance().runWithSlot(this, selected.slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                if (isSource(client, selected.source)
                    && Boolean.TRUE.equals(LocalRotationProjection.run(client, this,
                        () -> InventorySupport.useItem(client, Hand.MAIN_HAND)))) {
                    lastActionAge = client.player.age;
                }
            });
        });
    }

    private static BlockPos source(MinecraftClient client, BlockHitResult hit) {
        if (isSource(client, hit.getBlockPos())) return hit.getBlockPos();
        BlockPos adjacent = hit.getBlockPos().offset(hit.getSide());
        return isSource(client, adjacent) ? adjacent : null;
    }

    private static boolean isSource(MinecraftClient client, BlockPos pos) {
        if (client.world == null || pos == null) return false;
        var fluid = client.world.getFluidState(pos);
        return fluid.getFluid() == Fluids.WATER && fluid.isStill();
    }

    private boolean delayElapsed(ModuleContext context) {
        return context.client().player != null && context.client().player.age - lastActionAge
            >= Math.max(1, (int) Math.round(context.slider("Delay", 4.0D)));
    }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        plan = null;
        lastActionAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }

    private record Plan(ClientWorld world, BlockHitResult hit, BlockPos source,
                        int slot, Rotation rotation) { }
}
