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

/** Image-bloom trail, continuous shader core and compact impact marker. Prediction stays exact. */
public final class TrajectoriesFeature implements ModuleFeature {
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
        extract(context, paths, boxes, glows, 768);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldLineStrip> paths,
                 List<VisualExpansionRenderer.WorldBox> boxes, List<WorldGlowRenderer.Quad> glows, int quadLimit) {
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

        Vec3d camera = context.camera().getCameraPos();
        TrajectoryAppearance.Budget budget = TrajectoryAppearance.budget(glows == null ? 0 : glows.size(),
            glows == null ? 0 : quadLimit, landing && hitSomething, glow);
        boolean ribbon = projectileRibbon(glows, budget.pathSegments(), points, camera);
        GlowTrail.append(glows, points, camera, budget.glowSprites(), glow, opacity, trailFade, this::pathColor);
        // No second line over the shader: it made joints look dark/doubled at grazing angles.
        // Retain the unmodified predicted polyline only as a low-budget fallback.
        if (!ribbon) fallbackPath(paths, points, camera);
        if (landing && hitSomething) {
            landingMarker(paths, glows, budget.absoluteLimit(), position, hitNormal, camera);
        }
    }

    /** The authoritative predicted points remain untouched; only the glow mesh is resampled linearly. */
    private boolean projectileRibbon(List<WorldGlowRenderer.Quad> effects, int segments,
                                     List<Vec3d> points, Vec3d camera) {
        if (effects == null || camera == null || !TrajectoryAppearance.enoughDetail(points.size(), segments)) return false;
        double[] distances = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            distances[i] = distances[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        }
        double length = distances[distances.length - 1];
        if (length < 1.0E-6D) return false;
        List<ConnectedRibbon.Node> nodes = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Vec3d point = points.get(i);
            double progress = distances[i] / length;
            TrajectoryAppearance.Sample style = TrajectoryAppearance.sample(camera.distanceTo(point), progress, opacity, trailFade);
            nodes.add(new ConnectedRibbon.Node(point, style.width(),
                VisualExpansionRenderer.argb((float) style.alpha(), pathColor(progress))));
        }
        return ConnectedRibbon.append(effects, nodes, camera, null, segments, false,
            WorldGlowRenderer.Texture.TRAJECTORY, (float) glow) > 0;
    }

    private int pathColor(double progress) {
        // Stable material: no crawling stripes or hue motion when lining up a throw.
        return VisualEffects.color(theme, customColor, 0, .10 + progress * .32);
    }

    private void fallbackPath(List<VisualExpansionRenderer.WorldLineStrip> paths, List<Vec3d> points, Vec3d camera) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += points.get(i - 1).distanceTo(points.get(i));
        if (total < 1e-6 || camera == null) return;
        double at = 0;
        for (int i = 1; i < points.size(); i++) {
            Vec3d a = points.get(i - 1), b = points.get(i);
            double length = a.distanceTo(b), progress = (at + length * .5) / total;
            double alpha = TrajectoryAppearance.sample(camera.distanceTo(a.lerp(b, .5)), progress, opacity, trailFade).alpha();
            VisualEffects.line(paths, List.of(a, b), pathColor(progress), alpha, 1.6F, 0);
            at += length;
        }
    }

    private void landingMarker(List<VisualExpansionRenderer.WorldLineStrip> paths,
                               List<WorldGlowRenderer.Quad> glows, int limit,
                               Vec3d position, Vec3d normal, Vec3d camera) {
        if (camera == null) return;
        if (normal.lengthSquared() < 1e-8) normal = new Vec3d(0, 1, 0);
        Vec3d reference = Math.abs(normal.y) > 0.9D ? VisualEffects.X : new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d axisU = reference.subtract(normal.multiply(reference.dotProduct(normal))).normalize();
        Vec3d axisV = normal.crossProduct(axisU).normalize();
        Vec3d center = position.add(normal.multiply(0.018D));
        int color = pathColor(1);
        double alpha = TrajectoryAppearance.markerAlpha(camera.distanceTo(center), opacity);
        if (alpha < .002) return;
        if (glows != null && glows.size() < limit) {
            impactGlow(glows, WorldGlowRenderer.Texture.TRAJECTORY_IMPACT, center, axisU, axisV, .34, color, alpha);
            if (glow > .01 && glows.size() < limit)
                impactGlow(glows, WorldGlowRenderer.Texture.SOFT, center, axisU, axisV, .47, color, alpha * glow * .16);
        } else {
            // Also works when the shared quad budget is exhausted, not just when the list is absent.
            VisualEffects.line(paths, VisualEffects.arc(center, axisU, axisV, .34 * .64,
                0, VisualEffects.TAU, 48), color, alpha * .82, 1.6F, 0);
            VisualEffects.line(paths, VisualEffects.arc(center, axisU, axisV, .022,
                0, VisualEffects.TAU, 12), VisualEffects.mix(color, 0xFFFFFF, .65), alpha, 2F, 0);
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
