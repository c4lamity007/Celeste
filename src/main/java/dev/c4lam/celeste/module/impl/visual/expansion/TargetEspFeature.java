package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.hud.HudTargetTracker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Frame-animated energy ribbons framing the target without obscuring its silhouette. */
public final class TargetEspFeature implements ModuleFeature {
    private static final int MAX_EFFECT_QUADS = 128;
    private static final Vec3d RIBBON_AXIS = new Vec3d(0.0D, 1.0D, 0.0D);
    private volatile boolean enabled;
    private volatile String style = "Orbit";
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xFF4FA3;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.86D;
    private volatile double speed = 1.0D;
    private volatile double radius = 1.0D;
    private volatile double glow = 0.65D;
    private int acquiredId = -1;
    private long acquiredAt;
    private long lastSeenAt;

    @Override
    public String name() {
        return "Target ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        acquiredId = -1;
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
        acquiredId = -1;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        acquiredId = -1;
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines) {
        extract(context, boxes, lines, null);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows) {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (!enabled || self == null || self.getEntityWorld() != context.world()) return;
        long now = Util.getMeasuringTimeMs();
        LivingEntity target = select(self);
        double exit = 1.0D;
        if (target == null) {
            // Retain only the id for a short fade; resolve against this world each frame.
            if (now - lastSeenAt < 220L && context.world().getEntityById(acquiredId) instanceof LivingEntity cached
                && valid(self, cached)) {
                target = cached;
                exit = 1.0D - (now - lastSeenAt) / 220.0D;
                exit *= exit;
            } else {
                acquiredId = -1;
                return;
            }
        } else {
            lastSeenAt = now;
        }

        if (target.getId() != acquiredId) {
            acquiredId = target.getId();
            acquiredAt = now;
        }
        double enter = MathHelper.clamp((now - acquiredAt) / 180.0D, 0.0D, 1.0D);
        enter = enter * enter * (3.0D - 2.0D * enter);
        double time = now / 1000.0D;
        double phase = time * speed * 1.8D;
        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d interpolated = target.getLerpedPos(tickProgress);
        double distance = self.getLerpedPos(tickProgress).distanceTo(interpolated);
        double distanceFade = MathHelper.clamp((range - distance) / 4.0D, 0.0D, 1.0D);
        double alpha = opacity * enter * exit * distanceFade * (0.94D + 0.06D * Math.sin(phase * 1.2D));
        Box bounds = target.getBoundingBox().offset(interpolated.subtract(target.getEntityPos()));
        Vec3d center = bounds.getCenter();
        double ringRadius = MathHelper.clamp(target.getWidth() * 0.76D, 0.40D, 2.4D) * radius;
        int color = VisualEffects.color(theme, customColor, time, 0.0D);
        // Each feature owns a bounded share of the shared material batches.
        int effectLimit = glows == null ? 0 : Math.min(768, glows.size() + MAX_EFFECT_QUADS);
        List<WorldGlowRenderer.Quad> effects = glow > 0.01D ? glows : null;

        switch (style) {
            case "Helix" -> helix(lines, effects, effectLimit, bounds, center, ringRadius, phase, time, alpha);
            case "Scan" -> scan(lines, effects, effectLimit, bounds, center, ringRadius, phase, time, alpha);
            case "Box" -> box(boxes, lines, effects, effectLimit, bounds, color, alpha, time);
            default -> orbit(lines, effects, effectLimit, bounds, center, ringRadius, phase, time, alpha);
        }
        if (effects != null && effects.size() + 2 <= effectLimit) {
            Vec3d foot = new Vec3d(center.x, bounds.minY + 0.02D, center.z);
            groundGlow(effects, WorldGlowRenderer.Texture.SOFT, foot, ringRadius * 1.55D, color, alpha * glow * 0.09D);
            groundGlow(effects, WorldGlowRenderer.Texture.PULSE, foot.add(0.0D, 0.008D, 0.0D),
                ringRadius * 1.26D, color, alpha * glow * 0.54D);
        }
    }

    private static void groundGlow(List<WorldGlowRenderer.Quad> output, WorldGlowRenderer.Texture texture,
                                   Vec3d center, double size, int color, double alpha) {
        output.add(new WorldGlowRenderer.Quad(texture, center.add(-size, 0, -size), center.add(size, 0, -size),
            center.add(size, 0, size), center.add(-size, 0, size), VisualExpansionRenderer.argb((float) alpha, color)));
    }

    private void orbit(List<VisualExpansionRenderer.WorldLineStrip> lines,
                       List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                       double ringRadius, double phase, double time, double alpha) {
        for (int orbit = 0; orbit < 2; orbit++) {
            List<Vec3d> points = new ArrayList<>(49);
            double head = phase + orbit * Math.PI;
            for (int i = 0; i <= 48; i++) {
                double angle = head - 1.42D * Math.PI + 1.42D * Math.PI * i / 48.0D;
                double y = center.y + Math.sin(angle + orbit * Math.PI) * bounds.getLengthY() * 0.32D;
                points.add(new Vec3d(center.x + Math.cos(angle) * ringRadius, y,
                    center.z + Math.sin(angle) * ringRadius));
            }
            ribbon(effects, limit, points, time + orbit * 1.4D, alpha,
                0.18D + bounds.getLengthY() * 0.07D, true, false, 48);
            VisualEffects.trail(lines, points, theme, customColor, time + orbit * 1.4D,
                alpha * 0.72D, 0.025D, 1.0D, 1.10F, effects == null ? glow : 0.0D, 8);
        }
        VisualEffects.line(lines, VisualEffects.arc(new Vec3d(center.x, bounds.minY + 0.025D, center.z),
            VisualEffects.X, VisualEffects.Z, ringRadius * 0.86D, 0.0D, VisualEffects.TAU, 48),
            VisualEffects.color(theme, customColor, time, 0.2D), alpha * 0.16D, 1.0F, 0.0D);
    }

    private void helix(List<VisualExpansionRenderer.WorldLineStrip> lines,
                       List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                       double ringRadius, double phase, double time, double alpha) {
        for (int strand = 0; strand < 2; strand++) {
            List<Vec3d> points = new ArrayList<>(57);
            for (int i = 0; i <= 56; i++) {
                double t = i / 56.0D;
                double angle = phase + strand * Math.PI + t * VisualEffects.TAU * 1.4D;
                double y = bounds.minY + 0.06D + t * (bounds.getLengthY() - 0.06D);
                points.add(new Vec3d(center.x + Math.cos(angle) * ringRadius, y,
                    center.z + Math.sin(angle) * ringRadius));
            }
            ribbon(effects, limit, points, time + strand, alpha, 0.28D, false, false, 56);
            VisualEffects.trail(lines, points, theme, customColor, time + strand,
                alpha * 0.62D, 0.12D, 0.85D, 1.0F, effects == null ? glow : 0.0D, 8);
        }
    }

    private void scan(List<VisualExpansionRenderer.WorldLineStrip> lines,
                      List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                      double ringRadius, double phase, double time, double alpha) {
        double height = (0.5D + Math.sin(phase) * 0.5D) * Math.max(0.05D, bounds.getLengthY() - 0.12D);
        Vec3d ringCenter = new Vec3d(center.x, bounds.minY + 0.06D + height, center.z);
        List<Vec3d> ring = VisualEffects.arc(ringCenter, VisualEffects.X, VisualEffects.Z,
            ringRadius, -phase * 0.38D, VisualEffects.TAU, 64);
        ribbon(effects, limit, ring, time, alpha * 0.88D, 0.38D, false, true, 64);
        VisualEffects.line(lines, ring, VisualEffects.color(theme, customColor, time, 0.2D),
            alpha * 0.78D, 1.2F, effects == null ? glow : 0.0D);
        // A second, counter-moving arc makes the scan read as depth, not a flat hoop.
        List<Vec3d> undertow = VisualEffects.arc(ringCenter.add(0.0D, -0.11D, 0.0D),
            VisualEffects.X, VisualEffects.Z, ringRadius * 1.02D,
            phase * 0.7D, Math.PI * 1.22D, 32);
        ribbon(effects, limit, undertow, time + 1.2D, alpha * 0.33D, 0.18D, true, false, 32);
    }

    /** Vertical ribbon cross-sections stay stable as the camera moves around the target. */
    private void ribbon(List<WorldGlowRenderer.Quad> effects, int limit, List<Vec3d> points,
                        double time, double alpha, double width, boolean comet, boolean closed, int segments) {
        if (effects == null || points.size() < 2 || effects.size() >= limit) return;
        double[] distances = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            distances[i] = distances[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        double length = distances[distances.length - 1];
        if (length < 1.0E-6D) return;
        List<ConnectedRibbon.Node> nodes = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            double progress = distances[i] / length;
            double fade = comet ? Math.pow(progress, 1.35D)
                : closed ? 0.75D - 0.25D * Math.cos(VisualEffects.TAU * progress)
                : 0.50D + 0.50D * Math.sin(Math.PI * progress);
            int color = VisualEffects.color(theme, customColor, time, progress * (closed ? 1.0D : 0.7D));
            nodes.add(new ConnectedRibbon.Node(points.get(i), width * (0.25D + fade * 0.75D),
                VisualExpansionRenderer.argb((float) (alpha * glow * fade * 0.82D), color)));
        }
        // Reuse the exact first section at a closed seam, including color and width.
        if (closed) nodes.set(nodes.size() - 1, nodes.getFirst());
        ConnectedRibbon.append(effects, nodes, Vec3d.ZERO, RIBBON_AXIS,
            Math.min(segments, limit - effects.size()), closed);
    }

    private void box(List<VisualExpansionRenderer.WorldBox> boxes,
                     List<VisualExpansionRenderer.WorldLineStrip> lines,
                     List<WorldGlowRenderer.Quad> effects, int limit, Box original, int color, double alpha, double time) {
        Box bounds = original.expand(0.065D * radius);
        boxes.add(new VisualExpansionRenderer.WorldBox(bounds,
            VisualExpansionRenderer.argb((float) (alpha * 0.045D), color),
            VisualExpansionRenderer.argb((float) (alpha * 0.20D), color), 1.0F));
        double horizontal = bounds.getLengthX() * 0.26D;
        double vertical = Math.min(0.25D, bounds.getLengthY() * 0.17D);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    Vec3d corner = new Vec3d(x == 0 ? bounds.minX : bounds.maxX,
                        y == 0 ? bounds.minY : bounds.maxY, z == 0 ? bounds.minZ : bounds.maxZ);
                    VisualEffects.line(lines, List.of(corner.add((x == 0 ? 1 : -1) * horizontal, 0, 0),
                        corner, corner.add(0, (y == 0 ? 1 : -1) * vertical, 0)), color, alpha, 1.4F,
                        effects == null ? glow : 0.0D);
                    VisualEffects.line(lines, List.of(corner, corner.add(0, 0, (z == 0 ? 1 : -1) * horizontal)),
                        color, alpha, 1.4F, effects == null ? glow : 0.0D);
                }
            }
        }
        // Keep the box's exact silhouette, with one connected light seam travelling around it.
        double y = bounds.minY + bounds.getLengthY() * (0.5D + 0.5D * Math.sin(time * speed * 1.8D));
        List<Vec3d> seam = List.of(new Vec3d(bounds.minX, y, bounds.minZ),
            new Vec3d(bounds.maxX, y, bounds.minZ), new Vec3d(bounds.maxX, y, bounds.maxZ),
            new Vec3d(bounds.minX, y, bounds.maxZ), new Vec3d(bounds.minX, y, bounds.minZ));
        ribbon(effects, limit, seam, time, alpha * 0.72D, 0.22D, false, true, 32);
    }

    private LivingEntity select(ClientPlayerEntity self) {
        LivingEntity target = HudTargetTracker.getInstance().activeTarget(MinecraftClient.getInstance());
        return target != null && valid(self, target) ? target : null;
    }

    private boolean valid(ClientPlayerEntity self, LivingEntity target) {
        return target != self && target.getEntityWorld() == self.getEntityWorld()
            && target.isAlive() && !target.isRemoved() && !target.isSpectator()
            && !(target instanceof AbstractClientPlayerEntity player && FriendManager.getInstance().isFriend(player))
            && self.squaredDistanceTo(target) <= range * range;
    }

    private void update(ModuleContext context) {
        style = context.mode("Style", "Orbit");
        theme = context.mode("Theme", "Celeste");
        customColor = context.colorPick("Color", 0xFF4FA3) & 0xFFFFFF;
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.86D), 0.1D, 1.0D);
        speed = MathHelper.clamp(context.slider("Speed", 1.0D), 0.2D, 3.0D);
        radius = MathHelper.clamp(context.slider("Radius", 1.0D), 0.7D, 1.6D);
        glow = MathHelper.clamp(context.slider("Glow", 0.65D), 0.0D, 1.0D);
    }
}
