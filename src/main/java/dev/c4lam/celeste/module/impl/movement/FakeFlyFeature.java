package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;

/** Local visual flight rig with input isolation and an optional local scaffold trail. */
public final class FakeFlyFeature implements ModuleFeature {
    private static final double SPEED_MULTIPLIER = 1.5D;
    private static final int MAX_LOCAL_BLOCKS = 128;
    private static final String[] BOOT_MESSAGES = {
        "§8[§dCeleste§8] §7Detaching logging hatch...",
        "§8[§dCeleste§8] §7Priming vector lattice...",
        "§8[§dCeleste§8] §7Stabilizing flight envelope...",
        "§8[§dCeleste§8] §7Calibrating spatial drive...",
        "§8[§dCeleste§8] §aFlight systems online."
    };

    private static volatile FakeFlyFeature instance;

    private final LinkedHashMap<BlockPos, ScaffoldState> localScaffold = new LinkedHashMap<>();
    private MinecraftClient client;
    private ModuleContext context;
    private ClientWorld scaffoldWorld;
    private Vec3d cameraPosition;
    private Vec3d previousCameraPosition;
    private float cameraYaw;
    private float cameraPitch;
    private boolean flightOnline;
    private int bootTicks;
    private int bootMessageIndex;
    private int activationDelayTicks;

    @Override
    public String name() {
        return "Fly";
    }

    @Override
    public void onEnable(ModuleContext context) {
        this.client = context.client();
        this.context = context;
        ClientPlayerEntity player = context.client().player;
        cameraPosition = player == null ? null : player.getCameraPosVec(1.0F);
        previousCameraPosition = cameraPosition;
        cameraYaw = player == null ? 0.0F : player.getYaw();
        cameraPitch = player == null ? 0.0F : player.getPitch();
        flightOnline = false;
        bootTicks = 0;
        bootMessageIndex = 0;
        activationDelayTicks = 0;
        instance = this;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        this.client = context.client();
        this.context = context;
        ClientPlayerEntity player = context.client().player;
        if (player == null || context.client().world == null || !player.isAlive() || player.isSpectator()) {
            reset();
            return;
        }
        if (cameraPosition == null) {
            cameraPosition = player.getCameraPosVec(1.0F);
            previousCameraPosition = cameraPosition;
        }

        cameraYaw = player.getYaw();
        cameraPitch = player.getPitch();
        if (!flightOnline) {
            advanceStartup(player);
            return;
        }

        previousCameraPosition = cameraPosition;
        if (context.client().currentScreen == null) {
            moveCamera(player);
        }
    }

    @Override
    public void onDisable(ModuleContext context) {
        reset();
        if (instance == this) {
            instance = null;
        }
        client = null;
        this.context = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        reset();
    }

    /** Input is read only for this visual rig; vanilla player movement receives an empty input state. */
    public static boolean shouldZeroVanillaInput(ClientPlayerEntity player) {
        FakeFlyFeature feature = instance;
        return feature != null && feature.activeFor(player);
    }

    public static Vec3d cameraPosition(float tickProgress) {
        FakeFlyFeature feature = instance;
        if (feature == null || !feature.flightOnline || feature.cameraPosition == null) {
            return null;
        }
        Vec3d previous = feature.previousCameraPosition;
        return previous == null
            ? feature.cameraPosition
            : previous.lerp(feature.cameraPosition, MathHelper.clamp(tickProgress, 0.0F, 1.0F));
    }

    public static float cameraYaw() {
        FakeFlyFeature feature = instance;
        return feature == null ? 0.0F : feature.cameraYaw;
    }

    public static float cameraPitch() {
        FakeFlyFeature feature = instance;
        return feature == null ? 0.0F : feature.cameraPitch;
    }

    public static boolean shouldHideLocalModel(MinecraftClient client) {
        FakeFlyFeature feature = instance;
        return feature != null
            && feature.activeFor(client == null ? null : client.player)
            && feature.flightOnline
            && client.options.getPerspective().ordinal() == 0;
    }

    /** Render-only body position for third person. */
    public static Vec3d simulatedBodyPosition(MinecraftClient client, float tickProgress) {
        FakeFlyFeature feature = instance;
        ClientPlayerEntity player = client == null ? null : client.player;
        if (feature == null || !feature.activeFor(player) || !feature.flightOnline) {
            return null;
        }
        Vec3d visualEye = cameraPosition(tickProgress);
        return visualEye == null ? null : visualEye.subtract(0.0D, player.getStandingEyeHeight(), 0.0D);
    }

    public static boolean shouldRenderSimulatedBody(MinecraftClient client) {
        return simulatedBodyPosition(client, 1.0F) != null && client.options.getPerspective().ordinal() != 0;
    }

    private void advanceStartup(ClientPlayerEntity player) {
        if (bootMessageIndex < BOOT_MESSAGES.length && bootTicks++ % 5 == 0) {
            player.sendMessage(net.minecraft.text.Text.literal(BOOT_MESSAGES[bootMessageIndex++]), false);
            if (bootMessageIndex == BOOT_MESSAGES.length) {
                activationDelayTicks = 8;
            }
            return;
        }
        if (bootMessageIndex == BOOT_MESSAGES.length && activationDelayTicks-- <= 0) {
            flightOnline = true;
            previousCameraPosition = cameraPosition;
        }
    }

    private void moveCamera(ClientPlayerEntity player) {
        double forwardAxis = axis(client.options.forwardKey.isPressed(), client.options.backKey.isPressed());
        double strafeAxis = axis(client.options.leftKey.isPressed(), client.options.rightKey.isPressed());
        double verticalAxis = axis(client.options.jumpKey.isPressed(), client.options.sneakKey.isPressed());
        if (forwardAxis == 0.0D && strafeAxis == 0.0D && verticalAxis == 0.0D) {
            return;
        }

        double yawRadians = Math.toRadians(cameraYaw);
        Vec3d forward = new Vec3d(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
        Vec3d right = new Vec3d(Math.cos(yawRadians), 0.0D, Math.sin(yawRadians));
        Vec3d direction = forward.multiply(forwardAxis)
            .add(right.multiply(strafeAxis))
            .add(0.0D, verticalAxis, 0.0D);
        if (direction.lengthSquared() <= 1.0E-8D) {
            return;
        }

        double speed = Math.max(0.05D, context.slider("Speed", 1.0D)) * SPEED_MULTIPLIER;
        if (client.options.sprintKey.isPressed()) {
            speed *= 2.0D;
        }
        Vec3d resolved = resolveFlightMotion(player, direction.normalize().multiply(speed));
        if (resolved.squaredDistanceTo(cameraPosition) > 1.0E-8D) {
            cameraPosition = resolved;
            placeLocalScaffold(player);
        }
    }

    /** Walk the visual body through the same block collision volume used by ordinary movement. */
    private Vec3d resolveFlightMotion(ClientPlayerEntity player, Vec3d requestedMotion) {
        int steps = Math.max(1, (int) Math.ceil(requestedMotion.length() / 0.125D));
        Vec3d step = requestedMotion.multiply(1.0D / steps);
        Vec3d resolved = cameraPosition;
        for (int index = 0; index < steps; index++) {
            Vec3d candidate = resolved.add(step);
            if (!canOccupyVisualBody(player, candidate)) {
                break;
            }
            resolved = candidate;
        }
        return resolved;
    }

    private boolean canOccupyVisualBody(ClientPlayerEntity player, Vec3d visualEye) {
        Vec3d visualBody = visualEye.subtract(0.0D, player.getStandingEyeHeight(), 0.0D);
        Vec3d playerBody = new Vec3d(player.getX(), player.getY(), player.getZ());
        Box visualBox = player.getBoundingBox().offset(visualBody.subtract(playerBody));
        return client.world.isSpaceEmpty(player, visualBox);
    }

    /** Writes only ClientWorld state so the visual trail uses ordinary block rendering. */
    private void placeLocalScaffold(ClientPlayerEntity player) {
        if (!context.toggle("Visual scaffold", true) || client.world == null) {
            return;
        }
        BlockState material = hotbarScaffoldMaterial(player);
        if (material == null) {
            return;
        }
        BlockPos candidate = BlockPos.ofFloored(
            cameraPosition.x,
            cameraPosition.y - player.getStandingEyeHeight() - 1.0D,
            cameraPosition.z
        );
        if (!client.world.getBlockState(candidate).isAir() || !hasLocalSupport(candidate)) {
            return;
        }
        scaffoldWorld = client.world;
        localScaffold.putIfAbsent(candidate.toImmutable(), new ScaffoldState(client.world.getBlockState(candidate), material));
        client.world.setBlockState(candidate, material, Block.NOTIFY_LISTENERS);
        while (localScaffold.size() > MAX_LOCAL_BLOCKS) {
            BlockPos oldest = localScaffold.keySet().iterator().next();
            restoreLocalBlock(oldest, localScaffold.remove(oldest));
        }
    }

    private boolean hasLocalSupport(BlockPos target) {
        if (!client.world.getBlockState(target.down()).isAir()) {
            return true;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            if (!client.world.getBlockState(target.offset(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private BlockState hotbarScaffoldMaterial(ClientPlayerEntity player) {
        BlockState selected = blockState(player.getMainHandStack());
        if (selected != null) {
            return selected;
        }
        for (int slot = 0; slot < player.getInventory().getHotbarSize(); slot++) {
            BlockState candidate = blockState(player.getInventory().getStack(slot));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static BlockState blockState(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().getDefaultState();
        return state.isAir() ? null : state;
    }

    private boolean enabled() {
        return client != null && context != null && context.module().enabled();
    }

    private boolean activeFor(ClientPlayerEntity player) {
        return enabled() && client.player == player && cameraPosition != null;
    }

    private void restoreLocalScaffold() {
        if (scaffoldWorld != null) {
            localScaffold.forEach(this::restoreLocalBlock);
        }
        localScaffold.clear();
        scaffoldWorld = null;
    }

    private void restoreLocalBlock(BlockPos position, ScaffoldState state) {
        if (scaffoldWorld != null && scaffoldWorld.getBlockState(position).equals(state.placed())) {
            scaffoldWorld.setBlockState(position, state.original(), Block.NOTIFY_LISTENERS);
        }
    }

    private void reset() {
        restoreLocalScaffold();
        cameraPosition = null;
        previousCameraPosition = null;
        cameraYaw = 0.0F;
        cameraPitch = 0.0F;
        flightOnline = false;
        bootTicks = 0;
        bootMessageIndex = 0;
        activationDelayTicks = 0;
    }

    private static double axis(boolean positive, boolean negative) {
        return (positive ? 1.0D : 0.0D) - (negative ? 1.0D : 0.0D);
    }

    private record ScaffoldState(BlockState original, BlockState placed) {
    }
}
