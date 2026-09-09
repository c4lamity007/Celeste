package dev.c4lam.celeste.module.impl.combat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
/** Slot ownership boundary: records the actual production callback's single scope. */
final class CombatLoadout {
    static int axeSlot = 2;
    static int selectedSlot = 0;
    static int scopeDepth;
    static int scopeCalls;
    static boolean allowScope = true;
    static boolean lastSilent;
    static Object lastOwner;
    static int lastPriority;
    static Runnable beforeAction = () -> {};
    static final List<String> events = new ArrayList<>();
    static int shieldBreakerSlot(MinecraftClient client) { return axeSlot; }
    static boolean runWithSlot(Object owner, MinecraftClient client, int slot, int priority,
                               boolean silent, Runnable action) {
        scopeCalls++;
        lastSilent = silent; lastOwner = owner; lastPriority = priority;
        if (!allowScope) return false;
        int original = selectedSlot;
        ClientPlayerEntity scopedPlayer = client.player;
        int originalMainHand = scopedPlayer.inventory.selectedSlot;
        scopedPlayer.inventory.selectedSlot = slot;
        selectedSlot = slot; scopeDepth++; events.add("enter:" + slot);
        try { beforeAction.run(); action.run(); }
        finally {
            scopedPlayer.inventory.selectedSlot = originalMainHand;
            scopeDepth--; selectedSlot = original; events.add("restore:" + original);
        }
        return true;
    }
    static void reset() {
        axeSlot = 2; selectedSlot = 0; scopeDepth = scopeCalls = 0;
        allowScope = true; lastSilent = false; lastOwner = null; lastPriority = 0;
        beforeAction = () -> {}; events.clear();
    }
}
