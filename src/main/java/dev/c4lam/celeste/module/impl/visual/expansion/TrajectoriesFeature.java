package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/** Bounded, block-collision projectile preview derived from the supplied Volt example. */
public final class TrajectoriesFeature implements ModuleFeature {
    private static final int PATH_COLOR = 0xFFFF4FA3;
    private static final int LANDING_COLOR = 0xFFE7F3FF;

    private volatile boolean enabled;
    private volatile boolean pearls = true;
    private volatile boolean bows = true;
    private volatile boolean landing = true;
    private volatile int steps = 100;

    @Override
    public String name() {
        return "Trajectories";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
    }

    void extract(
        WorldExtractionContext context,
        List<VisualExpansionRenderer.WorldLineStrip> paths,
        List<VisualExpansionRenderer.WorldBox> boxes
    ) {
        if (!enabled) {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || player.getEntityWorld() != context.world()) {
            return;
        }

        ItemStack held = player.getMainHandStack();
        Motion motion = motion(player, held);
        if (motion == null) {
            return;
        }

        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d position = player.getCameraPosVec(tickProgress).add(player.getRotationVec(tickProgress).multiply(0.16D));
        Vec3d velocity = player.getRotationVec(tickProgress).normalize().multiply(motion.velocity());
        List<Vec3d> points = new ArrayList<>(steps + 1);
        points.add(position);

        boolean hitSomething = false;
        for (int index = 0; index < steps; index++) {
            Vec3d next = position.add(velocity);
            HitResult hit = context.world().raycast(new RaycastContext(
                position,
                next,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
            ));
            if (hit.getType() != HitResult.Type.MISS) {
                points.add(hit.getPos());
                position = hit.getPos();
                hitSomething = true;
                break;
            }
            points.add(next);
            position = next;
            velocity = velocity.multiply(motion.drag()).add(0.0D, -motion.gravity(), 0.0D);
        }

        if (points.size() >= 2) {
            paths.add(new VisualExpansionRenderer.WorldLineStrip(List.copyOf(points), PATH_COLOR, 1.7F));
        }
        if (landing && hitSomething) {
            boxes.add(new VisualExpansionRenderer.WorldBox(
                new net.minecraft.util.math.Box(position, position).expand(0.115D),
                0x30E7F3FF,
                LANDING_COLOR,
                1.35F
            ));
        }
    }

    private Motion motion(ClientPlayerEntity player, ItemStack held) {
        if (pearls && held.isOf(Items.ENDER_PEARL)) {
            return new Motion(1.5D, 0.03D, 0.99D);
        }
        if (!bows) {
            return null;
        }
        if (held.isOf(Items.BOW)) {
            if (!player.isUsingItem() || !player.getActiveItem().isOf(Items.BOW)) {
                return null;
            }
            float power = BowItem.getPullProgress(player.getItemUseTime());
            return power <= 0.01F ? null : new Motion(power * 3.0D, 0.05D, 0.99D);
        }
        if (held.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(held)) {
            return new Motion(3.15D, 0.05D, 0.99D);
        }
        return null;
    }

    private void update(ModuleContext context) {
        pearls = context.toggle("Pearls", true);
        bows = context.toggle("Bows", true);
        landing = context.toggle("Landing", true);
        steps = MathHelper.clamp((int) Math.round(context.slider("Steps", 100.0D)), 40, 160);
    }

    private record Motion(double velocity, double gravity, double drag) {
    }
}
