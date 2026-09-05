package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Silk ReBuffNotifier concept with transition-only action-bar and sound alerts. */
public final class ReBuffNotifierFeature implements ModuleFeature {
    private static volatile List<Notice> notices = List.of();
    private static Object noticeWorld;
    private boolean primed;
    private boolean hadSpeed;
    private boolean hadStrength;

    @Override
    public String name() {
        return "ReBuff Notifier";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (context.client().player == null || !context.client().player.isAlive()) {
            reset();
            return;
        }
        long now = System.nanoTime();
        notices = context.toggle("Visual alert", true)
            ? notices.stream().filter(notice -> notice.expiresNanos() > now).toList() : List.of();
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
        if (context.toggle("Visual alert", true)) {
            long now = System.nanoTime();
            long duration = (long) (Math.max(1D, Math.min(8D, context.slider("Alert duration", 3D)))
                * 1_000_000_000D);
            List<Notice> next = new ArrayList<>(2);
            for (Notice notice : notices) {
                if (notice.expiresNanos() > now && !notice.effect().equals(effect)) next.add(notice);
            }
            next.add(new Notice(effect, now, now + duration, "Speed".equals(effect) ? 0x86CFE9 : 0xEAA48C));
            noticeWorld = context.client().world;
            notices = List.copyOf(next);
        }
        if (context.toggle("Action bar", true)) {
            context.client().player.sendMessage(Text.literal("Celeste: " + effect + " expired — re-buff"), true);
        }
        if (context.toggle("Sound", true)) {
            context.client().getSoundManager().play(
                PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.0F));
        }
    }

    /** Transition-only visual alerts; never post additional chat or sound from a render frame. */
    public static void renderHud(MinecraftClient client, int width, int height) {
        List<Notice> current = notices;
        if (current.isEmpty() || client.player == null || !client.player.isAlive() || client.world != noticeWorld
            || client.options.hudHidden
            || (client.currentScreen != null && !(client.currentScreen instanceof SilentInventoryScreen))) return;
        long now = System.nanoTime();
        MatrixStack matrices = RenderUtil.matrices();
        matrices.push();
        matrices.loadIdentity();
        float y = Math.min(52F, height * 0.16F);
        for (Notice notice : current) {
            if (now >= notice.expiresNanos()) continue;
            float enter = clamp((now - notice.startedNanos()) / 180_000_000F);
            float leave = clamp((notice.expiresNanos() - now) / 400_000_000F);
            float alpha = smooth(enter) * smooth(leave);
            float x = width * 0.5F - 76F;
            float shownY = y - (1F - smooth(enter)) * 6F;
            RenderUtil.RECT.draw(matrices, x, shownY, 152F, 36F, 8F, color(0x191E25, alpha * 0.93F));
            RenderUtil.RECT.draw(matrices, x + 8F, shownY + 8F, 20F, 20F, 6F,
                color(notice.rgb(), alpha * 0.13F));
            String icon = "Speed".equals(notice.effect()) ? ">>" : "+";
            Fonts.semibold().drawCenteredText(matrices, icon, x + 18F, shownY + 13F, 7F,
                color(notice.rgb(), alpha));
            Fonts.semibold().draw(matrices, notice.effect() + " expired", x + 36F, shownY + 8F, 7F,
                color(0xEDF2F6, alpha));
            Fonts.regular().draw(matrices, "Reapply your effect", x + 36F, shownY + 20F, 5.4F,
                color(0x9AAAB6, alpha));
            float remaining = clamp((notice.expiresNanos() - now)
                / (float) (notice.expiresNanos() - notice.startedNanos()));
            RenderUtil.RECT.draw(matrices, x + 36F, shownY + 31F, 105F * remaining, 1.1F, 0.55F,
                color(notice.rgb(), alpha * 0.66F));
            y += 42F;
        }
        matrices.pop();
    }

    private static Color color(int rgb, float alpha) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, Math.round(clamp(alpha) * 255F));
    }

    private static float clamp(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    private static float smooth(float value) {
        return value * value * (3F - 2F * value);
    }

    private record Notice(String effect, long startedNanos, long expiresNanos, int rgb) { }

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
        notices = List.of();
        noticeWorld = null;
    }
}
