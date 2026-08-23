package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.expansion.LocalRotationProjection;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/** Volt/Silk AutoExtinguish rebuilt with silent down-look and normal bucket use. */
final class AutoExtinguishFeature implements ModuleFeature {
    private static final int PRIORITY = 240;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private int pickupDueAge = Integer.MAX_VALUE;
    private int pickupSlot = -1;
    private ClientWorld intentWorld;
    private int intentSlot = -1;
    private boolean pickupIntent;

    @Override
    public String name() {
        return "Auto Extinguish";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        clearIntent();
        MinecraftClient client = context.client();
        if (!InventorySupport.playable(client) || client.world.getRegistryKey().equals(net.minecraft.world.World.NETHER)) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        if (client.player.isOnFire()) {
            int delay = Math.max(0, (int) Math.round(context.slider("Delay", 2.0D)));
            if (client.player.age - lastActionAge < delay) return;
            int slot = InventorySupport.hotbarSlot(client, Items.WATER_BUCKET);
            if (slot < 0) return;
            intentWorld = client.world;
            intentSlot = slot;
            InventorySupport.requestDown(this, client.player, PRIORITY);
            return;
        }
        if (context.toggle("Pick up", false) && client.player.age >= pickupDueAge && pickupSlot >= 0
            && pickupSlot <= 8 && client.player.getInventory().getStack(pickupSlot).isOf(Items.BUCKET)
            && hasNearbyWaterSource(client)) {
            intentWorld = client.world;
            intentSlot = pickupSlot;
            pickupIntent = true;
            InventorySupport.requestDown(this, client.player, PRIORITY);
        } else if (client.player.age > pickupDueAge + 20) {
            pickupSlot = -1;
            pickupDueAge = Integer.MAX_VALUE;
            GameplayFoundation.releaseOwner(this);
        }
    }

    @Override
    public void onActionTick(ModuleContext context) {
        ClientWorld world = intentWorld;
        int slot = intentSlot;
        boolean pickingUp = pickupIntent;
        if (world == null || slot < 0 || !InventorySupport.downReady(this)) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> execute(client, context, world, slot, pickingUp));
    }

    private void execute(MinecraftClient client, ModuleContext context, ClientWorld world, int slot, boolean pickingUp) {
        if (!InventorySupport.playable(client) || client.world != world || !context.module().enabled()
            || slot > 8 || !InventorySupport.downReady(this)) return;
        if (pickingUp) {
            if (!context.toggle("Pick up", false) || client.player.isOnFire() || !hasNearbyWaterSource(client)
                || !client.player.getInventory().getStack(slot).isOf(Items.BUCKET)) return;
        } else if (!client.player.isOnFire() || !client.player.getInventory().getStack(slot).isOf(Items.WATER_BUCKET)) {
            return;
        }
        SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
            context.toggle("Silent swap", true), () -> {
            if (Boolean.TRUE.equals(LocalRotationProjection.run(client, this,
                () -> InventorySupport.useItem(client, Hand.MAIN_HAND)))) {
                lastActionAge = client.player.age;
                if (pickingUp) {
                    pickupSlot = -1;
                    pickupDueAge = Integer.MAX_VALUE;
                } else if (context.toggle("Pick up", false)) {
                    pickupSlot = slot;
                    pickupDueAge = client.player.age + 3;
                }
            }
        });
    }

    private static boolean hasNearbyWaterSource(MinecraftClient client) {
        BlockPos base = client.player.getBlockPos();
        for (BlockPos pos : new BlockPos[]{base, base.down(), base.north(), base.south(), base.east(), base.west()}) {
            var fluid = client.world.getFluidState(pos);
            if (fluid.getFluid() == Fluids.WATER && fluid.isStill()) return true;
        }
        return false;
    }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        clearIntent();
        pickupDueAge = Integer.MAX_VALUE;
        pickupSlot = -1;
        lastActionAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }

    private void clearIntent() {
        intentWorld = null;
        intentSlot = -1;
        pickupIntent = false;
    }
}
