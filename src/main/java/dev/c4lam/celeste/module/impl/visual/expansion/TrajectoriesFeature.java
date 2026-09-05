package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/** Bounded projectile preview with a soft path and surface-aligned landing reticle. */
public final class TrajectoriesFeature implements ModuleFeature {
    private static final int MAX_EFFECT_QUADS = 96;
    private static final int MAX_RIBBON_SEGMENTS = 64;
    private volatile boolean enabled;
    private volatile boolean pearls = true;
    private volatile boolean bows = true;
    private volatile boolean landing = true;
    private volatile int steps = 80;
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xFF4FA3;
    private volatile double glow = 0.55D;
    private volatile double opacity = 0.90D;
    private volatile boolean trailFade = true;

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
        extract(context, paths, boxes, null);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldLineStrip> paths,
                 List<VisualExpansionRenderer.WorldBox> boxes, List<WorldGlowRenderer.Quad> glows) {
        if (!enabled) {
            return;
        }
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || player.isSpectator() || player.getEntityWorld() != context.world()) {
            return;
        }

        float tickProgress = context.tickCounter().getTickProgress(false);
        // Never preview another hand while eating/blocking/charging an unrelated item.
        // Vanilla tries MAIN_HAND before OFF_HAND; an uncharged main-hand bow/crossbow
        // consumes use to begin charging, so it must not fall through to an offhand pearl.
        Motion motion;
        if (player.isUsingItem()) {
            motion = motion(player, player.getActiveItem(), tickProgress);
        } else {
            ItemStack main = player.getMainHandStack();
            motion = motion(player, main, tickProgress);
            if (motion == null && !main.isOf(Items.BOW) && !main.isOf(Items.CROSSBOW)
                && !main.isOf(Items.ENDER_PEARL) && !main.isOf(Items.SHIELD)) {
                motion = motion(player, player.getOffHandStack(), tickProgress);
            }
        }
        if (motion == null) {
            return;
        }

        // Vanilla projectiles spawn 0.1 below the eye, not in front of the camera.
        Vec3d position = player.getCameraPosVec(tickProgress).add(0.0D, -0.1D, 0.0D);
        Vec3d velocity = player.getRotationVec(tickProgress).normalize().multiply(motion.velocity());
        if (motion.inheritsMovement()) {
            Vec3d movement = player.getMovement();
            velocity = velocity.add(movement.x, player.isOnGround() ? 0.0D : movement.y, movement.z);
        }
        List<Vec3d> points = new ArrayList<>(steps + 1);
        points.add(position);

        boolean hitSomething = false;
        Vec3d hitNormal = new Vec3d(0.0D, 1.0D, 0.0D);
        for (int index = 0; index < steps; index++) {
            if (!context.world().isChunkLoaded(BlockPos.ofFloored(position))
                || position.y < context.world().getBottomY() - 16.0D) break;
            boolean inWater = context.world().isWater(BlockPos.ofFloored(position));
            // ThrownEntity in 1.21.11 applies gravity THEN drag BEFORE its move.
            if (motion.thrown()) {
                velocity = velocity.add(0.0D, -motion.gravity(), 0.0D).multiply(inWater ? 0.8D : motion.drag());
            }
            Vec3d next = position.add(velocity);
            HitResult hit = context.world().raycast(new RaycastContext(
                position,
                next,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
            ));
            Vec3d rayEnd = hit.getType() == HitResult.Type.MISS ? next : hit.getPos();
            var entityHit = ProjectileUtil.getEntityCollision(context.world(), player, position, rayEnd,
                new Box(position, rayEnd).expand(0.3D),
                entity -> entity != player && !entity.isSpectator() && entity.isAlive()
                    && entity.canBeHitByProjectile() && !entity.isConnectedThroughVehicle(player), 0.3F);
            if (entityHit != null) hit = entityHit;
            if (hit.getType() != HitResult.Type.MISS) {
                points.add(hit.getPos());
                position = hit.getPos();
                hitSomething = true;
                if (hit instanceof BlockHitResult blockHit) {
                    Direction side = blockHit.getSide();
                    hitNormal = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
                } else {
                    hitNormal = velocity.normalize().multiply(-1.0D);
                }
                break;
            }
            points.add(next);
            position = next;
            if (!motion.thrown()) {
                velocity = velocity.multiply(inWater ? 0.6D : motion.drag()).add(0.0D, -motion.gravity(), 0.0D);
            }
        }

        double time = Util.getMeasuringTimeMs() / 1000.0D;
        int effectLimit = glows == null ? 0 : Math.min(768, glows.size() + MAX_EFFECT_QUADS);
        List<WorldGlowRenderer.Quad> effects = glow > 0.01D ? glows : null;
        VisualEffects.trail(paths, points, theme, customColor, time, opacity,
            1.0D, trailFade ? 0.40D : 1.0D, 1.10F, effects == null ? glow : 0.0D, 12);
        projectileRibbon(effects, effectLimit, points, context.camera().getCameraPos(), time);
        if (landing && hitSomething) {
            landingMarker(paths, effects, effectLimit, position, hitNormal, time);
        }
    }

    /** The authoritative predicted points remain untouched; only the glow mesh is resampled linearly. */
    private void projectileRibbon(List<WorldGlowRenderer.Quad> effects, int limit,
                                  List<Vec3d> points, Vec3d camera, double time) {
        if (effects == null || camera == null || points.size() < 2 || effects.size() >= limit) return;
        double[] distances = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            distances[i] = distances[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        double length = distances[distances.length - 1];
        if (length < 1.0E-6D) return;
        List<ConnectedRibbon.Node> nodes = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Vec3d point = points.get(i);
            double progress = distances[i] / length;
            double distance = camera.distanceTo(point);
            double width = MathHelper.clamp(0.030D + distance * 0.0020D, 0.030D, 0.115D);
            // A near-eye fade keeps the first-person bow/hand crisp.
            double alpha = Math.min(1.0D, distance / 0.55D)
                * (trailFade ? 1.0D - progress * 0.60D : 1.0D);
            nodes.add(new ConnectedRibbon.Node(point, width * 2.0D,
                VisualExpansionRenderer.argb((float) (opacity * glow * alpha * 0.86D),
                    VisualEffects.color(theme, customColor, time, progress * 0.75D))));
        }
        ConnectedRibbon.append(effects, nodes, camera, null,
            Math.min(MAX_RIBBON_SEGMENTS, limit - effects.size()), false);
    }

    private void landingMarker(List<VisualExpansionRenderer.WorldLineStrip> paths,
                               List<WorldGlowRenderer.Quad> glows, int limit,
                               Vec3d position, Vec3d normal, double time) {
        Vec3d reference = Math.abs(normal.y) > 0.9D ? VisualEffects.X : new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d axisU = reference.subtract(normal.multiply(reference.dotProduct(normal))).normalize();
        Vec3d axisV = normal.crossProduct(axisU).normalize();
        Vec3d center = position.add(normal.multiply(0.018D));
        int color = VisualEffects.color(theme, customColor, time, 0.25D);
        double radius = 0.23D + 0.012D * Math.sin(time * 3.0D);
        VisualEffects.line(paths, VisualEffects.arc(center, axisU, axisV, radius,
            0.0D, VisualEffects.TAU, 48), color, opacity * 0.52D, 1.1F, glows == null ? glow : 0.0D);
        int highlight = VisualEffects.mix(color, 0xFFFFFF, 0.65D);
        VisualEffects.line(paths, List.of(center.add(axisU.multiply(-0.065D)), center.add(axisU.multiply(0.065D))),
            highlight, opacity, 1.4F, 0.0D);
        VisualEffects.line(paths, List.of(center.add(axisV.multiply(-0.065D)), center.add(axisV.multiply(0.065D))),
            highlight, opacity, 1.4F, 0.0D);
        if (glows != null && glows.size() + 3 <= limit) {
            // Rotate the actual surface basis: the landing glyph hugs walls and ceilings too.
            double rotation = time * 0.42D;
            Vec3d rotatedU = axisU.multiply(Math.cos(rotation)).add(axisV.multiply(Math.sin(rotation)));
            Vec3d rotatedV = axisV.multiply(Math.cos(rotation)).subtract(axisU.multiply(Math.sin(rotation)));
            impactGlow(glows, WorldGlowRenderer.Texture.PULSE, center, rotatedU, rotatedV, radius * 2.1D,
                color, opacity * glow * 0.68D);
            double reverse = -time * 0.61D;
            Vec3d innerU = axisU.multiply(Math.cos(reverse)).add(axisV.multiply(Math.sin(reverse)));
            Vec3d innerV = axisV.multiply(Math.cos(reverse)).subtract(axisU.multiply(Math.sin(reverse)));
            impactGlow(glows, WorldGlowRenderer.Texture.PULSE, center.add(normal.multiply(0.003D)),
                innerU, innerV, radius * 1.25D, VisualEffects.color(theme, customColor, time, 0.65D),
                opacity * glow * 0.33D);
            impactGlow(glows, WorldGlowRenderer.Texture.SOFT, center.add(normal.multiply(0.006D)), axisU, axisV,
                0.14D, highlight, opacity * glow * 0.50D);
            impactArc(glows, limit, center.add(normal.multiply(0.009D)), axisU, axisV,
                radius + 0.10D, time);
        }
    }

    private void impactArc(List<WorldGlowRenderer.Quad> glows, int limit, Vec3d center,
                           Vec3d axisU, Vec3d axisV, double radius, double time) {
        int segments = 28;
        double inner = radius - 0.055D;
        double outer = radius + 0.055D;
        for (int i = 0; i < segments && glows.size() < limit; i++) {
            double start = i / (double) segments;
            double end = (i + 1.0D) / segments;
            double angleA = time * 1.7D + start * Math.PI * 1.2D;
            double angleB = time * 1.7D + end * Math.PI * 1.2D;
            Vec3d directionA = axisU.multiply(Math.cos(angleA)).add(axisV.multiply(Math.sin(angleA)));
            Vec3d directionB = axisU.multiply(Math.cos(angleB)).add(axisV.multiply(Math.sin(angleB)));
            glows.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.RIBBON,
                center.add(directionA.multiply(inner)), center.add(directionB.multiply(inner)),
                center.add(directionB.multiply(outer)), center.add(directionA.multiply(outer)),
                VisualExpansionRenderer.argb((float) (opacity * glow * start * 0.85D),
                    VisualEffects.color(theme, customColor, time, start)),
                VisualExpansionRenderer.argb((float) (opacity * glow * end * 0.85D),
                    VisualEffects.color(theme, customColor, time, end)),
                (float) start, (float) end));
        }
    }

    private static void impactGlow(List<WorldGlowRenderer.Quad> glows, WorldGlowRenderer.Texture texture,
                                   Vec3d center, Vec3d axisU, Vec3d axisV, double size, int color, double alpha) {
        Vec3d u = axisU.multiply(size);
        Vec3d v = axisV.multiply(size);
        glows.add(new WorldGlowRenderer.Quad(texture, center.subtract(u).subtract(v), center.add(u).subtract(v),
            center.add(u).add(v), center.subtract(u).add(v), VisualExpansionRenderer.argb((float) alpha, color)));
    }

    private Motion motion(ClientPlayerEntity player, ItemStack held, float tickProgress) {
        if (pearls && held.isOf(Items.ENDER_PEARL)) {
            return new Motion(1.5D, 0.03D, 0.99D, true, true);
        }
        if (!bows) {
            return null;
        }
        if (held.isOf(Items.BOW)) {
            if (!player.isUsingItem() || !player.getActiveItem().isOf(Items.BOW)) {
                return null;
            }
            float power = MathHelper.lerp(tickProgress, BowItem.getPullProgress(player.getItemUseTime()),
                BowItem.getPullProgress(player.getItemUseTime() + 1));
            return power < 0.1F ? null : new Motion(power * 3.0D, 0.05D, 0.99D, false, true);
        }
        if (held.isOf(Items.CROSSBOW) && CrossbowItem.isCharged(held)) {
            // Fireworks do not follow arrow gravity; do not display an incorrect arrow arc.
            ChargedProjectilesComponent projectiles = held.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (projectiles == null || projectiles.contains(Items.FIREWORK_ROCKET)) return null;
            return new Motion(3.15D, 0.05D, 0.99D, false, false);
        }
        return null;
    }

    private void update(ModuleContext context) {
        pearls = context.toggle("Pearls", true);
        bows = context.toggle("Bows", true);
        landing = context.toggle("Landing", true);
        steps = MathHelper.clamp((int) Math.round(context.slider("Steps", 80.0D)), 40, 160);
        theme = context.mode("Theme", "Celeste");
        customColor = context.colorPick("Color", 0xFF4FA3) & 0xFFFFFF;
        glow = MathHelper.clamp(context.slider("Glow", 0.55D), 0.0D, 1.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.90D), 0.1D, 1.0D);
        trailFade = context.toggle("Trail fade", true);
    }

    private record Motion(double velocity, double gravity, double drag, boolean thrown, boolean inheritsMovement) {
    }
}
