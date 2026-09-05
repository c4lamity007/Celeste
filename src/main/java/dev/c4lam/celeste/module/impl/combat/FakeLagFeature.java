package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Conditional bounded latency; the original packet stream and tick order are preserved. */
public final class FakeLagFeature implements ModuleFeature {
    private static final String HANDLER_NAME = "celeste_fake_lag_tail";

    private final Object queueLock = new Object();
    private volatile boolean flushAttacks = true;
    private volatile boolean flushBlocks = true;
    private volatile boolean flushActions = true;
    private volatile long recoilMillis = 250L;
    private volatile long recoilUntil;
    private volatile boolean sawAttack;
    private volatile boolean sawBlock;
    private volatile boolean sawAction;

    private Channel channel;
    private PacketSplice splice;
    private boolean installed;
    private boolean cancelDown;

    // Observational state only. It never supplies a position or decision to the buffer.
    private static volatile VisualSnapshot visualSnapshot = VisualSnapshot.EMPTY;
    private long visualGeneration;
    private boolean visualTracking;
    private Vec3d lastForwardedPosition;
    private float lastForwardedYaw;
    private Vec3d releasePosition;
    private long releaseNanos;

    public record TrailPoint(Vec3d position) { }

    public record VisualSettings(String style, int color, boolean trail, boolean tether) {
        private static final VisualSettings DEFAULT =
            new VisualSettings("Ghost", 0x6DE4FF, true, true);
    }

    /** Last sent is observed from successful writes; it is not a server acknowledgement. */
    public record VisualSnapshot(Object world, boolean active, Vec3d lastSent,
                                 float lastSentYaw, List<TrailPoint> trail, int queuedPackets,
                                 Vec3d releasePosition, long releaseNanos,
                                 VisualSettings settings) {
        public static final VisualSnapshot EMPTY = new VisualSnapshot(null, false, null, 0F,
            List.of(), 0, null, 0L, VisualSettings.DEFAULT);

        public VisualSnapshot {
            trail = List.copyOf(trail);
        }
    }

    public static VisualSnapshot visualSnapshot() {
        return visualSnapshot;
    }

    @Override
    public String name() {
        return "Fake Lag";
    }

    @Override
    public void onEnable(ModuleContext context) {
        resetVisualState();
        cancelDown = cancelKeyDown(context);
        recoilUntil = 0L;
    }

    @Override
    public void onEndTick(ModuleContext context) {
        try {
            tick(context);
        } finally {
            publishVisualSnapshot(context);
        }
    }

    private void tick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.getNetworkHandler() == null || !client.player.isAlive()) {
            stop(true);
            return;
        }
        if (!installed && !install(client)) {
            return;
        }

        refresh(context);
        boolean cancel = cancelKeyDown(context);
        boolean cancelPressed = cancel && !cancelDown && client.currentScreen == null;
        cancelDown = cancel;
        if (cancelPressed) {
            // Cancel the delay, not the player's already-created vanilla actions.
            release();
            context.module().setEnabled(false);
            return;
        }

        long now = System.nanoTime();
        // Consume every signal even when another condition is already true.
        boolean attacked = consumeAttack();
        boolean blocked = consumeBlock();
        boolean action = consumeAction();
        boolean acted = (attacked && flushAttacks) || (blocked && flushBlocks) || (action && flushActions);
        if (acted || (context.toggle("Release on damage", true) && client.player.hurtTime > 0)) {
            recoilUntil = now + TimeUnit.MILLISECONDS.toNanos(recoilMillis);
            release();
            return;
        }

        boolean lag = (recoilUntil == 0L || now - recoilUntil >= 0L) && shouldLag(context);
        PacketSplice current = splice;
        if (current != null) current.setHolding(lag, lag ? rollHold(context) : 0L);
    }

    @Override
    public void onDisable(ModuleContext context) {
        stop(true);
        cancelDown = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        stop(false);
        cancelDown = false;
    }

    private void refresh(ModuleContext context) {
        flushAttacks = context.toggle("Flush attacks", true);
        flushBlocks = context.toggle("Flush blocks", true);
        flushActions = context.toggle("Flush actions", true);
        recoilMillis = Math.max(0L, Math.round(context.slider("Recoil", 250)));
    }

    private boolean shouldLag(ModuleContext context) {
        MinecraftClient client = context.client();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.currentScreen != null || player.isUsingItem()) {
            return false;
        }
        if ("Constant".equalsIgnoreCase(context.mode("Mode", "Dynamic"))) {
            return true;
        }

        double range = Math.max(2.0D, Math.min(12.0D, context.slider("Range", 5.0D)));
        PlayerEntity nearest = null;
        double clientDistance = Double.MAX_VALUE;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()
                || FriendManager.getInstance().isFriend(candidate)) {
                continue;
            }
            double distance = player.distanceTo(candidate);
            if (distance <= range && distance < clientDistance) {
                clientDistance = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return false;
        }

        double[] server = serverPosition();
        if (server == null) {
            return true;
        }
        double dx = nearest.getX() - server[0];
        double dy = nearest.getY() - server[1];
        double dz = nearest.getZ() - server[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) >= clientDistance;
    }

    private final class PacketSplice extends FakeLagPacketBuffer {
        private final long generation;

        private PacketSplice(Channel channel) {
            super(channel);
            synchronized (queueLock) { generation = visualGeneration; }
        }

        @Override
        protected void beforeWrite(Object message) {
            note(message);
            if (isHolding() && (message instanceof TeleportConfirmC2SPacket || shouldFlushBefore(message))) {
                recoilUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoilMillis);
                release();
            }
        }

        @Override
        protected ChannelPromise downstreamPromise(Object message, ChannelPromise promise) {
            return observeForwarded(message, promise, generation);
        }

        @Override
        protected void onForcedRelease() {
            recoilUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoilMillis);
        }
    }

    private boolean install(MinecraftClient client) {
        try {
            ClientConnection connection = client.getNetworkHandler().getConnection();
            Channel found = findChannel(connection);
            if (found == null || !found.isActive()) {
                return false;
            }
            if (found.pipeline().get(HANDLER_NAME) != null) {
                // A prior disable can still be queued on this event loop. Let
                // that owner drain/remove itself, then retry on the next tick.
                return false;
            }
            PacketSplice next = new PacketSplice(found);
            found.pipeline().addLast(HANDLER_NAME, next);
            splice = next;
            channel = found;
            installed = true;
            synchronized (queueLock) {
                visualTracking = true;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Channel findChannel(ClientConnection connection) throws IllegalAccessException {
        if (connection == null) {
            return null;
        }
        for (Field field : ClientConnection.class.getDeclaredFields()) {
            if (!Channel.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            return (Channel) field.get(connection);
        }
        return null;
    }

    private void stop(boolean send) {
        PacketSplice current = splice;
        splice = null;
        channel = null;
        installed = false;
        if (current != null) current.stop(send);
        recoilUntil = 0L;
        sawAttack = sawBlock = sawAction = false;
        resetVisualState();
    }

    private void release() {
        PacketSplice current = splice;
        if (current != null) current.release();
    }

    private void note(Object packet) {
        if (packet instanceof PlayerInteractEntityC2SPacket) {
            sawAttack = true;
        } else if (packet instanceof PlayerInteractBlockC2SPacket) {
            sawBlock = true;
        } else if (packet instanceof PlayerActionC2SPacket
            || packet instanceof HandSwingC2SPacket
            || packet instanceof PlayerInteractItemC2SPacket) {
            sawAction = true;
        }
    }

    private ChannelPromise observeForwarded(Object packet, ChannelPromise promise, long ownerGeneration) {
        if (!(packet instanceof PlayerMoveC2SPacket movement)) return promise;
        final long generation;
        synchronized (queueLock) {
            if (!visualTracking || ownerGeneration != visualGeneration) return promise;
            generation = visualGeneration;
        }
        // Minecraft 1.21.11 uses voidPromise for ordinary movement. Convert only
        // the promise at this existing downstream write, retaining its exception
        // forwarding, so both ordinary and explicitly-listened writes are observed.
        return SuccessfulWriteObserver.observe(promise, () -> {
            synchronized (queueLock) {
                if (!visualTracking || generation != visualGeneration) return;
                if (movement.changesPosition()) {
                    Vec3d next = new Vec3d(movement.getX(0D), movement.getY(0D), movement.getZ(0D));
                    if (!Double.isFinite(next.x) || !Double.isFinite(next.y) || !Double.isFinite(next.z)) return;
                    long now = System.nanoTime();
                    if (lastForwardedPosition != null && lastForwardedPosition.squaredDistanceTo(next) > 0.0025D
                        && visualSnapshot.queuedPackets() > 0 && now - releaseNanos > 450_000_000L) {
                        releasePosition = lastForwardedPosition;
                        releaseNanos = now;
                    }
                    lastForwardedPosition = next;
                }
                if (movement.changesLook()) lastForwardedYaw = movement.getYaw(lastForwardedYaw);
            }
        });
    }

    /** Netty-only seam shared by the actual forwarding paths and the regression harness. */
    static final class SuccessfulWriteObserver {
        private SuccessfulWriteObserver() { }

        static ChannelPromise observe(ChannelPromise original, Runnable onSuccess) {
            ChannelPromise observable = original.unvoid();
            observable.addListener(result -> {
                if (result.isSuccess()) onSuccess.run();
            });
            return observable;
        }
    }

    private void publishVisualSnapshot(ModuleContext context) {
        MinecraftClient client = context.client();
        if (channel == null || !channel.isActive()) {
            resetVisualState();
            return;
        }
        if (!installed || !context.module().enabled() || client.world == null
            || client.player == null || !client.player.isAlive()) {
            visualSnapshot = VisualSnapshot.EMPTY;
            return;
        }
        VisualSettings settings = new VisualSettings(context.mode("Visual style", "Ghost"),
            context.colorPick("Visual color", 0x6DE4FF) & 0xFFFFFF,
            context.toggle("Trail", true), context.toggle("Tether", true));
        List<FakeLagPacketBuffer.Pending> queued = pendingPackets();
        synchronized (queueLock) {
            if (!visualTracking) {
                visualSnapshot = VisualSnapshot.EMPTY;
                return;
            }
            // Sample once per client tick, never scan/copy the packet queue per frame.
            int positions = 0;
            for (FakeLagPacketBuffer.Pending packet : queued) if (packetPosition(packet.packet()) != null) positions++;
            int sampleCount = settings.trail() ? Math.min(32, positions) : 0;
            List<TrailPoint> trail = new ArrayList<>(sampleCount);
            int ordinal = 0;
            int sample = 0;
            for (FakeLagPacketBuffer.Pending packet : queued) {
                double[] position = packetPosition(packet.packet());
                if (position == null) continue;
                int desired = sampleCount <= 1 ? Math.max(0, positions - 1)
                    : (int) ((long) sample * (positions - 1) / (sampleCount - 1));
                if (sample < sampleCount && ordinal == desired) {
                    trail.add(new TrailPoint(new Vec3d(position[0], position[1], position[2])));
                    sample++;
                }
                ordinal++;
            }
            visualSnapshot = new VisualSnapshot(client.world, true, lastForwardedPosition,
                lastForwardedYaw, trail, queued.size(), releasePosition, releaseNanos, settings);
        }
    }

    private void resetVisualState() {
        synchronized (queueLock) {
            visualGeneration++;
            visualTracking = false;
            lastForwardedPosition = null;
            lastForwardedYaw = 0F;
            releasePosition = null;
            releaseNanos = 0L;
            visualSnapshot = VisualSnapshot.EMPTY;
        }
    }

    private boolean shouldFlushBefore(Object packet) {
        return (flushAttacks && packet instanceof PlayerInteractEntityC2SPacket)
            || (flushBlocks && packet instanceof PlayerInteractBlockC2SPacket)
            || (flushActions && (packet instanceof PlayerActionC2SPacket
                || packet instanceof HandSwingC2SPacket
                || packet instanceof PlayerInteractItemC2SPacket));
    }

    private List<FakeLagPacketBuffer.Pending> pendingPackets() {
        PacketSplice current = splice;
        return current == null ? List.of() : current.snapshot();
    }

    private double[] serverPosition() {
        // Legacy Dynamic-mode decision input; visualSnapshot deliberately uses
        // successfully forwarded movement instead of this first queued position.
        for (FakeLagPacketBuffer.Pending packet : pendingPackets()) {
            double[] position = packetPosition(packet.packet());
            if (position != null) return position;
        }
        return null;
    }

    private static double[] packetPosition(Object packet) {
        if (!(packet instanceof PlayerMoveC2SPacket movement) || !movement.changesPosition()) return null;
        return new double[] {movement.getX(0D), movement.getY(0D), movement.getZ(0D)};
    }

    private boolean consumeAttack() {
        boolean value = sawAttack;
        sawAttack = false;
        return value;
    }

    private boolean consumeBlock() {
        boolean value = sawBlock;
        sawBlock = false;
        return value;
    }

    private boolean consumeAction() {
        boolean value = sawAction;
        sawAction = false;
        return value;
    }

    private long rollHold(ModuleContext context) {
        double low = boundedDelay(context.rangeLow("Delay", 200), 200D);
        double high = Math.max(low, boundedDelay(context.rangeHigh("Delay", 400), 400D));
        return high <= low ? Math.round(low)
            : Math.round(ThreadLocalRandom.current().nextDouble(low, Math.nextUp(high)));
    }

    private static double boundedDelay(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0D, Math.min(FakeLagPacketBuffer.MAX_DELAY_MILLIS, value)) : fallback;
    }

    private static boolean cancelKeyDown(ModuleContext context) {
        MinecraftClient client = context.client();
        int key = context.keybind("Cancel");
        return key != GLFW.GLFW_KEY_UNKNOWN && client != null && client.getWindow() != null
            && InputUtil.isKeyPressed(client.getWindow(), key);
    }
}
