package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.mixin.module.KeyBindingAccessor;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.combat.CombatLoadout;
import dev.c4lam.celeste.module.impl.combat.Targeting;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.rotation.MoveFixMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.item.Items;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.function.Predicate;

/**
 * True Trigger Bot that automatically attacks when an entity is in crosshair
 * and conditions are met, without requiring the player to hold the attack button.
 */
public final class TBOTFeature implements ModuleFeature {
    private static final int PRIORITY = 100;
    
    private long lastAttackTime = 0;

    @Override
    public String name() {
        return "TBOT";
    }

    @Override
    public void onEnable(ModuleContext context) {
        // No special enable logic needed
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        // Check activation key (-1 means always active)
        int activationKey = context.keybind("Activation Key");
        boolean shouldProcess = true;
        if (activationKey != -1) {
            Window window = client.getWindow();
            if (window != null) {
                shouldProcess = GLFW.glfwGetKey(window.getHandle(), activationKey) == GLFW.GLFW_PRESS;
            } else {
                shouldProcess = false;
            }
        }
        
        if (!shouldProcess) {
            return;
        }

        // Get cooldown setting (with random variance if enabled)
        double baseCooldown = context.slider("Cooldown", 0.1);
        double cooldown = baseCooldown;
        if (context.toggle("Random Delay", false)) {
            double variance = context.slider("Delay Variance", 0.05);
            if (variance > 0) {
                cooldown += (Math.random() * 2 - 1) * variance;
                cooldown = Math.max(0.0, cooldown); // Ensure non-negative
            }
        }
        
        // Prevent spamming
        long now = System.nanoTime();
        if (now - lastAttackTime < (long)(cooldown * 1_000_000_000)) {
            return;
        }

        // Find target in crosshair
        LivingEntity target = findTargetInCrosshair(client, context);
        if (target == null) {
            return;
        }

        // Perform attack
        performAttack(client, target, context);
        
        // Update last attack time
        lastAttackTime = now;
    }

    @Override
    public void onActionTick(ModuleContext context) {
        // TBOT doesn't need action tick logic - everything happens in onStartTick
    }

    @Override
    public void onDisable(ModuleContext context) {
        // No special disable logic needed
    }

    private LivingEntity findTargetInCrosshair(MinecraftClient client, ModuleContext context) {
        if (!(client.crosshairTarget instanceof EntityHitResult hit)) {
            return null;
        }
        
        Entity entity = hit.getEntity();
        if (!(entity instanceof LivingEntity living)) {
            return null;
        }
        
        // Validate target based on settings
        if (!isValidTarget(living, client, context)) {
            return null;
        }
        
        // Check range
        double range = context.slider("Range", 4.5);
        if (client.player.squaredDistanceTo(living) > range * range) {
            return null;
        }
        
        // Check FOV
        double fov = context.slider("FOV", 60.0);
        if (!Targeting.inFov(client.player, living, fov)) {
            return null;
        }
        
        return living;
    }

    private boolean isValidTarget(LivingEntity target, MinecraftClient client, ModuleContext context) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        
        // Check if it's the player themselves
        if (target == client.player) {
            return false;
        }
        
        // Check friendlies
        if (context.toggle("Ignore Friendlies", true) && 
            target instanceof PlayerEntity player && 
            FriendManager.getInstance().isFriend(player)) {
            return false;
        }
        
        // Check invisibles
        if (context.toggle("Ignore Invisible", true) && 
            target.isInvisible()) {
            return false;
        }
        
        // Check creatives
        if (context.toggle("Ignore Creatives", true) && 
            target instanceof PlayerEntity player && 
            player.isCreative()) {
            return false;
        }
        
        // Check spectators
        if (context.toggle("Ignore Spectators", true) && 
            target instanceof PlayerEntity player && 
            player.isSpectator()) {
            return false;
        }
        
        return true;
    }

    private void performAttack(MinecraftClient client, LivingEntity target, ModuleContext context) {
        // Request smooth rotation to target for better accuracy
        double fov = context.slider("FOV", 60.0);
        RotationManager.getInstance().submit(RotationTask.builder(
                this, 
                Rotation.lookingAt(client.player, Targeting.aimPoint(target))
            )
            .priority(PRIORITY)
            .lifetimeTicks(1)
            .smoothing(10.0F, 10.0F)  // Smooth but responsive
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());
        
        // Perform the attack
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            if (actionClient.player == null || 
                actionClient.world == null || 
                actionClient.interactionManager == null) {
                return;
            }
            
            // Re-validate target is still valid
            LivingEntity currentTarget = findTargetInCrosshair(actionClient, 
                    new ModuleContext(actionClient, context.module()));
            if (currentTarget == null || !currentTarget.isAlive()) {
                return;
            }
            
            // Check if we can attack with current weapon
            int slot = CombatLoadout.attackSlot(actionClient, currentTarget, CombatLoadout.Weapon.CURRENT);
            if (slot < 0) {
                return;
            }
            
            // Check attack cooldown
            double cooldown = context.slider("Cooldown", 0.1);
            if (!Targeting.attackReady(actionClient, cooldown)) {
                return;
            }
            
            // Perform silent swap if enabled
            boolean swapped = false;
            if (context.toggle("Silent Swap", true)) {
                swapped = CombatLoadout.runWithSlot(
                        this, 
                        actionClient, 
                        slot, 
                        PRIORITY, 
                        true, 
                        () -> {
                            actionClient.interactionManager.attackEntity(
                                    actionClient.player, 
                                    currentTarget
                            );
                            actionClient.player.swingHand(Hand.MAIN_HAND);
                        }
                );
            } else {
                // Normal attack without silent swap
                actionClient.interactionManager.attackEntity(
                        actionClient.player, 
                        currentTarget
                );
                actionClient.player.swingHand(Hand.MAIN_HAND);
            }
            
            // Mark swap if we used silent swap
            if (swapped) {
                CombatLoadout.markSwap(actionClient, swapped);
            }
        });
    }
}