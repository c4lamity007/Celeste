package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Volt/Silk AutoRefill rebuilt as one validated player-handler quick-move per delay. */
final class AutoRefillFeature implements ModuleFeature {
    private static final int PRIORITY = 120;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private InventorySupport.ClickIntent intent;
    private ItemStack expected = ItemStack.EMPTY;

    @Override
    public String name() {
        return "Auto Refill";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        intent = null;
        expected = ItemStack.EMPTY;
        MinecraftClient client = context.client();
        if (!InventorySupport.stationaryInventoryClickAllowed(client) || client.player.age - lastActionAge
            < Math.max(1, (int) Math.round(context.slider("Delay", 3.0D)))) return;
        String mode = context.mode("Mode", "Combat");
        Slot source = findSource(client, mode);
        if (source == null) return;
        expected = source.getStack().copy();
        ItemStack snapshot = expected;
        intent = InventorySupport.captureClick(client, source.id,
            stack -> ItemStack.areItemsAndComponentsEqual(snapshot, stack) && !stack.isEmpty());
    }

    @Override
    public void onActionTick(ModuleContext context) {
        InventorySupport.ClickIntent selected = intent;
        ItemStack selectedStack = expected;
        if (selected == null || selectedStack.isEmpty()) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!context.module().enabled() || !InventorySupport.playerInventoryAvailable(client)
                || !InventorySupport.stationaryInventoryClickAllowed(client)
                || client.player.age - lastActionAge < Math.max(1, (int) Math.round(context.slider("Delay", 3.0D)))
                || !eligible(selectedStack, context.mode("Mode", "Combat"))
                || !hasDestination(client, selectedStack)) return;
            if (InventorySupport.executeClick(client, selected, 0, SlotActionType.QUICK_MOVE)) {
                lastActionAge = client.player.age;
            }
        });
    }

    private static Slot findSource(MinecraftClient client, String mode) {
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() < 9 || slot.getIndex() > 35
                || !eligible(slot.getStack(), mode) || !hasDestination(client, slot.getStack())) continue;
            return slot;
        }
        return null;
    }

    private static boolean hasDestination(MinecraftClient client, ItemStack source) {
        boolean empty = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack hotbar = client.player.getInventory().getStack(slot);
            if (hotbar.isEmpty()) empty = true;
            if (ItemStack.areItemsAndComponentsEqual(source, hotbar)
                && hotbar.getCount() < hotbar.getMaxCount()) return true;
        }
        return empty;
    }

    private static boolean eligible(ItemStack stack, String mode) {
        if (stack.isEmpty()) return false;
        boolean potion = stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION)
            || stack.isOf(Items.LINGERING_POTION);
        if (mode.equalsIgnoreCase("Potions")) return potion;
        return potion || stack.isOf(Items.END_CRYSTAL) || stack.isOf(Items.OBSIDIAN)
            || stack.isOf(Items.GLOWSTONE) || stack.isOf(Items.RESPAWN_ANCHOR)
            || stack.isOf(Items.COBWEB) || stack.isOf(Items.TOTEM_OF_UNDYING)
            || stack.isOf(Items.EXPERIENCE_BOTTLE) || stack.isOf(Items.WIND_CHARGE);
    }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        intent = null;
        expected = ItemStack.EMPTY;
        lastActionAge = Integer.MIN_VALUE / 2;
    }
}
