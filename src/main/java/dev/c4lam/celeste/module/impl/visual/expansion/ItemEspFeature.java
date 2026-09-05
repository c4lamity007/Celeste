package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Interpolated dropped-item halos; never changes an entity's glow/team state. */
public final class ItemEspFeature implements ModuleFeature {
    private static final int MAX_ITEMS = 96;
    private static final int MAX_GLOW_ITEMS = 24;
    private static final int MAX_BEAM_ITEMS = 16;
    private static final int MAX_EFFECT_QUADS = 64;
    private static final int MAX_ENTITY_VISITS = 1_024;
    private volatile boolean enabled;
    private volatile String style = "Halo";
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xF09BD0;
    private volatile double glow = 0.45D;
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

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines) {
        extract(context, output, lines, null);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows) {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (!enabled || self == null || self.getEntityWorld() != context.world()) {
            return;
        }
        double maxRange = range;
        double rangeSquared = maxRange * maxRange;
        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d viewer = self.getLerpedPos(tickProgress);
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        int count = 0;
        int visits = 0;
        List<ItemEntity> candidates = new ArrayList<>();
        for (var entity : context.world().getEntities()) {
            if (visits++ >= MAX_ENTITY_VISITS) break;
            if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.isRemoved() || item.getStack().isEmpty()) {
                continue;
            }
            double distanceSquared = self.squaredDistanceTo(item);
            if (distanceSquared > rangeSquared) continue;
            candidates.add(item);
        }
        // Spend detail on nearby drops, not arbitrary entity iteration order.
        candidates.sort(Comparator.comparingDouble((ItemEntity item) -> self.squaredDistanceTo(item))
            .thenComparingInt(ItemEntity::getId));
        int glowing = 0;
        int effectLimit = glows == null ? 0 : Math.min(384, glows.size() + MAX_EFFECT_QUADS);
        for (ItemEntity item : candidates) {
            if (count >= MAX_ITEMS) break;
            Vec3d interpolated = item.getLerpedPos(tickProgress);
            double distanceSquared = viewer.squaredDistanceTo(interpolated);
            double fade = MathHelper.clamp((maxRange - Math.sqrt(distanceSquared)) / Math.max(3.0D, maxRange * 0.15D), 0.0D, 1.0D);
            fade = fade * fade * (3.0D - 2.0D * fade);
            float alpha = (float) MathHelper.clamp(opacity * fade, 0.0D, 1.0D);
            if (alpha <= 0.003F) continue;
            Box bounds = item.getBoundingBox().offset(interpolated.subtract(item.getEntityPos())).expand(0.045D);
            double seed = (item.getId() % 61) / 61.0D;
            int color = VisualEffects.color(theme, customColor, time, seed * 0.2D);
            if (!"Halo".equals(style)) {
                output.add(new VisualExpansionRenderer.WorldBox(
                    bounds,
                    VisualExpansionRenderer.argb(alpha * 0.07F, color),
                    VisualExpansionRenderer.argb(alpha * 0.80F, color),
                    1.15F
                ));
            }
            if (!"Box".equals(style)) {
                boolean nearby = count < MAX_GLOW_ITEMS && distanceSquared <= 24.0D * 24.0D;
                double phase = time * 1.7D + seed * VisualEffects.TAU;
                double radius = Math.max(0.22D, bounds.getLengthX() * 0.8D);
                radius *= 1.0D + 0.035D * Math.sin(phase);
                Vec3d center = new Vec3d(interpolated.x, bounds.minY + 0.028D, interpolated.z);
                boolean textured = nearby && glows != null && glow > 0.01D
                    && glowing < MAX_GLOW_ITEMS && glows.size() < effectLimit;
                VisualEffects.line(lines, VisualEffects.arc(center, VisualEffects.X, VisualEffects.Z,
                    radius, 0.0D, VisualEffects.TAU, nearby ? 32 : 16),
                    color, alpha * (textured ? 0.22D : 0.38D), 1.0F, 0.0D);
                if (!textured) {
                    List<Vec3d> sweep = VisualEffects.arc(center, VisualEffects.X, VisualEffects.Z,
                        radius, -phase - Math.PI, Math.PI * 1.3D, nearby ? 20 : 8);
                    VisualEffects.trail(lines, sweep, theme, customColor, time + seed,
                        alpha, 0.08D, 1.0D, 1.15F, 0.0D, nearby ? 6 : 3);
                }
                if (textured) {
                    double size = radius * 1.65D;
                    glows.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.PULSE,
                        center.add(-size, 0.006D, -size), center.add(size, 0.006D, -size),
                        center.add(size, 0.006D, size), center.add(-size, 0.006D, size),
                        VisualExpansionRenderer.argb((float) (alpha * glow * 0.76D), color)));
                    if (glowing < MAX_BEAM_ITEMS && glows.size() + 2 <= effectLimit) {
                        // Crossed, tapered filaments retain a readable volume from every angle.
                        // World-relative geometry uses interpolated item motion, never a tick-step bob.
                        double height = 0.56D + 0.12D * Math.sin(phase * 0.8D);
                        int accent = VisualEffects.color(theme, customColor, time, seed * 0.2D + 0.35D);
                        beam(glows, center, phase * 0.20D, radius * 0.32D, height,
                            color, accent, alpha * glow * 0.64D);
                        beam(glows, center, phase * 0.20D + Math.PI * 0.5D, radius * 0.24D,
                            height * 0.92D, accent, color, alpha * glow * 0.46D);
                    }
                    if (glowing < 8 && glows.size() < effectLimit) {
                        double core = radius * 0.65D;
                        glows.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.SOFT,
                            center.add(-core, 0.012D, -core), center.add(core, 0.012D, -core),
                            center.add(core, 0.012D, core), center.add(-core, 0.012D, core),
                            VisualExpansionRenderer.argb((float) (alpha * glow * 0.36D),
                                VisualEffects.mix(color, 0xFFFFFF, 0.55D))));
                    }
                    glowing++;
                }
            }
            count++;
        }
    }

    private static void beam(List<WorldGlowRenderer.Quad> glows, Vec3d base, double angle,
                             double width, double height, int bottom, int top, double alpha) {
        Vec3d side = new Vec3d(Math.cos(angle) * width, 0.0D, Math.sin(angle) * width);
        Vec3d tip = base.add(0.0D, height, 0.0D);
        // U follows the beam upward, so the texture's long filaments and vertex fade
        // run vertically; V spans the taper. This is deliberately not a solid column.
        glows.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.RIBBON,
            base.subtract(side), tip.subtract(side.multiply(0.18D)),
            tip.add(side.multiply(0.18D)), base.add(side),
            VisualExpansionRenderer.argb((float) alpha, bottom),
            VisualExpansionRenderer.argb((float) (alpha * 0.025D), top)));
    }

    private void update(ModuleContext context) {
        style = context.mode("Style", "Halo");
        theme = context.mode("Theme", "Celeste");
        customColor = context.colorPick("Color", 0xF09BD0) & 0xFFFFFF;
        glow = MathHelper.clamp(context.slider("Glow", 0.45D), 0.0D, 1.0D);
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.76D), 0.1D, 1.0D);
    }
}
