package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.Locale;

/** Passive crosshair-only crystal estimate; it never changes world or input state. */
public final class DamageRenderFeature implements ModuleFeature {
    private final FriendManager friends = FriendManager.getInstance();
    private Estimate estimate;

    @Override
    public String name() {
        return "Damage Render";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        Vec3d explosion = explosionAtCrosshair(client);
        if (explosion == null || client.player == null || !client.player.isAlive()
            || (client.currentScreen != null && !(client.currentScreen instanceof SilentInventoryScreen))) {
            estimate = null;
            return;
        }

        PlayerEntity enemy = ExplosionSafety.bestEnemy(client, friends, explosion, 12.0D);
        if (enemy == null) {
            estimate = null;
            return;
        }
        estimate = new Estimate(
            ExplosionSafety.damage(enemy, explosion, 6.0D, true),
            ExplosionSafety.damage(client.player, explosion, 6.0D, true),
            enemy.getName().getString(),
            context.mode("Style", "Compact").equalsIgnoreCase("Detailed"),
            enemy.getHealth() + enemy.getAbsorptionAmount(),
            client.player.getHealth() + client.player.getAbsorptionAmount(),
            context.toggle("Damage bars", true),
            (float) Math.max(0.2D, Math.min(1D, context.slider("Opacity", 0.9D)))
        );
    }

    @Override
    public void onDisable(ModuleContext context) {
        estimate = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        estimate = null;
    }

    Estimate estimate() {
        return estimate;
    }

    static void drawEstimate(MatrixStack matrices, float centerX, float y, Estimate estimate, float visibility,
                             float targetFill, float selfFill) {
        float opacity = visibility * estimate.opacity();
        boolean detailed = estimate.detailed();
        float width = detailed ? 158F : 138F;
        float height = detailed ? 56F : 43F;
        float x = centerX - width * 0.5F;
        float shownY = y + (1F - visibility) * 5F;
        boolean lethal = estimate.targetHealth() > 0D && estimate.targetDamage() >= estimate.targetHealth();
        boolean selfLethal = estimate.selfHealth() > 0D && estimate.selfDamage() >= estimate.selfHealth();
        float selfFraction = fraction(estimate.selfDamage(), estimate.selfHealth());
        int targetColor = lethal ? 0x9DECC6 : 0xDBEFF5;
        int selfColor = selfLethal ? 0xFF7886 : selfFraction >= 0.5F ? 0xF2C67E : 0x8DB5CB;
        matrices.push();
        matrices.translate(centerX, shownY + height * 0.5F, 0F);
        float scale = 0.97F + visibility * 0.03F;
        matrices.scale(scale, scale, 1F);
        matrices.translate(-centerX, -(shownY + height * 0.5F), 0F);
        RenderUtil.RECT.draw(matrices, x, shownY, width, height, 8F, color(0x181C23, opacity * 0.9F));
        float contentY = shownY;
        if (detailed) {
            String target = fit(estimate.targetName(), width - 71F, 5.8F);
            Fonts.semibold().draw(matrices, target, x + 10F, shownY + 7F, 5.8F, color(0xDCE6EC, opacity));
            String status = selfLethal ? "SELF LETHAL" : lethal ? "LETHAL" : "ESTIMATE";
            Fonts.regular().draw(matrices, status,
                x + width - 10F - Fonts.regular().width(status, 4.5F), shownY + 8F, 4.5F,
                color(selfLethal ? selfColor : lethal ? targetColor : 0x8C9CA8, opacity));
            contentY += 13F;
        }
        float left = x + 10F;
        float right = centerX + 8F;
        float column = width * 0.5F - 20F;
        metric(matrices, left, contentY, column, "TARGET", estimate.targetDamage(), targetFill,
            targetColor, opacity, estimate.bars());
        metric(matrices, right, contentY, column, "SELF", estimate.selfDamage(), selfFill,
            selfColor, opacity, estimate.bars());
        matrices.pop();
    }

    private static void metric(MatrixStack matrices, float x, float y, float width, String label,
                               double damage, float fill, int rgb, float alpha, boolean bars) {
        Fonts.regular().draw(matrices, label, x, y + 7F, 4.7F, color(0x91A1AE, alpha));
        String value = String.format(Locale.ROOT, "%.1f", damage);
        Fonts.semibold().draw(matrices, value, x, y + 16F, 10F, color(rgb, alpha));
        float unitX = x + Fonts.semibold().width(value, 10F) + 3F;
        Fonts.regular().draw(matrices, "hp", unitX, y + 21F, 4.5F, color(0x81939F, alpha));
        if (bars) {
            RenderUtil.RECT.draw(matrices, x, y + 35F, width, 2F, 1F, color(rgb, alpha * 0.12F));
            RenderUtil.RECT.draw(matrices, x, y + 35F, width * Math.max(0F, Math.min(1F, fill)), 2F, 1F,
                color(rgb, alpha * 0.84F));
        }
    }

    static float fraction(double damage, double health) {
        return (float) Math.max(0D, Math.min(1D, damage / Math.max(0.1D, health)));
    }

    private static Color color(int rgb, float opacity) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255,
            Math.max(0, Math.min(255, Math.round(opacity * 255F))));
    }

    private static String fit(String value, float width, float size) {
        if (Fonts.semibold().width(value, size) <= width) return value;
        String trimmed = value;
        while (!trimmed.isEmpty() && Fonts.semibold().width(trimmed + "...", size) > width) {
            trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(trimmed.length(), -1));
        }
        return trimmed + "...";
    }

    private static Vec3d explosionAtCrosshair(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return null;
        }
        if (client.crosshairTarget instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof EndCrystalEntity crystal
            && crystal.isAlive() && !crystal.isRemoved()) {
            return crystal.getEntityPos();
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK || hit.getSide() != Direction.UP) {
            return null;
        }
        BlockPos base = hit.getBlockPos();
        BlockState state = client.world.getBlockState(base);
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) {
            return null;
        }
        BlockPos crystalPos = base.up();
        if (!client.world.isAir(crystalPos)
            || !client.world.getOtherEntities(null,
                new Box(crystalPos).stretch(0.0D, 1.0D, 0.0D), Entity::isAlive).isEmpty()) {
            return null;
        }
        return Vec3d.ofBottomCenter(crystalPos);
    }

    record Estimate(double targetDamage, double selfDamage, String targetName, boolean detailed,
                    double targetHealth, double selfHealth, boolean bars, float opacity) {
    }
}
