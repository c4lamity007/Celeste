package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

enum FrostedOption {
    GLASSY("Glassy", "Amount of the world visible in glass", InterfaceConfig.MIN_GLASSY, InterfaceConfig.MAX_GLASSY) {
        @Override float value() { return InterfaceConfig.glassy(); }
        @Override void set(double value) { InterfaceConfig.setGlassy(value); }
    },
    PASSES("Passes", "Blur quality and GPU workload", InterfaceConfig.MIN_PASSES, InterfaceConfig.MAX_PASSES) {
        @Override float value() { return InterfaceConfig.passes(); }
        @Override void set(double value) { InterfaceConfig.setPasses(value); }
    },
    OFFSET("Offset", "Width of the frosted blur spread", InterfaceConfig.MIN_OFFSET, InterfaceConfig.MAX_OFFSET) {
        @Override float value() { return InterfaceConfig.offset(); }
        @Override void set(double value) { InterfaceConfig.setOffset(value); }
    };
    final String label;
    final String description;
    final float minimum;
    final float maximum;
    FrostedOption(String label, String description, float minimum, float maximum) {
        this.label = label;
        this.description = description;
        this.minimum = minimum;
        this.maximum = maximum;
    }
    abstract float value();
    abstract void set(double value);
}

final class SpringAnimation {
    private boolean initialized;
    private float value;
    private float velocity;
    private long lastUpdateAt;
    float update(float target, float smoothTimeSeconds) {
        long now = System.nanoTime();
        if (!initialized) {
            initialized = true;
            value = target;
            lastUpdateAt = now;
            return value;
        }
        float deltaSeconds = CelesteScreenTypes.clamp((now - lastUpdateAt) / 1_000_000_000.0f, 0.0f, 0.05f);
        lastUpdateAt = now;
        if (deltaSeconds <= 0.0f) {
            return value;
        }
        float omega = 2.0f / Math.max(0.001f, smoothTimeSeconds);
        float x = omega * deltaSeconds;
        float decay = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = value - target;
        float temporary = (velocity + omega * change) * deltaSeconds;
        velocity = (velocity - omega * temporary) * decay;
        value = target + (change + temporary) * decay;
        if (Math.abs(value - target) < 0.0001f && Math.abs(velocity) < 0.0001f) {
            value = target;
            velocity = 0.0f;
        }
        return CelesteScreenTypes.clamp(value, 0.0f, 1.0f);
    }
}

final class TimedAnimation {
    private boolean initialized;
    private float start;
    private float value;
    private float target;
    private long startedAt;
    TimedAnimation() {}
    TimedAnimation(float initialValue) {
        initialized = true;
        start = initialValue;
        value = initialValue;
        target = initialValue;
        startedAt = System.nanoTime();
    }
    float update(float requestedTarget, long durationNanos) {
        long now = System.nanoTime();
        if (!initialized) {
            initialized = true;
            start = requestedTarget;
            value = requestedTarget;
            target = requestedTarget;
            startedAt = now;
            return value;
        }
        value = sample(now, durationNanos);
        if (Float.compare(requestedTarget, target) != 0) {
            start = value;
            target = requestedTarget;
            startedAt = now;
        }
        value = sample(now, durationNanos);
        return value;
    }
    private float sample(long now, long durationNanos) {
        float elapsed = CelesteScreenTypes.clamp((now - startedAt) / (float) durationNanos, 0, 1);
        float eased = 1.0f - (float) Math.pow(1.0f - elapsed, 3);
        return start + (target - start) * eased;
    }
}

record NavHit(CelestePage page, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record ModuleHit(GuiModule module, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record SettingHit(GuiSetting setting, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record ConfigHit(FrostedOption option, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record FriendHit(FriendManager.Friend friend, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record FieldHit(float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record ActionHit(String action, String tooltip, boolean helpOnly, float x, float y, float width, float height) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
}
record ProfileHit(String name, float x, float y, float width, float height,
        float deleteX, float deleteY, float deleteWidth, float deleteHeight) {
    boolean contains(double mouseX, double mouseY) { return CelesteScreenTypes.hovered(mouseX, mouseY, x, y, width, height); }
    boolean containsDelete(double mouseX, double mouseY) {
        return deleteWidth > 0 && CelesteScreenTypes.hovered(mouseX, mouseY, deleteX, deleteY, deleteWidth, deleteHeight);
    }
}
record ConfigAction(String id, String label, String help) {}

final class CelesteScreenTypes {
    private CelesteScreenTypes() {}
    static float centeredTextY(float y, float height, float fontSize) {
        return y + (height - fontSize) * 0.5f - 0.2f;
    }
    static boolean pulse() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }
    static String keyName(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return "Unbound";
        }
        return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
    }
    static String sliderValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
    static String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "...";
    }
    static boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }
    static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
    static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }
    static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
    static Color blend(Color from, Color to, float progress) {
        float t = clamp(progress, 0, 1);
        return new Color(
            Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
            Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t)
        );
    }
    static List<String> wrapTooltip(String text, float font, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Fonts.regular().getWidth(next, font) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
