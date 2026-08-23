package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Interpolated player boxes without glowing, team, or scoreboard mutations. */
public final class EntityEspFeature implements ModuleFeature {
    private static final int CELESTE_PINK = 0xFF4FA3;

    private volatile boolean enabled;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.75D;

    @Override
    public String name() {
        return "Entity ESP";
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

    void extract(WorldExtractionContext context, List<VisualWorldRenderer.WorldBox> output) {
        if (!enabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || self.getEntityWorld() != context.world()) {
            return;
        }

        double renderRange = range;
        double rangeSquared = renderRange * renderRange;
        float tickProgress = context.tickCounter().getTickProgress(false);
        double pulse = 0.91D + Math.sin(Util.getMeasuringTimeMs() * 0.0032D) * 0.09D;
        double fadeWidth = Math.min(10.0D, Math.max(2.0D, renderRange * 0.15D));

        for (AbstractClientPlayerEntity player : context.world().getPlayers()) {
            if (player == self
                || !player.isAlive()
                || player.isRemoved()
                || player.isSpectator()
                || player.isCreative()
                || FriendManager.getInstance().isFriend(player)) {
                continue;
            }

            double distanceSquared = self.squaredDistanceTo(player);
            if (distanceSquared > rangeSquared) {
                continue;
            }

            double edge = MathHelper.clamp(
                (renderRange - Math.sqrt(distanceSquared)) / fadeWidth,
                0.0D,
                1.0D
            );
            edge = edge * edge * (3.0D - 2.0D * edge);
            float lineAlpha = (float) MathHelper.clamp(opacity * pulse * edge, 0.0D, 1.0D);
            if (lineAlpha <= 0.002F) {
                continue;
            }

            Vec3d interpolated = player.getLerpedPos(tickProgress);
            Box bounds = player.getBoundingBox().offset(interpolated.subtract(player.getEntityPos()));
            output.add(new VisualWorldRenderer.WorldBox(
                bounds,
                VisualWorldRenderer.argb(lineAlpha * 0.17F, CELESTE_PINK),
                VisualWorldRenderer.argb(lineAlpha, CELESTE_PINK),
                1.45F
            ));
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.75D), 0.1D, 1.0D);
    }
}
