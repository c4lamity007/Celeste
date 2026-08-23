package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/** Silk ReBuffNotifier concept with transition-only action-bar and sound alerts. */
final class ReBuffNotifierFeature implements ModuleFeature {
    private boolean primed;
    private boolean hadSpeed;
    private boolean hadStrength;

    @Override
    public String name() {
        return "ReBuff Notifier";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (context.client().player == null) return;
        boolean speed = context.client().player.hasStatusEffect(StatusEffects.SPEED);
        boolean strength = context.client().player.hasStatusEffect(StatusEffects.STRENGTH);
        if (primed) {
            if (hadSpeed && !speed) notify(context, "Speed");
            if (hadStrength && !strength) notify(context, "Strength");
        }
        hadSpeed = speed;
        hadStrength = strength;
        primed = true;
    }

    private static void notify(ModuleContext context, String effect) {
        if (context.toggle("Action bar", true)) {
            context.client().player.sendMessage(Text.literal("Celeste: " + effect + " expired — re-buff"), true);
        }
        if (context.toggle("Sound", true)) {
            context.client().getSoundManager().play(
                PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.0F));
        }
    }

    @Override
    public void onEnable(ModuleContext context) { reset(); }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        primed = false;
        hadSpeed = false;
        hadStrength = false;
    }
}
