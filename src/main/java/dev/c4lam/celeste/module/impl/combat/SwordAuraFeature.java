package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.combat.VanillaCombatReach;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.module.impl.combat.Targeting;
import dev.c4lam.celeste.module.impl.combat.CombatLoadout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

/**
 * Sword Aura feature with proper rotation handling to avoid flickering and anti-cheat flags.
 */
public final class SwordAuraFeature extends CombatFeature {
    private static final int PRIORITY = 50;
    private LivingEntity target;
    private long lastAttackTime;
    private boolean isRotatingToTarget;

    @Override
    public String name() {
        return "Sword Aura";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        double range = context.slider("Range", 3.1D);
        double fov = context.slider("FOV", 60.0D);
        String mode = context.mode("Mode", "Locked");
        boolean requireCritical = context.toggle("Only criticals", false);

        target = Targeting.selectPlayer(client, FriendManager.getInstance(), range, fov, mode);
        if (target == null
                || !Targeting.attackReady(client, 0.9D)
                || CombatLoadout.attackSlot(client, target, CombatLoadout.Weapon.SWORD) < 0
                || (requireCritical && !Targeting.inCriticalWindow(client.player))) {
            target = null;
            releaseIntent();
            isRotatingToTarget = false;
            return;
        }

        // Every action mode must submit before the shared rotation-resolution
        // phase. Flick used to submit from onActionTick, one phase too late,
        // which made its first attack stale and its next attack use old aim.
        requestRotationToTarget(target, PRIORITY);
        isRotatingToTarget = true;
    }

    @Override
    public void onActionTick(ModuleContext context) {
        LivingEntity selected = target;
        if (selected == null) {
            return;
        }
        MinecraftClient client = context.client();
        String mode = context.mode("Mode", "Locked");
        boolean requireCritical = context.toggle("Only criticals", false);
        double range = context.slider("Range", 3.1D);

        // The request was resolved during START before this action phase. The
        // scheduler still revalidates manager ownership and final alignment.
        if (isProperlyAligned(selected, context) || "Flick".equals(mode)) {
            submitCappedAuraAttack(
                    PRIORITY,
                    selected,
                    range,
                    0.9D,
                    requireCritical,
                    CombatLoadout.Weapon.SWORD,
                    c -> c.player != null
                            && Targeting.inFov(c.player, selected, context.slider("FOV", 60.0D))
            );
            lastAttackTime = System.currentTimeMillis();
        }
    }

    private void requestRotationToTarget(LivingEntity target, int priority) {
        if (target == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        // Calculate rotation to look at target's eyes for better accuracy
        double targetX = target.getX();
        double targetY = target.getY() + target.getEyeHeight(target.getPose());
        double targetZ = target.getZ();

        double dx = targetX - client.player.getX();
        double dy = targetY - (client.player.getY() + client.player.getEyeHeight(client.player.getPose()));
        double dz = targetZ - client.player.getZ();

        float yaw = (float) MathHelper.atan2(dz, dx) * (180.0F / (float) Math.PI) - 90.0F;
        float pitch = (float) (-MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0F / (float) Math.PI));

        Rotation targetRotation = new Rotation(yaw, pitch);

        // Submit rotation task with appropriate settings for smooth aiming
        RotationManager.getInstance().submit(RotationTask.builder(this, targetRotation)
                .priority(priority)
                .lifetimeTicks(1) // Short lifetime so it doesn't interfere with other actions
                .smoothing(180.0F, 180.0F) // Smooth rotation
                .sensitivityQuantized(true)
                .moveFix(dev.c4lam.celeste.rotation.MoveFixMode.STRICT)
                .build());
    }

    private boolean isProperlyAligned(LivingEntity target, ModuleContext context) {
        if (target == null) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false;
        }

        double fov = context.slider("FOV", 60.0D);
        return Targeting.inFov(client.player, target, fov);
    }

    @Override
    protected void clearTarget() {
        target = null;
        isRotatingToTarget = false;
    }
}
