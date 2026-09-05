package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

/** Throws a pearl straight up, follows it with a wind charge, then restores cleanly. */
public final class CatchFeature implements ModuleFeature {
    private static final int PRIORITY = 230;
    private static final float UP_PITCH = -90.0F;
    private static final long STAGE_TIMEOUT_NANOS = 1_500_000_000L;

    private enum Stage { IDLE, AIM_PEARL, WAIT_CHARGE, AIM_CHARGE, RESTORE }

    private Stage stage = Stage.IDLE;
    private boolean actionDown;
    private boolean aimSettledLastTick;
    private long stageStartedNanos;
    private long readyAtNanos;
    private ClientPlayerEntity visiblePlayer;
    private int originalVisibleSlot = -1;
    private int moduleVisibleSlot = -1;

    @Override
    public String name() {
        return "Catch";
    }

    @Override
    public void onEnable(ModuleContext context) {
        actionDown = actionKeyDown(context);
        resetState(false, context.client());
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        boolean down = actionKeyDown(context);
        boolean pressed = down && !actionDown;
        actionDown = down;

        if (!playable(client) || client.currentScreen != null || client.player.isDead()) {
            abort(client, true);
            return;
        }

        if (stage == Stage.IDLE && pressed) {
            int pearl = CombatLoadout.findItem(client, Items.ENDER_PEARL);
            int charge = CombatLoadout.findItem(client, Items.WIND_CHARGE);
            if (pearl >= 0 && charge >= 0 && !coolingDown(client, pearl) && !coolingDown(client, charge)) {
                visiblePlayer = client.player;
                originalVisibleSlot = client.player.getInventory().getSelectedSlot();
                moduleVisibleSlot = -1;
                enter(Stage.AIM_PEARL);
            }
        }

        if (stage == Stage.IDLE) {
            return;
        }
        long now = System.nanoTime();
        if (stage != Stage.RESTORE && now - stageStartedNanos > STAGE_TIMEOUT_NANOS) {
            abort(client, true);
            return;
        }
        if (visiblePlayer != client.player) {
            resetState(false, client);
            return;
        }
        if (moduleVisibleSlot >= 0
            && client.player.getInventory().getSelectedSlot() != moduleVisibleSlot) {
            // A real scroll/key selection always wins over the macro.
            abort(client, false);
            return;
        }

        if (stage == Stage.WAIT_CHARGE && now >= readyAtNanos) {
            if (CombatLoadout.findItem(client, Items.WIND_CHARGE) < 0) {
                beginRestore(context);
            } else {
                enter(Stage.AIM_CHARGE);
                // The upward look remained published during the wait.
                aimSettledLastTick = true;
            }
        } else if (stage == Stage.RESTORE && now >= readyAtNanos) {
            restoreVisible(context);
            resetState(false, client);
            return;
        }

        submitUpwardRotation(client);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        if (stage != Stage.AIM_PEARL && stage != Stage.AIM_CHARGE) {
            return;
        }
        MinecraftClient client = context.client();
        RotationManager rotations = RotationManager.getInstance();
        if (!playable(client) || !rotations.isOwnedBy(this)) {
            return;
        }
        if (Math.abs(rotations.serverRotation().pitch() - UP_PITCH) > 0.01F) {
            aimSettledLastTick = false;
            return;
        }
        // Publish the completed look for one normal movement tick before the
        // first throw. The charge reuses that already-published look.
        if (!aimSettledLastTick) {
            aimSettledLastTick = true;
            return;
        }

        boolean pearl = stage == Stage.AIM_PEARL;
        int slot = CombatLoadout.findItem(client, pearl ? Items.ENDER_PEARL : Items.WIND_CHARGE);
        if (slot < 0 || coolingDown(client, slot) || !useSlot(context, slot)) {
            return;
        }

        if (pearl) {
            readyAtNanos = System.nanoTime() + randomMillis(context, "Charge delay", 200, 300) * 1_000_000L;
            enter(Stage.WAIT_CHARGE);
        } else {
            beginRestore(context);
        }
    }

    @Override
    public void onDisable(ModuleContext context) {
        abort(context.client(), true);
        actionDown = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetState(false, context.client());
        actionDown = false;
    }

    private void submitUpwardRotation(MinecraftClient client) {
        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(client.player.getYaw(), UP_PITCH))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180.0F, 45.0F)
            .sensitivityQuantized(false).moveFix(MoveFixMode.OFF).build());
    }

    private boolean useSlot(ModuleContext context, int slot) {
        MinecraftClient client = context.client();
        boolean silent = context.toggle("Silent swap", true);
        Runnable use = () -> useHeld(client);
        if (silent) {
            return SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY, true, use);
        }
        if (moduleVisibleSlot < 0) {
            moduleVisibleSlot = client.player.getInventory().getSelectedSlot();
        }
        if (!SilentSlotManager.getInstance().selectVisibleSlot(client, slot)) {
            return false;
        }
        moduleVisibleSlot = slot;
        use.run();
        return true;
    }

    private static void useHeld(MinecraftClient client) {
        if (!playable(client)) {
            return;
        }
        ActionResult result = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            client.player.swingHand(Hand.MAIN_HAND);
            client.gameRenderer.firstPersonRenderer.resetEquipProgress(Hand.MAIN_HAND);
        }
    }

    private void beginRestore(ModuleContext context) {
        readyAtNanos = System.nanoTime() + randomMillis(context, "Return delay", 50, 100) * 1_000_000L;
        enter(Stage.RESTORE);
    }

    private void restoreVisible(ModuleContext context) {
        MinecraftClient client = context.client();
        if (!context.toggle("Return slot", true) || visiblePlayer == null || client.player != visiblePlayer
            || originalVisibleSlot < 0 || originalVisibleSlot > 8 || moduleVisibleSlot < 0
            || client.player.getInventory().getSelectedSlot() != moduleVisibleSlot) {
            return;
        }
        SilentSlotManager.getInstance().selectVisibleSlot(client, originalVisibleSlot);
    }

    private void abort(MinecraftClient client, boolean restoreVisible) {
        if (restoreVisible && client != null && client.player == visiblePlayer
            && originalVisibleSlot >= 0 && originalVisibleSlot <= 8 && moduleVisibleSlot >= 0
            && client.player.getInventory().getSelectedSlot() == moduleVisibleSlot) {
            if (!SilentSlotManager.getInstance().selectVisibleSlot(client, originalVisibleSlot)) {
                client.player.getInventory().setSelectedSlot(originalVisibleSlot);
            }
        }
        resetState(true, client);
    }

    private void resetState(boolean releaseSlot, MinecraftClient client) {
        if (releaseSlot && client != null && client.isOnThread()) {
            SilentSlotManager.getInstance().release(this);
        }
        GameplayFoundation.releaseOwner(this);
        stage = Stage.IDLE;
        aimSettledLastTick = false;
        stageStartedNanos = 0L;
        readyAtNanos = 0L;
        visiblePlayer = null;
        originalVisibleSlot = -1;
        moduleVisibleSlot = -1;
    }

    private void enter(Stage next) {
        stage = next;
        stageStartedNanos = System.nanoTime();
    }

    private static long randomMillis(ModuleContext context, String label, double fallbackLow, double fallbackHigh) {
        double low = Math.max(0.0D, context.rangeLow(label, fallbackLow));
        double high = Math.max(low, context.rangeHigh(label, fallbackHigh));
        if (high <= low) {
            return Math.round(low);
        }
        return Math.round(ThreadLocalRandom.current().nextDouble(low, Math.nextUp(high)));
    }

    private static boolean coolingDown(MinecraftClient client, int slot) {
        ItemStack stack = client.player.getInventory().getStack(slot);
        return stack.isEmpty() || client.player.getItemCooldownManager().isCoolingDown(stack);
    }

    private static boolean playable(MinecraftClient client) {
        return client != null && client.player != null && client.world != null
            && client.interactionManager != null && client.getNetworkHandler() != null;
    }

    private static boolean actionKeyDown(ModuleContext context) {
        MinecraftClient client = context.client();
        int key = context.keybind("Action");
        return key != GLFW.GLFW_KEY_UNKNOWN && client != null && client.getWindow() != null
            && InputUtil.isKeyPressed(client.getWindow(), key);
    }
}
