package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Client-thread rotation arbiter. It never mutates the player's camera rotation;
 * mixins only substitute this manager's value while vanilla constructs packets.
 */
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    private final Map<Object, ActiveTask> tasks = new HashMap<>();
    private long tick;
    private long sequence;
    private ClientWorld world;
    private ClientPlayerEntity playerInstance;
    private volatile Rotation serverRotation = Rotation.ZERO;
    private Rotation previousRenderRotation = Rotation.ZERO;
    private volatile Object activeOwner;
    private volatile MoveFixMode moveFixMode = MoveFixMode.OFF;
    private volatile boolean active;
    private float outgoingYaw;
    private boolean outgoingYawInitialized;

    private RotationManager() {
    }

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    public boolean submit(RotationTask task) {
        Objects.requireNonNull(task, "task");
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            throw new IllegalStateException("Rotation requests must run on the Minecraft client thread");
        }
        // Mounted look/movement packets have a separate vanilla lifecycle. Do
        // not silently substitute their rotation unless a future API models a
        // concrete mounted action explicitly.
        if (client.player != null && client.player.hasVehicle()) {
            tasks.remove(task.owner());
            if (Objects.equals(activeOwner, task.owner())) {
                deactivateToCamera(client.player);
            }
            return false;
        }
        long now = System.nanoTime();
        long deadline;
        try {
            deadline = Math.addExact(now, task.lifetimeNanos());
        } catch (ArithmeticException ignored) {
            deadline = Long.MAX_VALUE;
        }

        ActiveTask activeTask = new ActiveTask(task, tick, deadline, ++sequence);
        tasks.put(task.owner(), activeTask);
        ActiveTask winner = selectWinner(now);
        return winner == activeTask;
    }

    public void release(Object owner) {
        if (owner == null) {
            return;
        }

        tasks.remove(owner);
        if (Objects.equals(activeOwner, owner)) {
            deactivateToCamera(MinecraftClient.getInstance().player);
        }
    }

    public void tick(MinecraftClient client) {
        prepare(client);
        previousRenderRotation = active ? serverRotation : client.player == null ? serverRotation
            : new Rotation(client.player.lastYaw, client.player.lastPitch);
        tick++;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        if (player.hasVehicle()) {
            tasks.clear();
            deactivateToCamera(player);
            return;
        }
        // Vanilla will handle these inputs after module START hooks. Keep its
        // interaction paired with the camera look rather than an unrelated
        // module's silent rotation. Explicit use-edge replacement modules drain
        // their owned binding before this point and remain eligible.
        if (VanillaInputGuard.hasInteractionConflict(client)) {
            tasks.clear();
            deactivateToCamera(player);
            return;
        }

        Rotation camera = new Rotation(player.getYaw(), player.getPitch());
        long now = System.nanoTime();
        tasks.values().removeIf(task -> task.expired(tick, now));
        ActiveTask winner = selectWinner(now);
        if (winner == null) {
            active = false;
            activeOwner = null;
            moveFixMode = MoveFixMode.OFF;
            serverRotation = camera;
            return;
        }

        Rotation base = active ? serverRotation : camera;
        RotationTask task = winner.task;
        Rotation stepped = base.stepToward(
            task.target(),
            task.maximumYawStep(),
            task.maximumPitchStep()
        );
        if (task.sensitivityQuantized() && SeeRotationsFeature.shouldQuantizeSensitivity()) {
            double sensitivity = client.options.getMouseSensitivity().getValue();
            stepped = RotationQuantizer.quantize(base, stepped, sensitivity);
        }

        serverRotation = stepped;
        activeOwner = task.owner();
        moveFixMode = task.moveFixMode().packetRepresentable();
        active = true;
    }

    /** Clears stale leases before modules submit requests for a new connection/player. */
    public void prepare(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld nextWorld = client.world;
        if (player == null || nextWorld == null) {
            if (playerInstance != null || world != null || !tasks.isEmpty()) {
                clear();
            }
            playerInstance = null;
            world = null;
            return;
        }
        if (player != playerInstance || nextWorld != world) {
            clear();
            playerInstance = player;
            world = nextWorld;
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
            previousRenderRotation = serverRotation;
            outgoingYaw = player.getYaw();
            outgoingYawInitialized = true;
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isOwnedBy(Object owner) {
        return active && Objects.equals(activeOwner, owner);
    }

    public Object activeOwner() {
        return activeOwner;
    }

    /**
     * An owner that requested a live rotation may act only when that request is
     * the selected server rotation. Owners without a rotation request are not
     * coupled to unrelated low-priority visual/aim assistance.
     */
    public boolean permitsAction(Object owner) {
        ActiveTask requested = tasks.get(owner);
        long now = System.nanoTime();
        return requested == null || (!requested.expired(tick, now) && isOwnedBy(owner));
    }

    /**
     * Pins the outgoing look to the selected action. If that action did not
     * request a rotation, fall back to the camera so an unrelated aim task can
     * never leak into its vanilla interaction packet.
     */
    public boolean alignForAction(Object owner) {
        ActiveTask requested = tasks.get(owner);
        long now = System.nanoTime();
        if (requested != null) {
            return !requested.expired(tick, now) && isOwnedBy(owner);
        }
        if (active && !Objects.equals(activeOwner, owner)) {
            deactivateToCamera(MinecraftClient.getInstance().player);
        }
        return true;
    }

    public Rotation serverRotation() {
        return serverRotation;
    }

    /** Render-only interpolation; never substituted for the exact packet/action aim. */
    public Rotation renderRotation(float tickDelta) {
        Rotation current = serverRotation;
        return new Rotation(ElytraRotationMath.yaw(previousRenderRotation.yaw(), current.yaw(), tickDelta),
            ElytraRotationMath.pitch(previousRenderRotation.pitch(), current.pitch(), tickDelta));
    }

    /**
     * Lets the owning module refine the already-selected silent look between
     * ticks without touching the camera. Packets still go out on the tick.
     */
    public void hold(Object owner, Rotation rotation, MoveFixMode mode) {
        if (rotation == null || !isOwnedBy(owner)) {
            return;
        }
        serverRotation = rotation;
        moveFixMode = mode.packetRepresentable();
        active = true;
    }

    public MoveFixMode moveFixMode() {
        return active ? moveFixMode : MoveFixMode.OFF;
    }

    public float serverYawOr(float fallback) {
        return active ? serverRotation.yaw() : fallback;
    }

    /**
     * Returns the desired packet yaw without ever introducing a modulo-360
     * seam. Internally equivalent angles may be canonical, but vanilla yaw is
     * continuous: after 179 degrees, -179 degrees must be emitted as 181, not
     * as a raw -358 degree snap. The phase is retained while rotations release
     * back to the camera and is reset only with the player/world connection.
     */
    public float outgoingYawOr(float fallback) {
        float desired = active ? serverRotation.yaw() : fallback;
        if (!Float.isFinite(desired)) {
            return fallback;
        }
        if (!outgoingYawInitialized || !Float.isFinite(outgoingYaw)) {
            outgoingYaw = desired;
            outgoingYawInitialized = true;
        } else {
            outgoingYaw += MathHelper.wrapDegrees(desired - outgoingYaw);
        }
        return outgoingYaw;
    }

    public float serverPitchOr(float fallback) {
        return active ? serverRotation.pitch() : fallback;
    }

    public void clear() {
        tasks.clear();
        active = false;
        activeOwner = null;
        moveFixMode = MoveFixMode.OFF;
        outgoingYaw = 0.0F;
        outgoingYawInitialized = false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
        }
        previousRenderRotation = serverRotation;
    }

    private void deactivateToCamera(ClientPlayerEntity player) {
        active = false;
        activeOwner = null;
        moveFixMode = MoveFixMode.OFF;
        if (player != null) {
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
        }
    }

    private ActiveTask selectWinner(long now) {
        ActiveTask winner = null;
        for (ActiveTask candidate : tasks.values()) {
            if (candidate.expired(tick, now)) {
                continue;
            }
            if (winner == null
                || candidate.task.priority() > winner.task.priority()
                || (candidate.task.priority() == winner.task.priority()
                && candidate.sequence > winner.sequence)) {
                winner = candidate;
            }
        }
        return winner;
    }

    private record ActiveTask(RotationTask task, long submittedTick, long deadlineNanos, long sequence) {
        private boolean expired(long currentTick, long now) {
            return currentTick > submittedTick + task.lifetimeTicks() || now > deadlineNanos;
        }
    }
}
