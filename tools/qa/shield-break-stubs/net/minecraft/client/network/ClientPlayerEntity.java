package net.minecraft.client.network;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSources;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
public final class ClientPlayerEntity extends LivingEntity {
    public boolean usingItem;
    public boolean vehicle;
    public int swings;
    public final Inventory inventory = new Inventory();
    public final DamageSources damageSources = new DamageSources();
    public boolean isUsingItem() { return usingItem; }
    public boolean hasVehicle() { return vehicle; }
    public Inventory getInventory() { return inventory; }
    public ItemStack getMainHandStack() { return inventory.getStack(inventory.selectedSlot); }
    public DamageSources getDamageSources() { return damageSources; }
    public void swingHand(Hand hand) {
        if (hand != Hand.MAIN_HAND) throw new AssertionError("Expected main-hand swing");
        swings++;
    }
    public static final class Inventory {
        public final ItemStack[] stacks = new ItemStack[9];
        public int selectedSlot;
        public Inventory() { for (int i = 0; i < stacks.length; i++) stacks[i] = new ItemStack(); }
        public ItemStack getStack(int slot) { return stacks[slot]; }
    }
}
