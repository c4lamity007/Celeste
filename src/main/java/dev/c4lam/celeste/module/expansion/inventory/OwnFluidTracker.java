package dev.c4lam.celeste.module.expansion.inventory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers still water/lava the local player placed so Auto Drain will not steal it. */
public final class OwnFluidTracker {
    private static final OwnFluidTracker INSTANCE = new OwnFluidTracker();
    private static final int MAX_OWNED = 64;
    private static final long PLACE_GRACE_NANOS = 3_000_000_000L;

    private final Map<BlockPos, Long> owned = new LinkedHashMap<>();
    private ClientWorld world;

    private OwnFluidTracker() {
    }

    public static OwnFluidTracker getInstance() {
        return INSTANCE;
    }

    public void mark(BlockPos pos) {
        if (pos == null) {
            return;
        }
        prune();
        BlockPos key = pos.toImmutable();
        owned.remove(key);
        owned.put(key, System.nanoTime());
        while (owned.size() > MAX_OWNED) {
            Iterator<BlockPos> iterator = owned.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public boolean isOwn(BlockPos pos) {
        prune();
        return pos != null && owned.containsKey(pos);
    }

    public void clear() {
        owned.clear();
        world = null;
    }

    public static BlockPos predictFilledBucketPlace(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        double reach = client.player.getBlockInteractionRange();
        Vec3d start = client.player.getEyePos();
        Vec3d end = start.add(client.player.getRotationVec(1.0F).multiply(reach));
        HitResult result = client.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        if (!(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (client.world.getBlockState(pos).isReplaceable() || client.world.getFluidState(pos).isStill()) {
            return pos.toImmutable();
        }
        return pos.offset(hit.getSide()).toImmutable();
    }

    private void prune() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld current = client == null ? null : client.world;
        if (current != world) {
            owned.clear();
            world = current;
            return;
        }
        if (current == null) {
            return;
        }
        long now = System.nanoTime();
        owned.entrySet().removeIf(entry -> {
            if (isStillFluid(current, entry.getKey())) {
                return false;
            }
            return now - entry.getValue() > PLACE_GRACE_NANOS;
        });
    }

    private static boolean isStillFluid(ClientWorld world, BlockPos pos) {
        var fluid = world.getFluidState(pos);
        if (!fluid.isStill()) {
            return false;
        }
        Fluid type = fluid.getFluid();
        return type == Fluids.WATER || type == Fluids.LAVA;
    }
}
