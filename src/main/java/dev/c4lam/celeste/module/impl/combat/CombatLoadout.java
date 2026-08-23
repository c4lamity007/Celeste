package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

import java.util.Locale;

/** Shared weapon policy so separately enabled modules never race hotbar state. */
final class CombatLoadout {
    enum Weapon { CURRENT, SWORD, AXE, MACE }

    private static int lastSwapAge = Integer.MIN_VALUE / 2;
    private static long lastMaceActionNanos = Long.MIN_VALUE;

    private CombatLoadout() {
    }

    static int attackSlot(MinecraftClient client, LivingEntity target, Weapon requested) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();

        boolean shieldRaised = target != null && target.isBlocking();
        if (shieldRaised && enabled("Shield Break")
            && (maceAuraActive() || requested == Weapon.MACE && enabled("Mace Swap"))) {
            shieldRaised = false;
        }
        if (shieldRaised && enabled("Shield Break")) {
            int axe = bestSlot(client, Weapon.AXE);
            return axe >= 0 && (axe == visible || swapDelayElapsed(client)) ? axe : -1;
        }

        if (requested == Weapon.MACE) {
            if (isWeapon(client.player.getInventory().getStack(visible), Weapon.MACE)) {
                return visible;
            }
            if (enabled("Mace Swap")) {
                return bestSlot(client, Weapon.MACE);
            }
            return -1;
        }

        if ((requested == Weapon.SWORD || requested == Weapon.CURRENT) && enabled("Auto Sword")) {
            boolean preferAxe = toggle("Auto Sword", "Prefer axe", true);
            Weapon preference;
            int slot;
            if (preferAxe) {
                int axe = bestSlot(client, Weapon.AXE);
                int sword = bestSlot(client, Weapon.SWORD);
                if (axe >= 0 && sword >= 0) {
                    preference = Weapon.AXE;
                    slot = axe;
                } else if (sword >= 0) {
                    preference = Weapon.SWORD;
                    slot = sword;
                } else {
                    preference = Weapon.AXE;
                    slot = axe;
                }
            } else {
                preference = Weapon.SWORD;
                slot = bestSlot(client, Weapon.SWORD);
                if (slot < 0) {
                    preference = Weapon.AXE;
                    slot = bestSlot(client, Weapon.AXE);
                }
            }
            return slot >= 0 && (slot == visible || swapDelayElapsed(client)) ? slot : -1;
        }

        if (requested == Weapon.AXE) {
            int axe = bestSlot(client, Weapon.AXE);
            return axe >= 0 && (axe == visible || swapDelayElapsed(client)) ? axe : -1;
        }
        if (requested == Weapon.SWORD
            && !isWeapon(client.player.getInventory().getStack(visible), Weapon.SWORD)) {
            return -1;
        }
        return visible;
    }

    /**
     * Returns the strongest hotbar instance of an explicitly requested weapon
     * without inheriting Auto Sword's normal swap-delay policy.  A stun slam is
     * a deliberate two-tick combo, so delaying its axe half would leave the
     * sequence in a half-completed state.
     */
    static int immediateWeaponSlot(MinecraftClient client, Weapon weapon) {
        return switch (weapon) {
            case AXE, MACE, SWORD -> bestSlot(client, weapon);
            case CURRENT -> client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        };
    }

    static boolean runWithSlot(Object owner, MinecraftClient client, int slot, int priority,
                               boolean silent, Runnable action) {
        if (client.player == null || slot < 0 || slot > 8) {
            return false;
        }
        return SilentSlotManager.getInstance().runWithSlot(owner, slot, priority, silent, action);
    }

    static boolean silentSwap(MinecraftClient client, LivingEntity target, Weapon requested) {
        if (client.player == null) {
            return true;
        }
        // A Mace Aura/Mace Swap action must use Mace Swap's own presentation
        // policy even when the target happens to be blocking. Shield Break is
        // a separate axe intent and must not overwrite the mace setting.
        if (requested == Weapon.MACE && enabled("Mace Swap")) {
            return maceSwapSilent();
        }
        if (target != null && target.isBlocking() && enabled("Shield Break")) {
            return toggle("Shield Break", "Silent swap", true);
        }
        if ((requested == Weapon.SWORD || requested == Weapon.CURRENT) && enabled("Auto Sword")) {
            return toggle("Auto Sword", "Silent swap", true);
        }
        if (requested == Weapon.AXE) {
            return toggle("Shield Break", "Silent swap", true);
        }
        return true;
    }

    static boolean maceSwapSilent() {
        return toggle("Mace Swap", "Silent swap", true);
    }

    static boolean maceSwapAutoStunSlam() {
        return toggle("Mace Swap", "Auto stun slam", false);
    }

    static long maceAuraCooldownMillis() {
        return Math.max(0L, Math.round(slider("Mace Aura", "Mace cooldown", 100.0D)));
    }

    /** One shared cadence for Auto Mace and Mace Swap's Stun Slam. */
    static boolean maceCooldownElapsed() {
        long delayNanos = maceAuraCooldownMillis() * 1_000_000L;
        return lastMaceActionNanos == Long.MIN_VALUE
            || System.nanoTime() - lastMaceActionNanos >= delayNanos;
    }

    static void markMaceAction() {
        lastMaceActionNanos = System.nanoTime();
    }

    static void markSwap(MinecraftClient client, boolean swapped) {
        if (swapped && client.player != null) {
            lastSwapAge = client.player.age;
        }
    }

    static int findItem(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (client.player.getInventory().getStack(visible).isOf(item)) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    static int findNonGlowstoneSlot(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (!client.player.getInventory().getStack(visible).isOf(Items.GLOWSTONE)) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (!client.player.getInventory().getStack(slot).isOf(Items.GLOWSTONE)) {
                return slot;
            }
        }
        return -1;
    }

    static boolean enabled(String moduleName) {
        GuiModule module = module(moduleName);
        return module != null && module.enabled();
    }

    private static boolean maceAuraActive() {
        GuiModule maceAura = module("Mace Aura");
        if (maceAura == null || !maceAura.enabled()) {
            return false;
        }
        return maceAura.settings().stream()
            .filter(s -> s.label().equalsIgnoreCase("Mode"))
            .filter(GuiSetting.Mode.class::isInstance)
            .map(GuiSetting.Mode.class::cast)
            .map(GuiSetting.Mode::value)
            .findFirst()
            .map(mode -> "Mace only".equals(mode) || "Weapon only".equals(mode))
            .orElse(true);
    }

    private static boolean swapDelayElapsed(MinecraftClient client) {
        if (client.player == null) {
            return false;
        }
        if (client.player.age < lastSwapAge) {
            lastSwapAge = Integer.MIN_VALUE / 2;
        }
        int delay = (int) Math.round(slider("Auto Sword", "Swap delay", 1.0D));
        return client.player.age - lastSwapAge >= Math.max(0, delay);
    }

    private static int bestSlot(MinecraftClient client, Weapon weapon) {
        if (client.player == null) {
            return -1;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        int visible = client.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!isWeapon(stack, weapon)) {
                continue;
            }
            int score = score(stack, weapon) + (slot == visible ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private static boolean isWeapon(ItemStack stack, Weapon weapon) {
        return switch (weapon) {
            case SWORD -> stack.isIn(ItemTags.SWORDS);
            case AXE -> stack.isIn(ItemTags.AXES);
            case MACE -> stack.isOf(Items.MACE);
            case CURRENT -> !stack.isEmpty();
        };
    }

    private static int score(ItemStack stack, Weapon weapon) {
        if (weapon == Weapon.MACE) return 100;
        if (stack.isOf(Items.NETHERITE_SWORD) || stack.isOf(Items.NETHERITE_AXE)) return 60;
        if (stack.isOf(Items.DIAMOND_SWORD) || stack.isOf(Items.DIAMOND_AXE)) return 50;
        if (stack.isOf(Items.IRON_SWORD) || stack.isOf(Items.IRON_AXE)) return 40;
        if (stack.isOf(Items.COPPER_SWORD) || stack.isOf(Items.COPPER_AXE)) return 35;
        if (stack.isOf(Items.STONE_SWORD) || stack.isOf(Items.STONE_AXE)) return 30;
        if (stack.isOf(Items.GOLDEN_SWORD) || stack.isOf(Items.GOLDEN_AXE)) return 20;
        if (stack.isOf(Items.WOODEN_SWORD) || stack.isOf(Items.WOODEN_AXE)) return 10;
        return 0;
    }

    private static boolean toggle(String moduleName, String label, boolean fallback) {
        GuiModule module = module(moduleName);
        if (module == null) return fallback;
        return module.settings().stream()
            .filter(setting -> setting.label().equalsIgnoreCase(label))
            .filter(GuiSetting.Toggle.class::isInstance)
            .map(GuiSetting.Toggle.class::cast)
            .map(GuiSetting.Toggle::value)
            .findFirst().orElse(fallback);
    }

    private static double slider(String moduleName, String label, double fallback) {
        GuiModule module = module(moduleName);
        if (module == null) return fallback;
        return module.settings().stream()
            .filter(setting -> setting.label().equalsIgnoreCase(label))
            .filter(GuiSetting.Slider.class::isInstance)
            .map(GuiSetting.Slider.class::cast)
            .mapToDouble(GuiSetting.Slider::value)
            .findFirst().orElse(fallback);
    }

    private static GuiModule module(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return GuiCatalog.modules().stream()
            .filter(module -> module.name().toLowerCase(Locale.ROOT).equals(key))
            .findFirst().orElse(null);
    }
}
