package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
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
        if (explosion == null || client.player == null || client.currentScreen != null) {
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
            context.mode("Style", "Compact").equalsIgnoreCase("Detailed")
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

    record Estimate(double targetDamage, double selfDamage, String targetName, boolean detailed) {
    }
}
