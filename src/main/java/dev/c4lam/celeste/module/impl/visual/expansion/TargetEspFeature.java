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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Frame-animated solid orbital models framing the selected target. */
public final class TargetEspFeature implements ModuleFeature {
    private static final int MAX_EFFECT_QUADS = 256;
    private static final int TUBE_SIDES = 4;
    private static final int MIN_MODEL_QUADS = 3 * TUBE_SIDES;
    private static final SolidVisualMesh.Point TUBE_AXIS = new SolidVisualMesh.Point(0.0D, 1.0D, 0.0D);
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
        extract(context, boxes, lines, glows, 768);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows, int quadLimit) {
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
        double ringRadius = MathHelper.clamp(target.getWidth() * 1.12D, 0.60D, 2.8D) * radius;
        double bodyYaw = TargetAuraShape.bodyYawRadians(target.lastBodyYaw, target.bodyYaw, tickProgress);
        boolean playerFit = target instanceof PlayerEntity && !target.isGliding()
            && !target.isInSwimmingPose() && !target.isSleeping();
        int color = VisualEffects.color(theme, customColor, time, 0.0D);
        if (glows == null) {
            // Compatibility extraction has no material batch; keep a simple
            // target box there, never a hairline fallback in the actual path.
            boxes.add(new VisualExpansionRenderer.WorldBox(bounds.expand(0.065D * radius),
                VisualExpansionRenderer.argb((float) (alpha * 0.045D), color),
                VisualExpansionRenderer.argb((float) (alpha * 0.85D), color), 1.4F));
            return;
        }
        int effectLimit = Math.min(Math.min(768, Math.max(0, quadLimit)), glows.size() + MAX_EFFECT_QUADS);
        // Glow controls the accents; the filled model remains visible at zero.
        int modelLimit = Math.max(glows.size(), effectLimit - (glow > 0.01D ? 3 : 0));

        switch (style) {
            case "Helix" -> helix(glows, modelLimit, bounds, center, ringRadius, phase, time, alpha, bodyYaw, playerFit);
            case "Scan" -> scan(glows, modelLimit, bounds, center, ringRadius, phase, time, alpha, bodyYaw, playerFit);
            case "Box" -> box(glows, modelLimit, bounds, color, alpha, time);
            default -> orbit(glows, modelLimit, bounds, center, ringRadius, phase, time, alpha, bodyYaw, playerFit);
        }
        if (glow > 0.01D && glows.size() + 3 <= effectLimit) {
            Vec3d foot = new Vec3d(center.x, bounds.minY + 0.02D, center.z);
            groundGlow(glows, WorldGlowRenderer.Texture.SOFT, foot, ringRadius * 1.52D, color, alpha * glow * 0.12D);
            groundGlow(glows, WorldGlowRenderer.Texture.PULSE, foot.add(0.0D, 0.008D, 0.0D),
                ringRadius * 1.34D, color, alpha * glow * 0.50D);
            groundGlow(glows, WorldGlowRenderer.Texture.PULSE, foot.add(0.0D, 0.015D, 0.0D),
                ringRadius * (0.82D + 0.06D * Math.sin(phase * 0.72D)),
                VisualEffects.color(theme, customColor, time, 0.48D), alpha * glow * 0.24D);
        }
    }

    private static void groundGlow(List<WorldGlowRenderer.Quad> output, WorldGlowRenderer.Texture texture,
                                   Vec3d center, double size, int color, double alpha) {
        output.add(new WorldGlowRenderer.Quad(texture, center.add(-size, 0, -size), center.add(size, 0, -size),
            center.add(size, 0, size), center.add(-size, 0, size), VisualExpansionRenderer.argb((float) alpha, color)));
    }

    private void orbit(List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                       double ringRadius, double phase, double time, double alpha, double bodyYaw, boolean playerFit) {
        int[] budgets = modelBudgets(effects.size(), limit, 24, 16);
        for (int orbit = 0; orbit < 2; orbit++) {
            if (budgets[orbit] < MIN_MODEL_QUADS) continue;
            List<Vec3d> points = shapePoints("Orbit", orbit, bounds, center, ringRadius, phase, bodyYaw, playerFit);
            // 24*4 + 16*4 = 160 filled facets, with both square-section loops closed.
            solid(effects, Math.min(limit, effects.size() + budgets[orbit]), points,
                time + orbit * 1.4D, alpha * (orbit == 0 ? 1.0D : 0.82D),
                orbit == 0 ? 0.16D : 0.13D, orbit == 0 ? 0.12D : 0.10D, true);
        }
    }

    private void helix(List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                       double ringRadius, double phase, double time, double alpha, double bodyYaw, boolean playerFit) {
        int[] budgets = modelBudgets(effects.size(), limit, 20, 20);
        for (int strand = 0; strand < 2; strand++) {
            if (budgets[strand] < MIN_MODEL_QUADS) continue;
            List<Vec3d> points = shapePoints("Helix", strand, bounds, center, ringRadius, phase, bodyYaw, playerFit);
            solid(effects, Math.min(limit, effects.size() + budgets[strand]), points,
                time + strand, alpha, 0.14D, 0.105D, false);
        }
    }

    private void scan(List<WorldGlowRenderer.Quad> effects, int limit, Box bounds, Vec3d center,
                      double ringRadius, double phase, double time, double alpha, double bodyYaw, boolean playerFit) {
        int[] budgets = modelBudgets(effects.size(), limit, 24, 16);
        for (int layer = 0; layer < 2; layer++) {
            if (budgets[layer] < MIN_MODEL_QUADS) continue;
            List<Vec3d> points = shapePoints("Scan", layer, bounds, center, ringRadius, phase, bodyYaw, playerFit);
            solid(effects, Math.min(limit, effects.size() + budgets[layer]), points,
                time + layer * 1.2D, alpha * (layer == 0 ? 0.96D : 0.58D),
                layer == 0 ? 0.16D : 0.10D, layer == 0 ? 0.12D : 0.105D, true);
        }
    }

    private static List<Vec3d> shapePoints(String style, int layer, Box bounds, Vec3d center,
                                          double ringRadius, double phase, double bodyYaw, boolean playerFit) {
        List<TargetAuraShape.Point> shape = TargetAuraShape.path(style, layer, ringRadius,
            bounds.getLengthY(), phase, bodyYaw, playerFit);
        List<Vec3d> points = new ArrayList<>(shape.size());
        for (TargetAuraShape.Point point : shape) {
            points.add(new Vec3d(center.x + point.x(), bounds.minY + point.y(), center.z + point.z()));
        }
        return points;
    }

    /** Reserve both complete models before either can spend the shared budget. */
    private static int[] modelBudgets(int current, int limit, int firstSegments, int secondSegments) {
        int total = Math.max(0, Math.min((firstSegments + secondSegments) * TUBE_SIDES, limit - current))
            / TUBE_SIDES * TUBE_SIDES;
        if (total < MIN_MODEL_QUADS) return new int[]{0, 0};
        if (total < 2 * MIN_MODEL_QUADS) return new int[]{Math.min(firstSegments * TUBE_SIDES, total), 0};
        int first = total * firstSegments / (firstSegments + secondSegments) / TUBE_SIDES * TUBE_SIDES;
        first = Math.max(MIN_MODEL_QUADS,
            Math.min(total - MIN_MODEL_QUADS, Math.min(firstSegments * TUBE_SIDES, first)));
        return new int[]{first, Math.min(secondSegments * TUBE_SIDES, total - first)};
    }

    private int solid(List<WorldGlowRenderer.Quad> effects, int limit, List<Vec3d> points,
                      double time, double alpha, double halfWidth, double halfDepth, boolean closed) {
        if (effects == null || points.size() < 2 || effects.size() >= limit || alpha <= 0.002D) return 0;
        List<SolidVisualMesh.Node> nodes = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            double progress = (double) i / (closed ? points.size() : points.size() - 1);
            double taper = closed ? 1.0D : 0.64D + 0.36D * Math.sin(Math.PI * progress);
            double fade = closed ? 0.86D + 0.14D * Math.pow(Math.sin(VisualEffects.TAU * progress), 2.0D)
                : 0.46D + 0.54D * Math.sin(Math.PI * progress);
            Vec3d point = points.get(i);
            int color = VisualEffects.color(theme, customColor, time, progress);
            nodes.add(new SolidVisualMesh.Node(new SolidVisualMesh.Point(point.x, point.y, point.z),
                halfWidth * taper, halfDepth * taper,
                VisualExpansionRenderer.argb((float) (alpha * fade * (0.72D + glow * 0.28D)), color)));
        }
        return SolidVisuals.append(effects, nodes, TUBE_AXIS, TUBE_SIDES, limit, closed);
    }

    private void box(List<WorldGlowRenderer.Quad> effects, int limit, Box original,
                     int color, double alpha, double time) {
        if (effects.size() + 6 > limit) return;
        Box bounds = original.expand(0.065D * radius);
        Vec3d a = new Vec3d(bounds.minX, bounds.minY, bounds.minZ);
        Vec3d b = new Vec3d(bounds.maxX, bounds.minY, bounds.minZ);
        Vec3d c = new Vec3d(bounds.maxX, bounds.maxY, bounds.minZ);
        Vec3d d = new Vec3d(bounds.minX, bounds.maxY, bounds.minZ);
        Vec3d e = new Vec3d(bounds.minX, bounds.minY, bounds.maxZ);
        Vec3d f = new Vec3d(bounds.maxX, bounds.minY, bounds.maxZ);
        Vec3d g = new Vec3d(bounds.maxX, bounds.maxY, bounds.maxZ);
        Vec3d h = new Vec3d(bounds.minX, bounds.maxY, bounds.maxZ);
        int main = VisualExpansionRenderer.argb((float) (alpha * 0.34D), color);
        int accent = VisualExpansionRenderer.argb((float) (alpha * 0.28D),
            VisualEffects.color(theme, customColor, time, 0.5D));
        prismFace(effects, a, b, c, d, main, accent);
        prismFace(effects, f, e, h, g, main, accent);
        prismFace(effects, e, a, d, h, main, accent);
        prismFace(effects, b, f, g, c, main, accent);
        prismFace(effects, d, c, g, h, accent, main);
        prismFace(effects, e, f, b, a, accent, main);
        // Six exact box faces plus a real solid travelling seam; no line corners.
        double y = bounds.minY + bounds.getLengthY() * (0.5D + 0.5D * Math.sin(time * speed * 1.8D));
        List<Vec3d> seam = List.of(new Vec3d(bounds.minX, y, bounds.minZ),
            new Vec3d(bounds.maxX, y, bounds.minZ), new Vec3d(bounds.maxX, y, bounds.maxZ),
            new Vec3d(bounds.minX, y, bounds.maxZ));
        solid(effects, limit, seam, time, alpha * 0.88D, 0.065D, 0.055D, true);
    }

    private static void prismFace(List<WorldGlowRenderer.Quad> effects, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                                  int color, int accent) {
        effects.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.PRISM, a, b, c, d, color, accent));
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
