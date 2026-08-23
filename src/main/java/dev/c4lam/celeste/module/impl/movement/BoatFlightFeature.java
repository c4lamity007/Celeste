package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Faithful Yarn/Fabric adaptation of the supplied BoatTP controller.
 *
 * <p>Vulcan arms immediately after the real boat exit, then uses either
 * camera-relative 2.5 block manual steps or a resolved look/coordinate
 * destination. Grim is kept intentionally inert as the requested placeholder.</p>
 */
public final class BoatFlightFeature implements ModuleFeature {
    private static final double MAX_DISTANCE = 24.0D;
    private static final double GRIM_SAFE_EXIT_DISTANCE = 2.0D;
    private static final long TELEPORT_ACCEPTANCE_WINDOW_NANOS = 5_000_000_000L;
    private static final long RECENT_TRANSACTION_WINDOW_NANOS = 250_000_000L;
    private static final double SLOW_REACH_DISTANCE = 0.10D;
    private static final double CONSECUTIVE_STEP_DISTANCE = 2.5D;
    private static final int SLOW_MOVEMENT_TIMEOUT_TICKS = 240;

    private static final AtomicBoolean SEND_BYPASS = new AtomicBoolean(false);
    private static volatile BoatFlightFeature instance;

    private MinecraftClient client;
    private ModuleContext context;
    private boolean vulcanMode;
    private boolean wasInBoat;
    private volatile boolean pendingBlink;
    private volatile Vec3d pendingTarget;
    private final ConcurrentLinkedQueue<BlinkEntry> blinkQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean movementMarkerQueued;
    private volatile boolean movementSentThisTick;
    private volatile int flushDelayTicks;
    private volatile boolean holdTickEnd;
    private volatile boolean teleportAcceptancePending;
    private volatile long teleportAcceptanceDeadline;
    private volatile boolean awaitingServerTeleport;
    private volatile boolean serverTeleportApplied;
    private volatile boolean serverTeleportMoveSent;
    private volatile boolean serverTeleportPongSent;
    private volatile boolean suppressNextTickEnd;
    private volatile boolean serverCorrectionPending;
    private volatile boolean waitingForExitTransaction;
    private volatile boolean exitMovementSent;
    private volatile boolean exitPingSeen;
    private volatile int exitPingParameter;
    private volatile boolean exitPongSent;
    private volatile boolean targetReady;
    private volatile int lastGrimPingParameter;
    private volatile long lastGrimPingNanos;
    private volatile int lastGrimPongParameter;
    private volatile long lastGrimPongNanos;
    private volatile Vec3d slowTarget;
    private volatile boolean slowMovementActive;
    private volatile int slowMovementTicks;
    private volatile boolean manualMovementActive;
    private volatile Vec3d manualOrigin;
    private volatile boolean guidedRotationActive;
    private volatile float guidedSavedYaw;
    private volatile float guidedSavedPitch;
    private volatile boolean guidedSavedSprinting;

    private record BlinkEntry(Packet<?> packet, boolean movementMarker) {
        private static BlinkEntry movement() {
            return new BlinkEntry(null, true);
        }

        private static BlinkEntry packet(Packet<?> packet) {
            return new BlinkEntry(packet, false);
        }
    }

    @Override
    public String name() {
        return "Boat Flight";
    }

    @Override
    public void onEnable(ModuleContext context) {
        this.client = context.client();
        this.context = context;
        this.vulcanMode = isVulcan(context);
        resetAll();
        instance = this;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        this.client = context.client();
        this.context = context;
        this.vulcanMode = isVulcan(context);
        if (!vulcanMode) {
            resetAll();
            return;
        }

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !player.isAlive() || player.isSpectator()) {
            resetAll();
            return;
        }
        if (manualMovementActive && client.options.sprintKey.isPressed()) {
            stopAndDisable();
            return;
        }

        movementSentThisTick = false;
        boolean inBoat = player.hasVehicle() && player.getVehicle() instanceof AbstractBoatEntity;
        if (waitingForExitTransaction && !inBoat && targetReady && exitPongSent
            && (manualControl() || pendingTarget != null) && !serverCorrectionPending) {
            waitingForExitTransaction = false;
            targetReady = false;
            if (manualControl()) {
                beginManualMovement();
            } else {
                beginSlowMovement(pendingTarget);
            }
            movementSentThisTick = false;
        }

        if (wasInBoat && !inBoat && !pendingBlink && !awaitingServerTeleport
            && !waitingForExitTransaction && !slowMovementActive && !manualMovementActive) {
            if (manualControl()) {
                beginManualMovement();
            } else {
                Vec3d target = resolveTarget(player);
                if (target != null) {
                    beginSlowMovement(target);
                } else {
                    sendMessage("§cNo valid Boat Flight destination was found.");
                }
            }
        }
        wasInBoat = inBoat;
    }

    @Override
    public void onEndTick(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        if (!owns(player) || context.client().world == null) {
            return;
        }
        if ((slowMovementActive || manualMovementActive) && player.hasVehicle()) {
            cancelSlowMovement();
        }
        if (pendingBlink && !player.hasVehicle()) {
            if (flushDelayTicks > 0) {
                flushDelayTicks--;
                holdTickEnd = true;
            } else {
                flushMovementPhase(player, false);
            }
        } else if (pendingBlink) {
            cancelBlink();
        }
        movementSentThisTick = false;
    }

    @Override
    public void onDisable(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        if (player != null) {
            restoreGuidedMovement(player);
        }
        resetAll();
        if (instance == this) {
            instance = null;
        }
        this.context = null;
        this.client = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        if (player != null) {
            restoreGuidedMovement(player);
        }
        resetAll();
    }

    /** Called from ClientPlayerEntity.dismountVehicle at HEAD. */
    public static void onDismountStart(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        if (!featureOwns(feature, player) || !(player.getVehicle() instanceof AbstractBoatEntity)) {
            return;
        }

        // Vanilla can retain the passenger relationship until the matching
        // server passenger update. Keep this marker so onStartTick activates
        // immediately on that confirmed dismount if the RETURN hook still
        // observes hasVehicle().
        feature.wasInBoat = true;
        feature.pendingTarget = feature.manualControl() ? null : feature.resolveTarget(player);
        if (!feature.manualControl() && feature.pendingTarget == null) {
            feature.cancelBlink();
            feature.sendMessage("§cNo valid Boat Flight destination was found.");
            return;
        }

        // Vulcan is immediate: the original transaction observer remains
        // available for packet ordering, but it must not delay flight startup.
        feature.pendingBlink = false;
        feature.waitingForExitTransaction = false;
        feature.exitMovementSent = false;
        feature.exitPingSeen = false;
        feature.exitPingParameter = Integer.MIN_VALUE;
        feature.exitPongSent = false;
        feature.targetReady = false;
        feature.serverCorrectionPending = false;
        feature.blinkQueue.clear();
    }

    /** Called from ClientPlayerEntity.dismountVehicle at RETURN. */
    public static void onDismountComplete(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        if (!featureOwns(feature, player) || player.hasVehicle()) {
            return;
        }
        if (feature.manualControl()) {
            feature.beginManualMovement();
        } else if (feature.pendingTarget != null) {
            feature.beginSlowMovement(feature.pendingTarget);
        }
    }

    /** Captures the camera state before the move-fix input transform. */
    public static void captureGuidedMovement(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        if (!featureOwns(feature, player)
            || !feature.slowMovementActive
            || player.hasVehicle()
            || feature.slowTarget == null
            || feature.guidedRotationActive) {
            return;
        }
        feature.guidedSavedYaw = player.getYaw();
        feature.guidedSavedPitch = player.getPitch();
        feature.guidedSavedSprinting = player.isSprinting();
        feature.guidedRotationActive = true;
    }

    /** Advances the original manual/target state immediately before player physics. */
    public static void prepareGuidedMovement(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        if (!featureOwns(feature, player) || player.hasVehicle()) {
            return;
        }
        if (feature.manualMovementActive) {
            feature.prepareManualStep(player);
            return;
        }
        if (!feature.slowMovementActive || feature.slowTarget == null) {
            return;
        }

        Vec3d target = feature.slowTarget;
        Vec3d delta = target.subtract(player.getEntityPos());
        double remaining = delta.length();
        if (!Double.isFinite(remaining) || remaining <= SLOW_REACH_DISTANCE) {
            feature.finishSlowMovement(player);
            return;
        }
        feature.slowMovementTicks++;
        if (feature.slowMovementTicks > SLOW_MOVEMENT_TIMEOUT_TICKS) {
            feature.finishSlowMovement(player);
            return;
        }
        if (!feature.guidedRotationActive) {
            feature.guidedSavedYaw = player.getYaw();
            feature.guidedSavedPitch = player.getPitch();
            feature.guidedSavedSprinting = player.isSprinting();
            feature.guidedRotationActive = true;
        }
        Vec3d step = remaining <= CONSECUTIVE_STEP_DISTANCE
            ? target
            : player.getEntityPos().add(delta.multiply(CONSECUTIVE_STEP_DISTANCE / remaining));
        feature.applyLocalTeleport(player, step);
        player.setSprinting(false);
    }

    /** The KeyboardInput mixin calls this so vanilla movement cannot add a second step. */
    public static boolean shouldZeroVanillaInput(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        return featureOwns(feature, player)
            && !player.hasVehicle()
            && (feature.manualMovementActive || (feature.slowMovementActive && feature.slowTarget != null));
    }

    /** Incoming common/play packet observer. Vanilla handling is never cancelled. */
    public static void onServerPacket(Packet<?> packet) {
        BoatFlightFeature feature = instance;
        if (feature == null || !feature.enabled()) {
            return;
        }
        if (packet instanceof CommonPingS2CPacket ping) {
            int parameter = ping.getParameter();
            if (isGrimTransactionParameter(parameter)) {
                feature.lastGrimPingParameter = parameter;
                feature.lastGrimPingNanos = System.nanoTime();
            }
            if (feature.waitingForExitTransaction && isGrimTransactionParameter(parameter)) {
                feature.exitPingSeen = true;
                feature.exitPingParameter = parameter;
            }
            return;
        }
        if (packet instanceof PlayerPositionLookS2CPacket) {
            if (feature.pendingBlink || feature.pendingTarget != null) {
                feature.cancelBlink();
            }
            // The reference cancels its entire staged state here because its
            // transaction mode treats a correction as terminal. Vulcan manual
            // flight is continuous: accept the server's new base position and
            // keep the held-key 2.5-block loop armed for the following tick.
            if (feature.manualMovementActive) {
                feature.slowMovementActive = false;
                feature.slowTarget = null;
                feature.slowMovementTicks = 0;
            } else {
                feature.cancelSlowMovement();
            }
            feature.waitingForExitTransaction = false;
            feature.targetReady = false;
            feature.serverCorrectionPending = true;
            feature.teleportAcceptancePending = true;
            feature.teleportAcceptanceDeadline = 0L;
        }
    }

    /** Called after vanilla has applied a PlayerPositionLook correction. */
    public static void onServerTeleportApplied() {
        BoatFlightFeature feature = instance;
        if (feature == null || !feature.enabled() || !feature.teleportAcceptancePending) {
            return;
        }
        feature.teleportAcceptancePending = false;
        feature.serverCorrectionPending = false;
        feature.serverTeleportApplied = false;
        feature.awaitingServerTeleport = false;
        feature.teleportAcceptanceDeadline = 0L;
    }

    /** Called by the ClientConnection send hook before a packet is written. */
    public static void observeOutgoingPacket(Packet<?> packet) {
        BoatFlightFeature feature = instance;
        if (feature == null || !feature.enabled() || SEND_BYPASS.get()) {
            return;
        }
        if (feature.waitingForExitTransaction && packet instanceof PlayerMoveC2SPacket move
            && move.changesPosition() && !feature.exitMovementSent) {
            feature.exitMovementSent = true;
            if (feature.exitPongSent) {
                feature.targetReady = true;
            }
        }
        if (feature.waitingForExitTransaction && feature.exitPingSeen
            && packet instanceof CommonPongC2SPacket pong && pong.getParameter() == feature.exitPingParameter) {
            feature.exitPongSent = true;
            feature.targetReady = true;
        }
        if (feature.awaitingServerTeleport && feature.serverTeleportApplied
            && packet instanceof PlayerMoveC2SPacket move && move.changesPosition()) {
            feature.serverTeleportMoveSent = true;
        }
        if (feature.awaitingServerTeleport && packet instanceof CommonPongC2SPacket) {
            feature.serverTeleportPongSent = true;
        }
        if (packet instanceof CommonPongC2SPacket pong && isGrimTransactionParameter(pong.getParameter())) {
            feature.lastGrimPongParameter = pong.getParameter();
            feature.lastGrimPongNanos = System.nanoTime();
        }
        if (packet instanceof PlayerMoveC2SPacket || packet instanceof VehicleMoveC2SPacket
            || packet instanceof PlayerInputC2SPacket) {
            feature.movementSentThisTick = true;
        }
    }

    /** Called by ClientConnection; true means the original one-tick blink owns this send. */
    public static boolean shouldBlinkHold(Packet<?> packet) {
        BoatFlightFeature feature = instance;
        if (feature == null || !feature.enabled() || SEND_BYPASS.get()) {
            return false;
        }
        if (feature.serverCorrectionPending) {
            return false;
        }
        if (packet instanceof ClientTickEndC2SPacket && feature.suppressNextTickEnd) {
            feature.suppressNextTickEnd = false;
            return true;
        }
        if (!feature.pendingBlink) {
            return false;
        }
        if (packet instanceof ClientTickEndC2SPacket) {
            return feature.holdTickEnd;
        }
        if (packet instanceof PlayerMoveC2SPacket || packet instanceof VehicleMoveC2SPacket) {
            if (!feature.movementMarkerQueued) {
                feature.movementMarkerQueued = true;
                feature.blinkQueue.add(BlinkEntry.movement());
            }
            return true;
        }
        if (isBlinkOrderSensitive(packet)) {
            feature.blinkQueue.add(BlinkEntry.packet(packet));
            return true;
        }
        return false;
    }

    /** Called at Minecraft's exact movement-packets-complete boundary. */
    public static void onMovementPacketsComplete(ClientPlayerEntity player) {
        BoatFlightFeature feature = instance;
        if (!featureOwns(feature, player) || feature.awaitingServerTeleport) {
            return;
        }
        if (feature.pendingBlink && feature.pendingTarget != null && !player.hasVehicle()) {
            if (feature.flushDelayTicks > 0) {
                feature.flushDelayTicks--;
                return;
            }
            feature.flushMovementPhase(player, true);
        }
    }

    /** Starts the original one-tick outbound hold path for integrations that arm it. */
    public static void armBlink(Vec3d target) {
        BoatFlightFeature feature = instance;
        ClientPlayerEntity player = feature == null || feature.client == null ? null : feature.client.player;
        if (!featureOwns(feature, player) || target == null) {
            return;
        }
        feature.pendingTarget = target;
        feature.pendingBlink = true;
        feature.flushDelayTicks = feature.movementSentThisTick ? 1 : 0;
        feature.holdTickEnd = !feature.movementSentThisTick;
        feature.movementMarkerQueued = false;
        feature.blinkQueue.clear();
    }

    private void prepareManualStep(ClientPlayerEntity player) {
        if (client.options.sprintKey.isPressed()) {
            stopAndDisable();
            return;
        }
        boolean forward = client.options.forwardKey.isPressed();
        boolean backward = client.options.backKey.isPressed();
        boolean left = client.options.leftKey.isPressed();
        boolean right = client.options.rightKey.isPressed();
        boolean up = client.options.jumpKey.isPressed();
        boolean down = client.options.sneakKey.isPressed();
        double forwardAxis = (forward ? 1.0D : 0.0D) - (backward ? 1.0D : 0.0D);
        double strafeAxis = (left ? 1.0D : 0.0D) - (right ? 1.0D : 0.0D);
        double verticalAxis = (up ? 1.0D : 0.0D) - (down ? 1.0D : 0.0D);
        if (forwardAxis == 0.0D && strafeAxis == 0.0D && verticalAxis == 0.0D) {
            player.setVelocity(Vec3d.ZERO);
            player.setSprinting(false);
            return;
        }
        // Manual flight must follow the live camera, not the yaw that happened
        // to be captured on its first input frame.
        double yaw = Math.toRadians(player.getYaw());
        Vec3d forwardVector = new Vec3d(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3d rightVector = new Vec3d(Math.cos(yaw), 0.0D, Math.sin(yaw));
        Vec3d direction = forwardVector.multiply(forwardAxis)
            .add(rightVector.multiply(strafeAxis))
            .add(0.0D, verticalAxis, 0.0D);
        if (direction.lengthSquared() < 1.0E-8D) {
            return;
        }
        double speed = Math.max(0.05D, slider("Speed", 0.50D));
        Vec3d origin = manualOrigin == null ? player.getEntityPos() : manualOrigin;
        double maximumDistance = Math.max(1.0D, slider("Max flight distance", 24.0D));
        Vec3d requested = player.getEntityPos().add(direction.normalize().multiply(speed));
        Vec3d offset = requested.subtract(origin);
        if (offset.lengthSquared() > maximumDistance * maximumDistance) {
            double currentDistance = player.getEntityPos().squaredDistanceTo(origin);
            if (currentDistance >= maximumDistance * maximumDistance - 1.0E-6D) {
                player.setVelocity(Vec3d.ZERO);
                return;
            }
            requested = origin.add(offset.normalize().multiply(maximumDistance));
        }
        applyLocalTeleport(player, requested);
        player.setSprinting(false);
    }

    private void beginManualMovement() {
        pendingBlink = false;
        pendingTarget = null;
        movementMarkerQueued = false;
        flushDelayTicks = 0;
        holdTickEnd = false;
        slowTarget = null;
        slowMovementActive = false;
        slowMovementTicks = 0;
        manualOrigin = client == null || client.player == null ? null : client.player.getEntityPos();
        manualMovementActive = true;
    }

    private void beginSlowMovement(Vec3d target) {
        pendingBlink = false;
        pendingTarget = null;
        movementMarkerQueued = false;
        flushDelayTicks = 0;
        holdTickEnd = false;
        slowTarget = target;
        slowMovementActive = target != null;
        slowMovementTicks = 0;
    }

    private void finishSlowMovement(ClientPlayerEntity player) {
        if (!slowMovementActive) {
            restoreGuidedMovement(player);
            return;
        }
        slowMovementActive = false;
        slowTarget = null;
        slowMovementTicks = 0;
        boolean restoreSprint = guidedSavedSprinting;
        restoreGuidedMovement(player);
        if (restoreSprint) {
            player.setSprinting(true);
        }
        if (messages()) {
            sendMessage("§aTeleported to the staged destination");
        }
        if (sounds()) {
            player.playSound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.2F);
        }
    }

    private void cancelSlowMovement() {
        slowMovementActive = false;
        manualMovementActive = false;
        manualOrigin = null;
        slowTarget = null;
        slowMovementTicks = 0;
        if (client != null && client.player != null) {
            restoreGuidedMovement(client.player);
        }
    }

    private void flushMovementPhase(ClientPlayerEntity player, boolean beforeTickEnd) {
        if (!pendingBlink || pendingTarget == null || player.hasVehicle()) {
            return;
        }
        Vec3d target = pendingTarget;
        pendingBlink = false;
        pendingTarget = null;
        suppressNextTickEnd = beforeTickEnd;
        try {
            SEND_BYPASS.set(true);
            BlinkEntry entry;
            while ((entry = blinkQueue.poll()) != null) {
                if (!entry.movementMarker() && !(entry.packet() instanceof PlayerInputC2SPacket) && entry.packet() != null) {
                    player.networkHandler.sendPacket(entry.packet());
                }
            }
            applyLocalTeleport(player, target);
            player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                target, isGroundTarget(target), player.horizontalCollision));
            player.networkHandler.sendPacket(ClientTickEndC2SPacket.INSTANCE);
        } finally {
            movementMarkerQueued = false;
            holdTickEnd = false;
            blinkQueue.clear();
            SEND_BYPASS.set(false);
        }
        if (messages()) {
            sendMessage("§aTeleported to §f" + format(target.x) + ", " + format(target.y) + ", " + format(target.z));
        }
        if (sounds()) {
            player.playSound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.2F);
        }
    }

    private void cancelBlink() {
        pendingBlink = false;
        pendingTarget = null;
        movementMarkerQueued = false;
        flushDelayTicks = 0;
        holdTickEnd = false;
        teleportAcceptanceDeadline = 0L;
        awaitingServerTeleport = false;
        serverTeleportApplied = false;
        serverTeleportMoveSent = false;
        serverTeleportPongSent = false;
        suppressNextTickEnd = false;
        waitingForExitTransaction = false;
        exitMovementSent = false;
        exitPingSeen = false;
        exitPingParameter = Integer.MIN_VALUE;
        exitPongSent = false;
        targetReady = false;
        blinkQueue.clear();
    }

    private Vec3d resolveTarget(ClientPlayerEntity player) {
        Vec3d origin = player.getEntityPos();
        double configuredDistance = Math.min(MAX_DISTANCE, Math.max(1.0D, slider("Distance", 15.0D)));
        double allowedExitDistance = grimSafe()
            ? Math.min(GRIM_SAFE_EXIT_DISTANCE, configuredDistance)
            : configuredDistance;
        Vec3d target;
        if ("Coordinates".equalsIgnoreCase(mode("Target", "Look"))) {
            target = new Vec3d(slider("X", 0.0D), slider("Y", 0.0D), slider("Z", 0.0D));
        } else {
            HitResult hit = player.raycast(configuredDistance, 1.0F, false);
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }
            target = safeBlockTarget(blockHit);
        }
        return findSafeEndpoint(player, origin, clampDistance(origin, target, allowedExitDistance));
    }

    private Vec3d safeBlockTarget(BlockHitResult hit) {
        BlockPos block = hit.getBlockPos();
        var shape = client.world.getBlockState(block).getCollisionShape(client.world, block);
        double topY = block.getY() + (shape.isEmpty() ? 1.0D : shape.getMax(Direction.Axis.Y));
        Direction side = hit.getSide();
        if (side == Direction.UP) {
            return new Vec3d(hit.getPos().x, topY + 0.01D, hit.getPos().z);
        }
        return hit.getPos().add(new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ()).multiply(0.35D));
    }

    private static Vec3d clampDistance(Vec3d origin, Vec3d target, double maximum) {
        Vec3d delta = target.subtract(origin);
        double length = delta.length();
        if (!Double.isFinite(length) || length < 1.0E-6D) {
            return origin;
        }
        return length <= maximum ? target : origin.add(delta.multiply(maximum / length));
    }

    private Vec3d findSafeEndpoint(ClientPlayerEntity player, Vec3d origin, Vec3d target) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)) {
            return null;
        }
        double[] vertical = {0.0D, 0.01D, -0.01D, 0.08D, -0.08D, 0.16D, -0.16D};
        for (double yOffset : vertical) {
            Vec3d candidate = target.add(0.0D, yOffset, 0.0D);
            if (isSafePlayerPosition(player, candidate) && candidate.squaredDistanceTo(origin) > 0.0625D) {
                return candidate;
            }
        }
        for (double xOffset : new double[]{-0.12D, 0.12D}) {
            for (double zOffset : new double[]{-0.12D, 0.12D}) {
                Vec3d candidate = target.add(xOffset, 0.01D, zOffset);
                if (isSafePlayerPosition(player, candidate) && candidate.squaredDistanceTo(origin) > 0.0625D) {
                    return candidate;
                }
            }
        }
        Vec3d delta = target.subtract(origin);
        if (delta.lengthSquared() > 1.0E-6D) {
            for (int index = 1; index <= 32; index++) {
                Vec3d candidate = origin.add(delta.multiply(1.0D - index / 32.0D));
                for (double yOffset : new double[]{0.0D, 0.01D, -0.01D}) {
                    Vec3d adjusted = candidate.add(0.0D, yOffset, 0.0D);
                    if (isSafePlayerPosition(player, adjusted) && adjusted.squaredDistanceTo(origin) > 0.0625D) {
                        return adjusted;
                    }
                }
            }
        }
        return null;
    }

    private void applyLocalTeleport(ClientPlayerEntity player, Vec3d target) {
        player.setPosition(target);
        player.setVelocity(Vec3d.ZERO);
        player.setOnGround(isGroundTarget(target));
        player.fallDistance = 0.0F;
    }

    private boolean isGroundTarget(Vec3d target) {
        if (client == null || client.world == null) {
            return false;
        }
        BlockPos below = BlockPos.ofFloored(target.x, target.y - 1.0E-4D, target.z);
        return !client.world.getBlockState(below).getCollisionShape(client.world, below).isEmpty();
    }

    private boolean isSafePlayerPosition(ClientPlayerEntity player, Vec3d position) {
        if (client == null || client.world == null || player == null) {
            return false;
        }
        Box box = player.getBoundingBox().offset(position.subtract(player.getEntityPos()));
        return !client.world.getBlockCollisions(player, box).iterator().hasNext();
    }

    private void restoreGuidedMovement(ClientPlayerEntity player) {
        if (player == null || !guidedRotationActive) {
            return;
        }
        player.setYaw(guidedSavedYaw);
        player.setPitch(guidedSavedPitch);
        guidedRotationActive = false;
    }

    private void stopAndDisable() {
        resetAll();
        if (context != null) {
            context.module().setEnabled(false);
        }
    }

    private void resetAll() {
        wasInBoat = false;
        pendingBlink = false;
        pendingTarget = null;
        movementMarkerQueued = false;
        movementSentThisTick = false;
        flushDelayTicks = 0;
        holdTickEnd = false;
        teleportAcceptancePending = false;
        teleportAcceptanceDeadline = 0L;
        awaitingServerTeleport = false;
        serverTeleportApplied = false;
        serverTeleportMoveSent = false;
        serverTeleportPongSent = false;
        suppressNextTickEnd = false;
        serverCorrectionPending = false;
        waitingForExitTransaction = false;
        exitMovementSent = false;
        exitPingSeen = false;
        exitPingParameter = Integer.MIN_VALUE;
        exitPongSent = false;
        targetReady = false;
        lastGrimPingParameter = Integer.MIN_VALUE;
        lastGrimPingNanos = 0L;
        lastGrimPongParameter = Integer.MIN_VALUE;
        lastGrimPongNanos = 0L;
        slowTarget = null;
        slowMovementActive = false;
        slowMovementTicks = 0;
        manualMovementActive = false;
        manualOrigin = null;
        guidedRotationActive = false;
        blinkQueue.clear();
    }

    private boolean enabled() {
        return vulcanMode && context != null && context.module().enabled() && client != null;
    }

    private boolean owns(ClientPlayerEntity player) {
        return enabled() && player != null && client.player == player;
    }

    private boolean manualControl() {
        return context != null && context.toggle("Manual Control", true);
    }

    private boolean grimSafe() {
        return context != null && context.toggle("Grim Safe", false);
    }

    private boolean messages() {
        return context == null || context.toggle("Messages", true);
    }

    private boolean sounds() {
        return context == null || context.toggle("Sounds", true);
    }

    private double slider(String label, double fallback) {
        return context == null ? fallback : context.slider(label, fallback);
    }

    private String mode(String label, String fallback) {
        return context == null ? fallback : context.mode(label, fallback);
    }

    private void sendMessage(String message) {
        if (messages() && client != null && client.player != null) {
            client.player.sendMessage(Text.literal("§8[§bBoat Flight§8] " + message), false);
        }
    }

    private static boolean featureOwns(BoatFlightFeature feature, ClientPlayerEntity player) {
        return feature != null && feature.owns(player);
    }

    private static boolean isVulcan(ModuleContext context) {
        return "Vulcan".equalsIgnoreCase(context.mode("Mode", "Vulcan"));
    }

    private static boolean isGrimTransactionParameter(int parameter) {
        return parameter >= Short.MIN_VALUE && parameter <= 0;
    }

    private static boolean isRecentGrimTransaction(int parameter, long timestamp, long now) {
        return isGrimTransactionParameter(parameter) && timestamp > 0L && now >= timestamp
            && now - timestamp <= RECENT_TRANSACTION_WINDOW_NANOS;
    }

    private static boolean isBlinkOrderSensitive(Packet<?> packet) {
        return packet instanceof PlayerInputC2SPacket
            || packet instanceof ClientCommandC2SPacket
            || packet instanceof PlayerActionC2SPacket
            || packet instanceof UpdateSelectedSlotC2SPacket
            || packet instanceof PlayerInteractEntityC2SPacket
            || packet instanceof PlayerInteractBlockC2SPacket
            || packet instanceof PlayerInteractItemC2SPacket
            || packet instanceof UpdatePlayerAbilitiesC2SPacket
            || packet instanceof HandSwingC2SPacket
            || packet instanceof SpectatorTeleportC2SPacket;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }
}
