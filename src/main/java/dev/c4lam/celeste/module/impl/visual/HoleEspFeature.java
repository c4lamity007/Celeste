package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-thread, incremental scanner for 1x1 two-air-high combat holes. */
public final class HoleEspFeature implements ModuleFeature {
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int MAX_CHECKS_PER_TICK = 192;
    private static final int MAX_CURSOR_STEPS_PER_TICK = 512;
    private static final int MAX_HOLES = 256;
    private static final int OBSIDIAN_COLOR = 0xD760FF;
    private static final int BEDROCK_COLOR = 0x68E4FF;

    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private final Map<Long, HoleType> working = new HashMap<>();
    private volatile List<HolePosition> published = List.of();
    private volatile List<VisualWorldRenderer.WorldBox> renderBoxes = List.of();

    private volatile boolean enabled;
    private boolean cycleActive;
    private boolean showObsidian = true;
    private boolean showBedrock = true;
    private int range = 8;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private int cursorX;
    private int cursorY;
    private int cursorZ;

    @Override
    public String name() {
        return "Hole ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        enabled = true;
        updateSettings(context);
        resetScan();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        ClientWorld world = context.client().world;
        ClientPlayerEntity player = context.client().player;
        if (world == null || player == null) {
            resetAll();
            return;
        }

        int oldRange = range;
        updateSettings(context);
        BlockPos center = player.getBlockPos();
        double teleportThreshold = Math.max(8.0D, range * 2.0D);
        if (!cycleActive
            || oldRange != range
            || squaredDistance(anchorX, anchorY, anchorZ, center.getX(), center.getY(), center.getZ())
                > teleportThreshold * teleportThreshold) {
            beginCycle(center);
        }
        scan(world);
        rebuildRenderBoxes(player);
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        resetAll();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetAll();
    }

    List<VisualWorldRenderer.WorldBox> renderBoxes() {
        return enabled ? renderBoxes : List.of();
    }

    private void updateSettings(ModuleContext context) {
        range = MathHelper.clamp((int) Math.round(context.slider("Range", 8.0D)), 4, 16);
        showObsidian = context.toggle("Obsidian", true);
        showBedrock = context.toggle("Bedrock", true);
    }

    private void beginCycle(BlockPos center) {
        anchorX = center.getX();
        anchorY = center.getY();
        anchorZ = center.getZ();
        cursorX = -range;
        cursorY = -range;
        cursorZ = -range;
        working.clear();
        cycleActive = true;
    }

    private void scan(ClientWorld world) {
        int checked = 0;
        int steps = 0;
        int rangeSquared = range * range;
        while (cycleActive && checked < MAX_CHECKS_PER_TICK && steps < MAX_CURSOR_STEPS_PER_TICK) {
            if (cursorY > range) {
                finishCycle();
                return;
            }

            int dx = cursorX;
            int dy = cursorY;
            int dz = cursorZ;
            advanceCursor();
            steps++;
            if (dx * dx + dy * dy + dz * dz > rangeSquared) {
                continue;
            }

            checked++;
            int x = anchorX + dx;
            int y = anchorY + dy;
            int z = anchorZ + dz;
            HoleType type = classify(world, x, y, z);
            if (type != null && working.size() < MAX_HOLES) {
                working.put(BlockPos.asLong(x, y, z), type);
            }
        }
    }

    private HoleType classify(ClientWorld world, int x, int y, int z) {
        scratch.set(x, y, z);
        if (!world.getBlockState(scratch).isAir()) {
            return null;
        }
        scratch.set(x, y + 1, z);
        if (!world.getBlockState(scratch).isAir()) {
            return null;
        }
        scratch.set(x, y - 1, z);
        if (!world.getBlockState(scratch).isSideSolidFullSquare(world, scratch, Direction.UP)) {
            return null;
        }

        boolean allBedrock = true;
        for (Direction direction : HORIZONTAL) {
            scratch.set(x + direction.getOffsetX(), y, z + direction.getOffsetZ());
            BlockState wall = world.getBlockState(scratch);
            if (wall.isOf(Blocks.BEDROCK)) {
                continue;
            }
            if (!wall.isOf(Blocks.OBSIDIAN)) {
                return null;
            }
            allBedrock = false;
        }
        return allBedrock ? HoleType.BEDROCK : HoleType.OBSIDIAN;
    }

    private void advanceCursor() {
        if (++cursorX <= range) {
            return;
        }
        cursorX = -range;
        if (++cursorZ <= range) {
            return;
        }
        cursorZ = -range;
        cursorY++;
    }

    private void finishCycle() {
        List<HolePosition> next = new ArrayList<>(working.size());
        for (Map.Entry<Long, HoleType> entry : working.entrySet()) {
            long packed = entry.getKey();
            next.add(new HolePosition(
                BlockPos.unpackLongX(packed),
                BlockPos.unpackLongY(packed),
                BlockPos.unpackLongZ(packed),
                entry.getValue()
            ));
        }
        published = List.copyOf(next);
        working.clear();
        cycleActive = false;
    }

    private void rebuildRenderBoxes(ClientPlayerEntity player) {
        if (!showBedrock && !showObsidian) {
            renderBoxes = List.of();
            return;
        }

        double rangeSquared = (double) range * range;
        List<VisualWorldRenderer.WorldBox> next = new ArrayList<>(published.size());
        for (HolePosition hole : published) {
            if ((hole.type() == HoleType.BEDROCK && !showBedrock)
                || (hole.type() == HoleType.OBSIDIAN && !showObsidian)
                || squaredDistance(
                    player.getX(), player.getY(), player.getZ(),
                    hole.x() + 0.5D, hole.y() + 0.5D, hole.z() + 0.5D
                ) > rangeSquared) {
                continue;
            }

            int color = hole.type() == HoleType.BEDROCK ? BEDROCK_COLOR : OBSIDIAN_COLOR;
            next.add(new VisualWorldRenderer.WorldBox(
                new Box(
                    hole.x() + 0.06D, hole.y() + 0.02D, hole.z() + 0.06D,
                    hole.x() + 0.94D, hole.y() + 0.16D, hole.z() + 0.94D
                ),
                VisualWorldRenderer.argb(0.16F, color),
                VisualWorldRenderer.argb(0.82F, color),
                1.35F
            ));
        }
        renderBoxes = List.copyOf(next);
    }

    private void resetScan() {
        cycleActive = false;
        working.clear();
    }

    private void resetAll() {
        resetScan();
        published = List.of();
        renderBoxes = List.of();
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx;
        double y = ay - by;
        double z = az - bz;
        return x * x + y * y + z * z;
    }

    private enum HoleType {
        OBSIDIAN,
        BEDROCK
    }

    private record HolePosition(int x, int y, int z, HoleType type) {
    }
}
