package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Volt/Cyemer hover-to-equip behavior with stable-hover and exact-slot revalidation. */
final class HoverTotemFeature implements ModuleFeature {
    private static final int PRIORITY = 210;
    private int hoveredSlotId = -1;
    private int hoverTicks;
    private InventorySupport.ClickIntent intent;
    private SlotActionType actionType;
    private int button;

    @Override
    public String name() {
        return "Hover Totem";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        intent = null;
        var client = context.client();
        if (!(client.currentScreen instanceof InventoryScreen) || !InventorySupport.playerInventoryAvailable(client)) {
            resetHover();
            return;
        }
        Slot focused = InventorySupport.focusedSlot(client);
        if (focused == null || focused.inventory != client.player.getInventory()
            || focused.getIndex() < 0 || focused.getIndex() > 35
            || !focused.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            resetHover();
            return;
        }
        if (hoveredSlotId != focused.id) {
            hoveredSlotId = focused.id;
            hoverTicks = 0;
        } else {
            hoverTicks++;
        }
        int delay = Math.max(0, (int) Math.round(context.slider("Delay", 4.0D)));
        if (hoverTicks < delay) return;

        boolean needsOffhand = !client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
            && (client.player.getOffHandStack().isEmpty() || context.toggle("Replace offhand", false));
        if (needsOffhand) {
            actionType = SlotActionType.SWAP;
            button = 40;
        } else if (focused.getIndex() >= 9 && InventorySupport.hotbarSlot(client, Items.TOTEM_OF_UNDYING) < 0) {
            actionType = SlotActionType.QUICK_MOVE;
            button = 0;
        } else {
            return;
        }
        intent = InventorySupport.captureClick(client, focused.id, stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
    }

    @Override
    public void onActionTick(ModuleContext context) {
        InventorySupport.ClickIntent selected = intent;
        SlotActionType selectedType = actionType;
        int selectedButton = button;
        if (selected == null) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            Slot focused = InventorySupport.focusedSlot(client);
            if (!(client.currentScreen instanceof InventoryScreen) || focused == null
                || focused.id != selected.slotId()) return;
            if (InventorySupport.executeClick(client, selected, selectedButton, selectedType)) {
                resetHover();
            }
        });
    }

    @Override
    public void onDisable(ModuleContext context) { resetHover(); }

    private void resetHover() {
        hoveredSlotId = -1;
        hoverTicks = 0;
        intent = null;
    }
}
