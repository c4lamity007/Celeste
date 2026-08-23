package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Dropped-item boxes without the supplied client's glow/team state mutation. */
public final class ItemEspFeature implements ModuleFeature {
    private static final int MAX_ITEMS = 160;
    private static final int MAX_ENTITY_VISITS = 1_024;
    private static final int ITEM_COLOR = 0xFFF09BD0;
    private volatile boolean enabled;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.76D;

    @Override
    public String name() {
        return "Item ESP";
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

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output) {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (!enabled || self == null || self.getEntityWorld() != context.world()) {
            return;
        }
        double maxRange = range;
        double rangeSquared = maxRange * maxRange;
        float tickProgress = context.tickCounter().getTickProgress(false);
        int count = 0;
        int visits = 0;
        for (var entity : context.world().getEntities()) {
            if (count >= MAX_ITEMS || visits++ >= MAX_ENTITY_VISITS) break;
            if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.isRemoved() || item.getStack().isEmpty()) {
                continue;
            }
            double distanceSquared = self.squaredDistanceTo(item);
            if (distanceSquared > rangeSquared) continue;
            double fade = MathHelper.clamp((maxRange - Math.sqrt(distanceSquared)) / Math.max(3.0D, maxRange * 0.15D), 0.0D, 1.0D);
            float alpha = (float) MathHelper.clamp(opacity * fade, 0.0D, 1.0D);
            if (alpha <= 0.003F) continue;
            Vec3d interpolated = item.getLerpedPos(tickProgress);
            Box bounds = item.getBoundingBox().offset(interpolated.subtract(item.getEntityPos())).expand(0.045D);
            output.add(new VisualExpansionRenderer.WorldBox(
                bounds,
                VisualExpansionRenderer.argb(alpha * 0.12F, ITEM_COLOR),
                VisualExpansionRenderer.argb(alpha, ITEM_COLOR),
                1.25F
            ));
            count++;
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.76D), 0.1D, 1.0D);
    }
}
