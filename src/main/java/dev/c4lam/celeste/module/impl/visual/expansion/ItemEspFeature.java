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

/** Interpolated dropped-item gems and solid halos; never changes an entity's glow/team state. */
public final class ItemEspFeature implements ModuleFeature {
    private static final int MAX_ITEMS = 96;
    private static final int MAX_GLOW_ITEMS = 24;
    private static final int MAX_GEM_ITEMS = 6;
    private static final int MAX_HALO_ITEMS = 2;
    private static final int MAX_EFFECT_QUADS = 128;
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
        extract(context, output, lines, glows, 768);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows, int quadLimit) {
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
        int effectLimit = glows == null ? 0 : Math.min(Math.min(768, Math.max(0, quadLimit)), glows.size() + MAX_EFFECT_QUADS);
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
                boolean glowAvailable = glows != null && glow > 0.01D;
                boolean textured = nearby && glowAvailable && glowing < MAX_GLOW_ITEMS;
                // A full shared mesh budget skips detail, rather than replacing a
                // solid nearby marker with a suddenly thin ring.
                if (!textured && (!nearby || !glowAvailable)) {
                    VisualEffects.line(lines, VisualEffects.arc(center, VisualEffects.X, VisualEffects.Z,
                        radius, 0.0D, VisualEffects.TAU, nearby ? 32 : 16),
                        color, alpha * 0.38D, 1.0F, 0.0D);
                    List<Vec3d> sweep = VisualEffects.arc(center, VisualEffects.X, VisualEffects.Z,
                        radius, -phase - Math.PI, Math.PI * 1.3D, nearby ? 20 : 8);
                    VisualEffects.trail(lines, sweep, theme, customColor, time + seed,
                        alpha, 0.08D, 1.0D, 1.15F, 0.0D, nearby ? 6 : 3);
                }
                if (textured && glows.size() < effectLimit) {
                    if (glowing < MAX_GEM_ITEMS && glows.size() + 8 <= effectLimit) {
                        double gemRadius = Math.max(0.18D, radius * 0.90D);
                        Vec3d gemCenter = center.add(0.0D, 0.48D + 0.06D * Math.sin(phase * 1.2D), 0.0D);
                        int gemColor = VisualExpansionRenderer.argb((float) (alpha * Math.min(1.0D, glow * 1.8D)), color);
                        SolidVisuals.faces(glows, SolidVisualMesh.diamond(point(gemCenter), gemRadius,
                            gemRadius * 1.70D, gemColor, effectLimit - glows.size()), effectLimit);
                    }
                    if (glowing < MAX_HALO_ITEMS && glows.size() + 16 <= effectLimit) {
                        solidHalo(glows, effectLimit, center, radius, phase, time, seed, alpha);
                    }
                    double size = radius * 1.80D;
                    if (glows.size() < effectLimit) {
                        glows.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.PULSE,
                            center.add(-size, 0.006D, -size), center.add(size, 0.006D, -size),
                            center.add(size, 0.006D, size), center.add(-size, 0.006D, size),
                            VisualExpansionRenderer.argb((float) (alpha * glow * 0.46D), color)));
                    }
                    glowing++;
                }
            }
            count++;
        }
    }

    private void solidHalo(List<WorldGlowRenderer.Quad> glows, int limit, Vec3d base,
                           double radius, double phase, double time, double seed, double alpha) {
        List<SolidVisualMesh.Node> nodes = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            double progress = i / 8.0D;
            double angle = phase * 0.28D + progress * VisualEffects.TAU;
            Vec3d center = base.add(Math.cos(angle) * radius * 1.72D,
                0.16D + 0.025D * Math.sin(phase), Math.sin(angle) * radius * 1.72D);
            nodes.add(new SolidVisualMesh.Node(point(center), 0.085D, 0.095D,
                VisualExpansionRenderer.argb((float) (alpha * Math.min(1.0D, glow * 1.65D)),
                    VisualEffects.color(theme, customColor, time, seed * 0.2D + progress * 0.45D))));
        }
        SolidVisuals.append(glows, nodes, new SolidVisualMesh.Point(0.0D, 1.0D, 0.0D), 4,
            Math.min(limit, glows.size() + 32), true);
    }

    private static SolidVisualMesh.Point point(Vec3d point) {
        return new SolidVisualMesh.Point(point.x, point.y, point.z);
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
