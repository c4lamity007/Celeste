package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.Blocks;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/**
 * Client-only dispenser gamble: empty dispensers look like they hold renamed 1-9
 * prizes. Activation, pickup, and putting items back never leave this client.
 * Dropped prizes use vanilla dispenser spawn + item-entity merge/physics.
 */
public final class FakeGambleFeature implements ModuleFeature {
    private static final Item[] DEFAULT_PRIZES = {
        Items.DIRT, Items.OAK_LOG, Items.OAK_PLANKS, Items.IRON_ORE, Items.IRON_BLOCK,
        Items.DIAMOND_ORE, Items.NETHERITE_SWORD, Items.TRIDENT, Items.MACE
    };
    private static final Item[] prizes = DEFAULT_PRIZES.clone();
    private static final int DISPENSE_SPEED = 6;
    private static final AtomicInteger CLIENT_IDS = new AtomicInteger(-71_000_000);

    private static volatile boolean enabled;
    private static final Map<BlockPos, ItemStack[]> dispensers = new ConcurrentHashMap<>();
    private static final ItemStack[] playerGhost = new ItemStack[36];
    private static final Set<ItemEntity> ground = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<BlockPos> pendingFires = new LinkedHashSet<>();
    private static ItemStack cursor = ItemStack.EMPTY;
    private static BlockPos openPos;
    private static BlockPos lastLooked;
    private static BlockPos lastUsed;
    private static BlockPos primedA;
    private static BlockPos primedB;
    private static double win1Percent = 50;
    private static double win2Percent = 50;
    private static String tieMode = "Reroll";
    private static boolean middleDown;
    private static volatile boolean selectionMarkers = true;
    private static volatile boolean roundDisplay = true;
    private static volatile long displayDurationNanos = 3_500_000_000L;
    private static volatile RoundResult lastRound;

    static {
        java.util.Arrays.fill(playerGhost, ItemStack.EMPTY);
    }

    @Override
    public String name() {
        return "Fake Gamble";
    }

    @Override
    public void onEnable(ModuleContext context) {
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        enabled = true;
        MinecraftClient client = context.client();
        if (client.player == null || !client.player.isAlive()) lastRound = null;
        if (client.player == null || client.world == null) {
            return;
        }
        syncConfiguredPrizes(context);
        win1Percent = context.slider("1 Win %", 50);
        win2Percent = context.slider("2 Win %", 50);
        tieMode = context.mode("Tie", "Reroll");
        selectionMarkers = context.toggle("Selection markers", true);
        roundDisplay = context.toggle("Round display", true);
        displayDurationNanos = (long) (MathHelper.clamp(context.slider("Display duration", 3.5D), 1D, 8D)
            * 1_000_000_000D);
        rememberLooked(client);
        pollMiddleClick(client);
        pruneGround();
        bindOpenDispenser(client);
        applyAll(client);
        stashCursorIfScreenClosed(client);
    }

    @Override
    public void onEndTick(ModuleContext context) {
        enabled = true;
        MinecraftClient client = context.client();
        if (client.world instanceof ClientWorld world) {
            flushPending(world);
        }
        if (client.player != null) {
            bindOpenDispenser(client);
            applyAll(client);
        }
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        resetWorldState(context.client(), true);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetWorldState(context.client(), false);
    }

    public static boolean active() {
        return enabled;
    }

    public static void noteUsedDispenser(BlockHitResult hit) {
        if (!enabled || hit == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || !client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)) {
            return;
        }
        lastUsed = hit.getBlockPos().toImmutable();
        lastLooked = lastUsed;
    }

    public static boolean onPickBlock(MinecraftClient client) {
        return primeFromLook(client);
    }

    public static void ensureScreenPrizes(Screen screen) {
        if (!enabled || !(screen instanceof Generic3x3ContainerScreen) || !isDispenserTitle(screen.getTitle())) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        bindOpenDispenser(client);
        if (client.player != null) {
            applyDispenserSlots(client.player.currentScreenHandler);
        }
    }

    public static boolean isFakeDrop(ItemEntity entity) {
        return entity != null && (ground.contains(entity) || kindOf(entity.getStack()) >= 0);
    }

    public static void clientItemTick(ItemEntity entity) {
        if (!enabled || entity == null || entity.isRemoved() || !isFakeDrop(entity)) {
            return;
        }
        if (!(entity.getEntityWorld() instanceof ClientWorld)) {
            return;
        }
        if (entity.getItemAge() >= 6000) {
            ground.remove(entity);
            entity.discard();
        }
    }

    public static int kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            return -1;
        }
        String raw = name.getString();
        if (raw.length() != 1 || raw.charAt(0) < '1' || raw.charAt(0) > '9') {
            return -1;
        }
        int kind = raw.charAt(0) - '1';
        return kind;
    }

    public static ItemStack create(int kind) {
        return create(kind, 1);
    }

    public static ItemStack create(int kind, int count) {
        if (kind < 0 || kind >= prizes.length || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(prizes[kind], count);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(Integer.toString(kind + 1)));
        return stack;
    }

    public static boolean handleClick(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player) {
        if (!enabled || player == null || !(player.getEntityWorld() instanceof ClientWorld)) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player || client.player.currentScreenHandler.syncId != syncId) {
            return false;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (kindOf(cursor) < 0 && kindOf(handler.getCursorStack()) >= 0) {
            cursor = copyOf(handler.getCursorStack());
        }
        int inv = playerInvIndex(handler, slotId);
        boolean disp = isDispenserContainerSlot(handler, slotId);
        ItemStack slotStack = ItemStack.EMPTY;
        if (disp) {
            slotStack = state(openPos)[slotId];
        } else if (inv >= 0) {
            slotStack = !playerGhost[inv].isEmpty() ? playerGhost[inv] : player.getInventory().getStack(inv);
        }
        boolean cursorPrize = kindOf(cursor) >= 0;
        boolean slotPrize = kindOf(slotStack) >= 0;
        boolean swapPrize = actionType == SlotActionType.SWAP && button >= 0 && button < 9
            && (kindOf(playerGhost[button]) >= 0 || kindOf(player.getInventory().getStack(button)) >= 0);
        if (actionType == SlotActionType.THROW && slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX && cursorPrize) {
            spawnDropped(client.world, player, copyOf(cursor));
            setCursor(handler, ItemStack.EMPTY);
            return true;
        }
        if (!cursorPrize && !slotPrize && !swapPrize) {
            return false;
        }
        return switch (actionType) {
            case PICKUP -> clickPickup(handler, player, client, slotId, inv, disp, button);
            case QUICK_MOVE -> clickQuickMove(handler, player, slotId, inv, disp);
            case SWAP -> clickSwap(handler, player, slotId, inv, disp, button);
            case THROW -> clickThrow(handler, player, client, slotId, inv, disp, button);
            case PICKUP_ALL -> clickPickupAll(handler, player);
            default -> true;
        };
    }

    public static boolean onEmptyDispenserFired(ClientWorld world, BlockPos pos) {
        if (!enabled || world == null || pos == null) {
            return false;
        }
        if (!world.getBlockState(pos).isOf(Blocks.DISPENSER)) {
            return false;
        }
        if (!hasAny(state(pos.toImmutable()))) {
            return false;
        }
        pendingFires.add(pos.toImmutable());
        if (primedA == null && primedB == null) {
            flushPending(world);
        }
        return true;
    }

    public static boolean tryPickup(ItemEntity entity, PlayerEntity player) {
        if (!enabled || entity == null || player == null || !(player.getEntityWorld() instanceof ClientWorld world)) {
            return false;
        }
        if (!isFakeDrop(entity) || entity.cannotPickup()) {
            return isFakeDrop(entity);
        }
        ItemStack stack = entity.getStack();
        if (stack.isEmpty() || kindOf(stack) < 0) {
            return false;
        }
        int before = stack.getCount();
        if (!player.getInventory().insertStack(stack)) {
            return true;
        }
        snapshotGhost(player.getInventory());
        world.playSoundClient(
            entity.getX(), entity.getY(), entity.getZ(),
            SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.2F,
            (world.getRandom().nextFloat() - world.getRandom().nextFloat()) * 1.4F + 2.0F,
            false
        );
        if (stack.isEmpty()) {
            ground.remove(entity);
            entity.discard();
        } else {
            entity.setStack(stack);
        }
        return before != stack.getCount() || stack.isEmpty();
    }

    public static void reapplyAfterSync() {
        if (!enabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            bindOpenDispenser(client);
            applyAll(client);
        }
    }

    public static void stashCursor(ScreenHandler handler, PlayerEntity player) {
        if (!enabled || handler == null || player == null) {
            return;
        }
        ItemStack held = !cursor.isEmpty() ? cursor : handler.getCursorStack();
        if (kindOf(held) < 0) {
            return;
        }
        insertIntoInventory(player, held);
        cursor = ItemStack.EMPTY;
        handler.setCursorStack(ItemStack.EMPTY);
    }

    private static void rememberLooked(MinecraftClient client) {
        if (client.currentScreen != null || !(client.crosshairTarget instanceof BlockHitResult hit) || client.world == null) {
            return;
        }
        if (client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)) {
            lastLooked = hit.getBlockPos().toImmutable();
        }
    }

    private static void pollMiddleClick(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        boolean down = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
            == GLFW.GLFW_PRESS;
        boolean pressed = down && !middleDown;
        middleDown = down;
        if (pressed) {
            primeFromLook(client);
        }
    }

    private static boolean primeFromLook(MinecraftClient client) {
        if (!enabled || client == null || client.currentScreen != null || client.world == null) {
            return false;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
            return false;
        }
        if (!client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)) {
            return false;
        }
        BlockPos pos = hit.getBlockPos().toImmutable();
        lastLooked = pos;
        if (pos.equals(primedA)) {
            primedA = primedB;
            primedB = null;
        } else if (pos.equals(primedB)) {
            primedB = null;
        } else if (primedA == null) {
            primedA = pos;
        } else if (primedB == null) {
            primedB = pos;
        } else {
            primedB = pos;
        }
        return true;
    }

    private static void bindOpenDispenser(MinecraftClient client) {
        if (client == null || !isDispenserScreen(client.currentScreen)) {
            openPos = null;
            return;
        }
        if (openPos != null) {
            state(openPos);
            return;
        }
        BlockPos candidate = lastUsed != null ? lastUsed : lastLooked;
        if (candidate == null && client.crosshairTarget instanceof BlockHitResult hit
            && client.world != null && client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)) {
            candidate = hit.getBlockPos().toImmutable();
        }
        if (candidate != null) {
            openPos = candidate.toImmutable();
            state(openPos);
        }
    }

    private static boolean isDispenserScreen(Screen screen) {
        return screen instanceof Generic3x3ContainerScreen && isDispenserTitle(screen.getTitle());
    }

    private static boolean isOpenDispenser(ScreenHandler handler) {
        return handler instanceof Generic3x3ContainerScreenHandler
            && isDispenserScreen(MinecraftClient.getInstance().currentScreen);
    }

    private static boolean isDispenserTitle(Text title) {
        if (title == null) {
            return false;
        }
        if (title.getContent() instanceof TranslatableTextContent trans
            && "container.dispenser".equals(trans.getKey())) {
            return true;
        }
        return "Dispenser".equals(title.getString());
    }

    private static ItemStack[] state(BlockPos pos) {
        return dispensers.computeIfAbsent(pos.toImmutable(), key -> freshPrizes());
    }

    private static ItemStack[] freshPrizes() {
        ItemStack[] slots = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            slots[i] = create(i);
        }
        return slots;
    }

    private static void applyAll(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        PlayerInventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            putInventoryIfNeeded(inventory, i, playerGhost[i]);
        }
        applyDispenserSlots(client.player.currentScreenHandler);
        if (!cursor.isEmpty()) {
            ItemStack held = client.player.currentScreenHandler.getCursorStack();
            if (!ItemStack.areEqual(held, cursor)) {
                client.player.currentScreenHandler.setCursorStack(copyOf(cursor));
            }
        }
    }

    private static void applyDispenserSlots(ScreenHandler handler) {
        if (!isOpenDispenser(handler) || openPos == null) {
            return;
        }
        ItemStack[] slots = state(openPos);
        if (hasForeignItems(handler, slots)) {
            return;
        }
        for (int i = 0; i < 9; i++) {
            setSlotIfNeeded(handler, i, slots[i]);
        }
    }

    private static boolean hasForeignItems(ScreenHandler handler, ItemStack[] slots) {
        for (int i = 0; i < 9; i++) {
            ItemStack current = handler.getSlot(i).getStack();
            if (!current.isEmpty() && kindOf(current) < 0 && (slots[i].isEmpty() || kindOf(slots[i]) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private static void stashCursorIfScreenClosed(MinecraftClient client) {
        if (client.currentScreen != null || cursor.isEmpty()) {
            return;
        }
        insertIntoInventory(client.player, cursor);
        cursor = ItemStack.EMPTY;
        client.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
    }

    private static void flushPending(ClientWorld world) {
        if (pendingFires.isEmpty()) {
            return;
        }
        List<BlockPos> batch = new ArrayList<>(pendingFires);
        pendingFires.clear();
        Random random = world.getRandom();
        boolean pairFired = primedA != null && primedB != null
            && containsPos(batch, primedA) && containsPos(batch, primedB);
        if (pairFired) {
            boolean firstWins = decideFirstWins(random);
            ItemStack[] firstSlots = state(primedA);
            ItemStack[] secondSlots = state(primedB);
            int[] picks = pickByWinner(firstSlots, secondSlots, random, firstWins);
            int firstNumber = prizeNumber(firstSlots, picks[0]);
            int secondNumber = prizeNumber(secondSlots, picks[1]);
            ejectSlot(world, primedA, picks[0]);
            ejectSlot(world, primedB, picks[1]);
            if (firstNumber > 0 && secondNumber > 0) {
                lastRound = new RoundResult(world, firstNumber, secondNumber, System.nanoTime());
            }
            for (BlockPos pos : batch) {
                if (!pos.equals(primedA) && !pos.equals(primedB)) {
                    ejectVanilla(world, pos, random);
                }
            }
            primedA = null;
            primedB = null;
            return;
        }
        for (BlockPos pos : batch) {
            ejectVanilla(world, pos, random);
        }
    }

    private static boolean decideFirstWins(Random random) {
        double w1 = Math.max(0, Math.min(100, win1Percent)) / 100.0;
        double w2 = Math.max(0, Math.min(100, win2Percent)) / 100.0;
        if (w1 + w2 > 1.0) {
            double sum = w1 + w2;
            w1 /= sum;
            w2 /= sum;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            double roll = random.nextDouble();
            if (roll < w1) {
                return true;
            }
            if (roll < w1 + w2) {
                return false;
            }
            if ("Reroll".equalsIgnoreCase(tieMode)) {
                continue;
            }
            return "Win".equalsIgnoreCase(tieMode);
        }
        return w1 >= w2;
    }

    private static int[] pickByWinner(ItemStack[] first, ItemStack[] second, Random random, boolean firstWins) {
        int chosenA = -1;
        int chosenB = -1;
        int seen = 1;
        for (int i = 0; i < 9; i++) {
            if (first[i].isEmpty()) {
                continue;
            }
            int firstNum = prizeNumber(first, i);
            for (int j = 0; j < 9; j++) {
                if (second[j].isEmpty()) {
                    continue;
                }
                int secondNum = prizeNumber(second, j);
                boolean ok = firstWins ? firstNum > secondNum : secondNum > firstNum;
                if (ok && random.nextInt(seen++) == 0) {
                    chosenA = i;
                    chosenB = j;
                }
            }
        }
        if (chosenA < 0 || chosenB < 0) {
            return new int[] {
                chooseNonEmpty(first, random, k -> true),
                chooseNonEmpty(second, random, k -> true)
            };
        }
        return new int[] {chosenA, chosenB};
    }

    private static void ejectVanilla(ClientWorld world, BlockPos pos, Random random) {
        ejectSlot(world, pos, chooseNonEmpty(state(pos), random, k -> true));
    }

    private static void ejectSlot(ClientWorld world, BlockPos pos, int slot) {
        if (slot < 0) {
            return;
        }
        ItemStack[] slots = state(pos);
        if (slot >= 9 || slots[slot].isEmpty()) {
            return;
        }
        ItemStack ejected = slots[slot].split(1);
        if (slots[slot].isEmpty()) {
            slots[slot] = ItemStack.EMPTY;
        }
        spawnDispensed(world, pos, ejected);
        MinecraftClient client = MinecraftClient.getInstance();
        if (openPos != null && openPos.equals(pos) && client.player != null) {
            setSlotIfNeeded(client.player.currentScreenHandler, slot, slots[slot]);
        }
    }

    /**
     * Vanilla {@code DispenserBlockEntity.chooseNonEmptySlot}: reservoir sampling
     * with {@code Random.nextInt}.
     */
    private static int chooseNonEmpty(ItemStack[] slots, Random random, IntPredicate extra) {
        int chosen = -1;
        int seen = 1;
        for (int i = 0; i < 9; i++) {
            if (!slots[i].isEmpty() && extra.test(i) && random.nextInt(seen++) == 0) {
                chosen = i;
            }
        }
        return chosen;
    }

    private static int prizeNumber(ItemStack[] slots, int slot) {
        if (slot < 0 || slot >= 9) {
            return 0;
        }
        int kind = kindOf(slots[slot]);
        return kind < 0 ? 0 : kind + 1;
    }

    private static int maxPrize(ItemStack[] slots) {
        int max = 0;
        for (int i = 0; i < 9; i++) {
            max = Math.max(max, prizeNumber(slots, i));
        }
        return max;
    }

    private static boolean hasAny(ItemStack[] slots) {
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPos(List<BlockPos> batch, BlockPos pos) {
        for (BlockPos other : batch) {
            if (other.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos nearestOther(List<BlockPos> batch, BlockPos primedPos) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos pos : batch) {
            if (pos.equals(primedPos)) {
                continue;
            }
            int dist = pos.getManhattanDistance(primedPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    private static void spawnDispensed(ClientWorld world, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Direction side = world.getBlockState(pos).isOf(Blocks.DISPENSER)
            ? world.getBlockState(pos).get(DispenserBlock.FACING)
            : Direction.NORTH;
        Vec3d center = pos.toCenterPos();
        double x = center.x + 0.7 * side.getOffsetX();
        double y = center.y + 0.7 * side.getOffsetY();
        double z = center.z + 0.7 * side.getOffsetZ();
        if (side.getAxis() == Direction.Axis.Y) {
            y -= 0.125;
        } else {
            y -= 0.15625;
        }
        ItemEntity entity = new ItemEntity(world, x, y, z, stack);
        Random random = world.getRandom();
        double g = random.nextDouble() * 0.1 + 0.2;
        entity.setVelocity(
            random.nextTriangular(side.getOffsetX() * g, 0.0172275 * DISPENSE_SPEED),
            random.nextTriangular(0.2, 0.0172275 * DISPENSE_SPEED),
            random.nextTriangular(side.getOffsetZ() * g, 0.0172275 * DISPENSE_SPEED)
        );
        addDrop(world, entity);
    }

    private static void spawnDropped(World world, PlayerEntity player, ItemStack stack) {
        if (!(world instanceof ClientWorld clientWorld) || stack.isEmpty()) {
            return;
        }
        ItemEntity entity = new ItemEntity(
            clientWorld,
            player.getX(),
            player.getEyeY() - 0.3,
            player.getZ(),
            stack
        );
        entity.setPickupDelay(40);
        addDrop(clientWorld, entity);
    }

    private static void addDrop(ClientWorld world, ItemEntity entity) {
        entity.setId(CLIENT_IDS.getAndDecrement());
        world.addEntity(entity);
        ground.add(entity);
    }

    private static void insertIntoInventory(PlayerEntity player, ItemStack moving) {
        if (moving.isEmpty()) {
            return;
        }
        player.getInventory().insertStack(moving);
        snapshotGhost(player.getInventory());
        if (!moving.isEmpty() && player.getEntityWorld() instanceof ClientWorld clientWorld) {
            spawnDropped(clientWorld, player, moving.copyAndEmpty());
        }
    }

    private static void insertIntoDispenser(ItemStack[] slots, ItemStack moving) {
        for (int i = 0; i < 9 && !moving.isEmpty(); i++) {
            if (!slots[i].isEmpty()
                && ItemStack.areItemsAndComponentsEqual(slots[i], moving)
                && slots[i].getCount() < slots[i].getMaxCount()) {
                int n = Math.min(moving.getCount(), slots[i].getMaxCount() - slots[i].getCount());
                slots[i].increment(n);
                moving.decrement(n);
            }
        }
        for (int i = 0; i < 9 && !moving.isEmpty(); i++) {
            if (slots[i].isEmpty()) {
                slots[i] = moving.split(moving.getMaxCount());
            }
        }
    }

    private static void snapshotGhost(PlayerInventory inventory) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (kindOf(stack) >= 0) {
                playerGhost[i] = stack.copy();
            } else if (kindOf(playerGhost[i]) >= 0 && stack.isEmpty()) {
                playerGhost[i] = ItemStack.EMPTY;
            }
        }
    }

    private static void putInventoryIfNeeded(PlayerInventory inventory, int slot, ItemStack ghost) {
        if (ghost.isEmpty()) {
            return;
        }
        ItemStack current = inventory.getStack(slot);
        if (isReal(current)) {
            playerGhost[slot] = ItemStack.EMPTY;
            return;
        }
        if (ItemStack.areEqual(current, ghost)) {
            return;
        }
        inventory.setStack(slot, copyOf(ghost));
    }

    private static void setSlotIfNeeded(ScreenHandler handler, int slot, ItemStack wanted) {
        if (slot < 0 || slot >= handler.slots.size()) {
            return;
        }
        ItemStack current = handler.getSlot(slot).getStack();
        ItemStack target = wanted == null ? ItemStack.EMPTY : wanted;
        if (ItemStack.areEqual(current, target)) {
            return;
        }
        handler.getSlot(slot).setStack(copyOf(target));
    }

    private static void refreshHandlerInventory(ScreenHandler handler, PlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            int slotId = i < 9 ? i + 36 : i;
            setSlotIfNeeded(handler, slotId, player.getInventory().getStack(i));
        }
    }

    private static void rightClickSlot(ItemStack[] slots, int slotId, ScreenHandler handler) {
        ItemStack slot = slots[slotId];
        if (cursor.isEmpty()) {
            if (slot.isEmpty()) {
                return;
            }
            int take = MathHelper.ceil(slot.getCount() / 2.0F);
            cursor = slot.split(take);
            if (slot.isEmpty()) {
                slots[slotId] = ItemStack.EMPTY;
            }
        } else if (slot.isEmpty()) {
            slots[slotId] = cursor.split(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(slot, cursor) && slot.getCount() < slot.getMaxCount()) {
            slot.increment(1);
            cursor.decrement(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        }
        setSlotIfNeeded(handler, slotId, slots[slotId]);
        handler.setCursorStack(copyOf(cursor));
    }

    private static void rightClickInventory(int inv, ScreenHandler handler, PlayerEntity player) {
        ItemStack slot = playerGhost[inv];
        if (cursor.isEmpty()) {
            if (slot.isEmpty()) {
                return;
            }
            int take = MathHelper.ceil(slot.getCount() / 2.0F);
            cursor = slot.split(take);
            if (slot.isEmpty()) {
                playerGhost[inv] = ItemStack.EMPTY;
            }
        } else if (slot.isEmpty()) {
            playerGhost[inv] = cursor.split(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(slot, cursor) && slot.getCount() < slot.getMaxCount()) {
            slot.increment(1);
            cursor.decrement(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        }
        putInventoryIfNeeded(player.getInventory(), inv, playerGhost[inv]);
        handler.setCursorStack(copyOf(cursor));
    }

    private static boolean clickPickup(ScreenHandler handler, PlayerEntity player, MinecraftClient client,
                                       int slotId, int inv, boolean disp, int button) {
        if (slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX && kindOf(cursor) >= 0) {
            spawnDropped(client.world, player, copyOf(cursor));
            setCursor(handler, ItemStack.EMPTY);
            return true;
        }
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (button == 1) {
                rightClickSlot(slots, slotId, handler);
                return true;
            }
            if (!canSwap(slots[slotId], cursor)) {
                return true;
            }
            ItemStack was = slots[slotId];
            slots[slotId] = copyOf(cursor);
            setCursor(handler, was);
            setSlotIfNeeded(handler, slotId, slots[slotId]);
            return true;
        }
        if (inv >= 0) {
            if (button == 1) {
                rightClickInventory(inv, handler, player);
                return true;
            }
            ItemStack was = copyOf(playerGhost[inv]);
            if (was.isEmpty()) {
                was = copyOf(player.getInventory().getStack(inv));
            }
            if (!canSwap(was, cursor)) {
                return true;
            }
            setPlayerSlot(player, inv, cursor);
            setCursor(handler, was);
            setSlotIfNeeded(handler, slotId, playerGhost[inv]);
            return true;
        }
        return true;
    }

    private static boolean clickQuickMove(ScreenHandler handler, PlayerEntity player, int slotId, int inv, boolean disp) {
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!slots[slotId].isEmpty()) {
                ItemStack moving = slots[slotId];
                insertIntoInventory(player, moving);
                if (moving.isEmpty()) {
                    slots[slotId] = ItemStack.EMPTY;
                }
                setSlotIfNeeded(handler, slotId, slots[slotId]);
                refreshHandlerInventory(handler, player);
            }
            return true;
        }
        if (inv < 0 || kindOf(playerGhost[inv]) < 0) {
            return true;
        }
        ItemStack moving = copyOf(playerGhost[inv]);
        if (isOpenDispenser(handler) && openPos != null) {
            insertIntoDispenser(state(openPos), moving);
            applyDispenserSlots(handler);
        }
        if (!moving.isEmpty()) {
            insertIntoPlayerRegion(player, moving, inv < 9);
        }
        setPlayerSlot(player, inv, moving);
        snapshotGhost(player.getInventory());
        refreshHandlerInventory(handler, player);
        return true;
    }

    private static boolean clickSwap(ScreenHandler handler, PlayerEntity player, int slotId, int inv, boolean disp, int hotbar) {
        if (hotbar < 0 || hotbar > 8) {
            return true;
        }
        ItemStack hot = copyOf(playerGhost[hotbar]);
        if (hot.isEmpty()) {
            hot = copyOf(player.getInventory().getStack(hotbar));
        }
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!canSwap(slots[slotId], hot)) {
                return true;
            }
            ItemStack was = slots[slotId];
            slots[slotId] = copyOf(hot);
            setPlayerSlot(player, hotbar, was);
            setSlotIfNeeded(handler, slotId, slots[slotId]);
            refreshHandlerInventory(handler, player);
            return true;
        }
        if (inv >= 0 && inv != hotbar) {
            ItemStack hovered = copyOf(playerGhost[inv]);
            if (hovered.isEmpty()) {
                hovered = copyOf(player.getInventory().getStack(inv));
            }
            if (!canSwap(hovered, hot)) {
                return true;
            }
            setPlayerSlot(player, inv, hot);
            setPlayerSlot(player, hotbar, hovered);
            refreshHandlerInventory(handler, player);
        }
        return true;
    }

    private static boolean clickThrow(ScreenHandler handler, PlayerEntity player, MinecraftClient client,
                                      int slotId, int inv, boolean disp, int button) {
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!slots[slotId].isEmpty()) {
                ItemStack dropped = button == 1 ? slots[slotId].copy() : slots[slotId].split(1);
                if (slots[slotId].isEmpty()) {
                    slots[slotId] = ItemStack.EMPTY;
                }
                spawnDropped(client.world, player, dropped);
                setSlotIfNeeded(handler, slotId, slots[slotId]);
            }
            return true;
        }
        if (inv >= 0 && kindOf(playerGhost[inv]) >= 0) {
            ItemStack stack = playerGhost[inv];
            ItemStack dropped = button == 1 ? stack.copy() : stack.split(1);
            if (stack.isEmpty()) {
                setPlayerSlot(player, inv, ItemStack.EMPTY);
            } else {
                setPlayerSlot(player, inv, stack);
            }
            spawnDropped(client.world, player, dropped);
            refreshHandlerInventory(handler, player);
        }
        return true;
    }

    private static boolean clickPickupAll(ScreenHandler handler, PlayerEntity player) {
        if (kindOf(cursor) < 0) {
            return true;
        }
        int kind = kindOf(cursor);
        int room = cursor.getMaxCount() - cursor.getCount();
        if (room <= 0) {
            return true;
        }
        if (isOpenDispenser(handler) && openPos != null) {
            ItemStack[] slots = state(openPos);
            for (int i = 0; i < 9 && room > 0; i++) {
                if (kindOf(slots[i]) == kind) {
                    int n = Math.min(room, slots[i].getCount());
                    cursor.increment(n);
                    slots[i].decrement(n);
                    if (slots[i].isEmpty()) {
                        slots[i] = ItemStack.EMPTY;
                    }
                    room -= n;
                }
            }
            applyDispenserSlots(handler);
        }
        for (int i = 0; i < 36 && room > 0; i++) {
            if (kindOf(playerGhost[i]) == kind) {
                int n = Math.min(room, playerGhost[i].getCount());
                cursor.increment(n);
                ItemStack leftover = copyOf(playerGhost[i]);
                leftover.decrement(n);
                setPlayerSlot(player, i, leftover);
                room -= n;
            }
        }
        setCursor(handler, cursor);
        refreshHandlerInventory(handler, player);
        return true;
    }

    private static void insertIntoPlayerRegion(PlayerEntity player, ItemStack moving, boolean fromHotbar) {
        int start = fromHotbar ? 9 : 0;
        int end = fromHotbar ? 36 : 9;
        for (int pass = 0; pass < 2 && !moving.isEmpty(); pass++) {
            for (int i = start; i < end && !moving.isEmpty(); i++) {
                ItemStack existing = playerGhost[i].isEmpty() ? player.getInventory().getStack(i) : playerGhost[i];
                if (pass == 0) {
                    if (kindOf(existing) >= 0 && ItemStack.areItemsAndComponentsEqual(existing, moving)
                        && existing.getCount() < existing.getMaxCount()) {
                        int n = Math.min(moving.getCount(), existing.getMaxCount() - existing.getCount());
                        existing.increment(n);
                        moving.decrement(n);
                        setPlayerSlot(player, i, existing);
                    }
                } else if (existing.isEmpty()) {
                    setPlayerSlot(player, i, moving.split(moving.getMaxCount()));
                }
            }
        }
    }

    private static void setPlayerSlot(PlayerEntity player, int inv, ItemStack stack) {
        ItemStack prize = kindOf(stack) >= 0 ? copyOf(stack) : ItemStack.EMPTY;
        playerGhost[inv] = prize;
        ItemStack current = player.getInventory().getStack(inv);
        if (prize.isEmpty()) {
            if (kindOf(current) >= 0) {
                player.getInventory().setStack(inv, ItemStack.EMPTY);
            }
        } else {
            player.getInventory().setStack(inv, copyOf(prize));
        }
    }

    private static void setCursor(ScreenHandler handler, ItemStack stack) {
        cursor = stack == null || stack.isEmpty() ? ItemStack.EMPTY : copyOf(stack);
        handler.setCursorStack(copyOf(cursor));
    }

    private static boolean canSwap(ItemStack a, ItemStack b) {
        return !isReal(a) && !isReal(b);
    }

    private static boolean isReal(ItemStack stack) {
        return stack != null && !stack.isEmpty() && kindOf(stack) < 0;
    }

    private static int playerInvIndex(ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return -1;
        }
        Slot slot = handler.getSlot(slotId);
        if (!(slot.inventory instanceof PlayerInventory)) {
            return -1;
        }
        int index = slot.getIndex();
        return index >= 0 && index < 36 ? index : -1;
    }

    private static boolean isDispenserContainerSlot(ScreenHandler handler, int slotId) {
        return isOpenDispenser(handler) && openPos != null && slotId >= 0 && slotId < 9
            && slotId < handler.slots.size()
            && !(handler.getSlot(slotId).inventory instanceof PlayerInventory);
    }

    private static void syncConfiguredPrizes(ModuleContext context) {
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            String fallback = Registries.ITEM.getId(DEFAULT_PRIZES[i]).toString();
            Item item = resolveItem(context.itemPick(Integer.toString(i + 1), fallback), DEFAULT_PRIZES[i]);
            if (item != prizes[i]) {
                prizes[i] = item;
                changed = true;
            }
        }
        if (changed) {
            restylePrizes();
        }
    }

    private static Item resolveItem(String id, Item fallback) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return fallback;
        }
        Item item = Registries.ITEM.get(identifier);
        return item == Items.AIR ? fallback : item;
    }

    private static void restylePrizes() {
        for (ItemStack[] slots : dispensers.values()) {
            for (int i = 0; i < 9; i++) {
                int kind = kindOf(slots[i]);
                if (kind >= 0) {
                    slots[i] = create(kind, slots[i].getCount());
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            int kind = kindOf(playerGhost[i]);
            if (kind >= 0) {
                playerGhost[i] = create(kind, playerGhost[i].getCount());
            }
        }
        int cursorKind = kindOf(cursor);
        if (cursorKind >= 0) {
            cursor = create(cursorKind, cursor.getCount());
        }
    }

    private static ItemStack copyOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static void pruneGround() {
        ground.removeIf(entity -> entity == null || entity.isRemoved() || entity.getStack().isEmpty());
    }

    /** Bounded presentation of existing local selections; does not inspect or create prize slots. */
    static void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                        List<VisualExpansionRenderer.WorldLineStrip> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || !selectionMarkers || client.player == null || !client.player.isAlive()
            || client.world != context.world() || client.options.hudHidden) return;
        BlockPos first = primedA;
        BlockPos second = primedB;
        if (first != null) selection(first, 0x80CEE5, client, boxes, lines);
        if (second != null) selection(second, 0xE7A3CD, client, boxes, lines);
    }

    private static void selection(BlockPos pos, int rgb, MinecraftClient client,
                                  List<VisualExpansionRenderer.WorldBox> boxes,
                                  List<VisualExpansionRenderer.WorldLineStrip> lines) {
        if (!client.world.getBlockState(pos).isOf(Blocks.DISPENSER)
            || client.player.squaredDistanceTo(pos.toCenterPos()) > 4096D) return;
        double phase = System.nanoTime() / 1_000_000_000D;
        float pulse = (float) (0.68D + 0.15D * Math.sin(phase * 2.4D));
        double x = pos.getX();
        double y = pos.getY() + 1.014D;
        double z = pos.getZ();
        boxes.add(new VisualExpansionRenderer.WorldBox(
            new Box(x - 0.018D, y, z - 0.018D, x + 1.018D, y + 0.012D, z + 1.018D),
            VisualExpansionRenderer.argb(0.08F, rgb), 0, 1F));
        for (int index = 0; index < 4; index++) {
            double cornerX = x + (index == 1 || index == 2 ? 1.03D : -0.03D);
            double cornerZ = z + (index >= 2 ? 1.03D : -0.03D);
            double dx = index == 1 || index == 2 ? -0.24D : 0.24D;
            double dz = index >= 2 ? -0.24D : 0.24D;
            List<Vec3d> corner = List.of(new Vec3d(cornerX + dx, y + 0.014D, cornerZ),
                new Vec3d(cornerX, y + 0.014D, cornerZ), new Vec3d(cornerX, y + 0.014D, cornerZ + dz));
            lines.add(new VisualExpansionRenderer.WorldLineStrip(corner,
                VisualExpansionRenderer.argb(pulse * 0.15F, rgb), 5F));
            lines.add(new VisualExpansionRenderer.WorldLineStrip(corner,
                VisualExpansionRenderer.argb(pulse, rgb), 1.5F));
        }
    }

    public static void renderHud(MinecraftClient client, int width, int height) {
        if (!enabled || !roundDisplay || client.player == null || !client.player.isAlive()
            || client.world == null || client.options.hudHidden
            || (client.currentScreen != null && !(client.currentScreen instanceof SilentInventoryScreen))) return;
        int primed = (primedA == null ? 0 : 1) + (primedB == null ? 0 : 1);
        RoundResult result = lastRound;
        long now = System.nanoTime();
        boolean showResult = primed == 0 && result != null && result.world() == client.world
            && now - result.startedNanos() < displayDurationNanos;
        if (primed == 0 && !showResult) return;
        float alpha = 1F;
        if (showResult) {
            float enter = MathHelper.clamp((now - result.startedNanos()) / 160_000_000F, 0F, 1F);
            float leave = MathHelper.clamp((displayDurationNanos - (now - result.startedNanos())) / 450_000_000F, 0F, 1F);
            alpha = enter * enter * (3F - 2F * enter) * leave * leave * (3F - 2F * leave);
        }
        float x = Math.max(8F, width - 160F);
        float y = Math.max(8F, height - 126F) + (1F - alpha) * 5F;
        MatrixStack matrices = RenderUtil.matrices();
        matrices.push();
        matrices.loadIdentity();
        RenderUtil.RECT.draw(matrices, x, y, 148F, 55F, 8F, tint(0x1A1D25, alpha * 0.93F));
        Fonts.regular().draw(matrices, showResult ? "LOCAL ROUND" : "LOCAL DISPENSERS", x + 10F, y + 7F,
            5F, tint(0x8F9EAB, alpha));
        if (showResult) {
            boolean tie = result.first() == result.second();
            int winner = tie ? 0 : result.first() > result.second() ? 1 : 2;
            resultValue(matrices, x + 10F, y + 18F, "A", result.first(), 0x80CEE5, winner == 1, alpha);
            resultValue(matrices, x + 82F, y + 18F, "B", result.second(), 0xE7A3CD, winner == 2, alpha);
            Fonts.regular().draw(matrices, tie ? "Equal result" : winner == 1 ? "A has the higher prize" : "B has the higher prize",
                x + 10F, y + 43F, 4.8F, tint(0xADB9C4, alpha));
        } else {
            for (int index = 0; index < 2; index++) {
                float cx = x + 10F + index * 23F;
                int rgb = index == 0 ? 0x80CEE5 : 0xE7A3CD;
                boolean selected = index == 0 ? primedA != null : primedB != null;
                RenderUtil.RECT.draw(matrices, cx, y + 21F, 17F, 17F, 5F, tint(rgb, selected ? 0.18F : 0.05F));
                Fonts.semibold().drawCenteredText(matrices, index == 0 ? "A" : "B", cx + 8.5F, y + 26F,
                    6F, tint(rgb, selected ? 1F : 0.25F));
            }
            Fonts.semibold().draw(matrices, primed == 2 ? "Pair ready" : "One selected", x + 62F, y + 22F,
                6.5F, tint(0xE4ECF2, alpha));
            Fonts.regular().draw(matrices, primed == 2 ? "Trigger both dispensers" : "Middle-click a second dispenser",
                x + 10F, y + 43F, 4.8F, tint(0xA3B2BE, alpha));
        }
        matrices.pop();
    }

    private static void resultValue(MatrixStack matrices, float x, float y, String label, int value,
                                    int rgb, boolean higher, float alpha) {
        Fonts.regular().draw(matrices, label, x, y + 4F, 5.5F, tint(rgb, alpha * 0.8F));
        Fonts.semibold().draw(matrices, Integer.toString(value), x + 13F, y, 12F,
            tint(higher ? 0xEDF6F9 : rgb, alpha));
        if (higher) RenderUtil.RECT.draw(matrices, x + 34F, y + 6F, 3.3F, 3.3F, 1.65F, tint(rgb, alpha));
    }

    private static Color tint(int rgb, float opacity) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255,
            MathHelper.clamp(Math.round(opacity * 255F), 0, 255));
    }

    private record RoundResult(Object world, int first, int second, long startedNanos) { }

    private static void resetWorldState(MinecraftClient client, boolean wipePlayerVisuals) {
        if (wipePlayerVisuals && client != null && client.player != null) {
            for (int i = 0; i < 36; i++) {
                if (!playerGhost[i].isEmpty()) {
                    client.player.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
            client.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            if (isOpenDispenser(client.player.currentScreenHandler)) {
                for (int i = 0; i < 9; i++) {
                    client.player.currentScreenHandler.getSlot(i).setStack(ItemStack.EMPTY);
                }
            }
        }
        for (ItemEntity entity : List.copyOf(ground)) {
            entity.discard();
        }
        ground.clear();
        dispensers.clear();
        pendingFires.clear();
        java.util.Arrays.fill(playerGhost, ItemStack.EMPTY);
        cursor = ItemStack.EMPTY;
        openPos = null;
        lastLooked = null;
        lastUsed = null;
        primedA = null;
        primedB = null;
        middleDown = false;
        lastRound = null;
    }
}
