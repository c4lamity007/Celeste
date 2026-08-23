package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Incremental ore scanner; never performs the supplied Volt full-volume scan in one tick. */
public final class OreEspExpansionFeature implements ModuleFeature {
    private static final int MAX_CURSOR_STEPS_PER_TICK = 768;
    private static final int MAX_DETECTED = 2_048;
    private static final int MAX_RENDERED = 512;

    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private final Map<Long, OreType> working = new HashMap<>();
    private volatile List<VisualExpansionRenderer.WorldBox> renderBoxes = List.of();
    private List<OrePosition> published = List.of();
    private volatile boolean enabled;
    private int range = 16;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private int cursorX;
    private int cursorY;
    private int cursorZ;
    private boolean cycleActive;
    private boolean diamond = true;
    private boolean emerald = true;
    private boolean gold = true;
    private boolean iron = true;
    private boolean copper = true;
    private boolean redstone = true;
    private boolean lapis = true;
    private boolean coal = true;
    private boolean ancientDebris = true;
    private boolean netherGold;
    private boolean quartz;

    @Override
    public String name() {
        return "Ore ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
        resetAll();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        ClientWorld world = context.client().world;
        if (player == null || world == null) {
            resetAll();
            return;
        }

        int oldRange = range;
        update(context);
        BlockPos center = player.getBlockPos();
        if (!cycleActive || oldRange != range || squaredDistance(center, anchorX, anchorY, anchorZ) > 64.0D) {
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

    List<VisualExpansionRenderer.WorldBox> renderBoxes() {
        return enabled ? renderBoxes : List.of();
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp((int) Math.round(context.slider("Range", 16.0D)), 4, 32);
        diamond = context.toggle("Diamond", true);
        emerald = context.toggle("Emerald", true);
        gold = context.toggle("Gold", true);
        iron = context.toggle("Iron", true);
        copper = context.toggle("Copper", true);
        redstone = context.toggle("Redstone", true);
        lapis = context.toggle("Lapis", true);
        coal = context.toggle("Coal", true);
        ancientDebris = context.toggle("Ancient Debris", true);
        netherGold = context.toggle("Nether Gold", false);
        quartz = context.toggle("Quartz", false);
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
        int steps = 0;
        int radiusSquared = range * range;
        while (cycleActive && steps++ < MAX_CURSOR_STEPS_PER_TICK) {
            if (cursorY > range) {
                finishCycle();
                return;
            }
            int dx = cursorX;
            int dy = cursorY;
            int dz = cursorZ;
            advanceCursor();
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                continue;
            }

            int x = anchorX + dx;
            int y = anchorY + dy;
            int z = anchorZ + dz;
            scratch.set(x, y, z);
            OreType type = classify(world.getBlockState(scratch).getBlock());
            if (type != null && working.size() < MAX_DETECTED) {
                working.put(BlockPos.asLong(x, y, z), type);
            }
        }
    }

    private void advanceCursor() {
        if (++cursorX <= range) return;
        cursorX = -range;
        if (++cursorZ <= range) return;
        cursorZ = -range;
        cursorY++;
    }

    private void finishCycle() {
        List<OrePosition> next = new ArrayList<>(working.size());
        for (Map.Entry<Long, OreType> entry : working.entrySet()) {
            long packed = entry.getKey();
            next.add(new OrePosition(
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
        double rangeSquared = (double) range * range;
        List<OrePosition> visible = published.stream()
            .filter(ore -> isEnabled(ore.type()))
            .filter(ore -> squaredDistance(player.getX(), player.getY(), player.getZ(), ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D) <= rangeSquared)
            .sorted(Comparator.comparingInt((OrePosition ore) -> ore.type().priority()).reversed()
                .thenComparingDouble(ore -> squaredDistance(player.getX(), player.getY(), player.getZ(), ore.x(), ore.y(), ore.z())))
            .limit(MAX_RENDERED)
            .toList();
        List<VisualExpansionRenderer.WorldBox> boxes = new ArrayList<>(visible.size());
        for (OrePosition ore : visible) {
            boxes.add(new VisualExpansionRenderer.WorldBox(
                new Box(ore.x() + 0.035D, ore.y() + 0.035D, ore.z() + 0.035D,
                    ore.x() + 0.965D, ore.y() + 0.965D, ore.z() + 0.965D),
                VisualExpansionRenderer.argb(0.10F, ore.type().rgb()),
                VisualExpansionRenderer.argb(0.88F, ore.type().rgb()),
                1.25F
            ));
        }
        renderBoxes = List.copyOf(boxes);
    }

    private boolean isEnabled(OreType type) {
        return switch (type) {
            case DIAMOND -> diamond;
            case EMERALD -> emerald;
            case GOLD -> gold;
            case IRON -> iron;
            case COPPER -> copper;
            case REDSTONE -> redstone;
            case LAPIS -> lapis;
            case COAL -> coal;
            case ANCIENT_DEBRIS -> ancientDebris;
            case NETHER_GOLD -> netherGold;
            case QUARTZ -> quartz;
        };
    }

    private static OreType classify(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return OreType.DIAMOND;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return OreType.EMERALD;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) return OreType.GOLD;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return OreType.IRON;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) return OreType.COPPER;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) return OreType.REDSTONE;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) return OreType.LAPIS;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) return OreType.COAL;
        if (block == Blocks.ANCIENT_DEBRIS) return OreType.ANCIENT_DEBRIS;
        if (block == Blocks.NETHER_GOLD_ORE) return OreType.NETHER_GOLD;
        if (block == Blocks.NETHER_QUARTZ_ORE) return OreType.QUARTZ;
        return null;
    }

    private void resetAll() {
        cycleActive = false;
        working.clear();
        published = List.of();
        renderBoxes = List.of();
    }

    private static double squaredDistance(BlockPos pos, int x, int y, int z) {
        return squaredDistance(pos.getX(), pos.getY(), pos.getZ(), x, y, z);
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx;
        double y = ay - by;
        double z = az - bz;
        return x * x + y * y + z * z;
    }

    private enum OreType {
        DIAMOND(0x62EAF2, 10), EMERALD(0x39E67D, 9), ANCIENT_DEBRIS(0xD78D72, 9),
        GOLD(0xFFD34F, 8), NETHER_GOLD(0xFFB23D, 7), REDSTONE(0xFF4A55, 7),
        LAPIS(0x4B78FF, 6), IRON(0xE7D9C8, 5), COPPER(0xE78B54, 4),
        QUARTZ(0xF4E7DF, 3), COAL(0x747782, 2);

        private final int rgb;
        private final int priority;

        OreType(int rgb, int priority) {
            this.rgb = rgb;
            this.priority = priority;
        }

        int rgb() { return rgb; }
        int priority() { return priority; }
    }

    private record OrePosition(int x, int y, int z, OreType type) {
    }
}
