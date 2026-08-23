package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.hud.HudTargetTracker;
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

/** Animated focus marker for one valid player target, inspired by Silk's spiral Target ESP. */
public final class TargetEspFeature implements ModuleFeature {
    private static final int COLOR = 0xFFFF4FA3;
    private volatile boolean enabled;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.86D;

    @Override
    public String name() {
        return "Target ESP";
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
        if (!enabled || self == null || self.getEntityWorld() != context.world()) return;
        AbstractClientPlayerEntity target = select(context, self);
        if (target == null) return;

        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d interpolated = target.getLerpedPos(tickProgress);
        Box bounds = target.getBoundingBox().offset(interpolated.subtract(target.getEntityPos()));
        float pulse = (float) (0.84D + 0.16D * Math.sin(Util.getMeasuringTimeMs() * 0.005D));
        float alpha = (float) MathHelper.clamp(opacity * pulse, 0.0D, 1.0D);
        output.add(new VisualExpansionRenderer.WorldBox(
            bounds.expand(0.085D),
            VisualExpansionRenderer.argb(alpha * 0.08F, COLOR),
            VisualExpansionRenderer.argb(alpha, COLOR),
            1.65F
        ));

        double phase = (Util.getMeasuringTimeMs() % 4_000L) / 4_000.0D * Math.PI * 2.0D;
        double width = Math.max(0.42D, target.getWidth() * (0.74D + 0.08D * Math.sin(phase)));
        for (int layer = 0; layer < 3; layer++) {
            double progress = (phase / (Math.PI * 2.0D) + layer / 3.0D) % 1.0D;
            double y = bounds.minY + 0.12D + progress * Math.max(0.15D, bounds.getLengthY() - 0.24D);
            output.add(new VisualExpansionRenderer.WorldBox(
                new Box(
                    interpolated.x - width, y, interpolated.z - width,
                    interpolated.x + width, y + 0.012D, interpolated.z + width
                ),
                0,
                VisualExpansionRenderer.argb(alpha * 0.72F, COLOR),
                1.2F
            ));
        }
    }

    private AbstractClientPlayerEntity select(WorldExtractionContext context, ClientPlayerEntity self) {
        return HudTargetTracker.getInstance().activeTarget(MinecraftClient.getInstance())
            instanceof AbstractClientPlayerEntity player && valid(self, player) ? player : null;
    }

    private boolean valid(ClientPlayerEntity self, AbstractClientPlayerEntity player) {
        return player != self && player.isAlive() && !player.isRemoved() && !player.isSpectator()
            && !player.isCreative() && !FriendManager.getInstance().isFriend(player)
            && self.squaredDistanceTo(player) <= range * range;
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.86D), 0.1D, 1.0D);
    }
}
