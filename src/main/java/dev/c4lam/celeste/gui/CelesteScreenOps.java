package dev.c4lam.celeste.gui;

import static dev.c4lam.celeste.gui.CelesteScreenTypes.clamp;

import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

final class CelesteScreenOps {
    private CelesteScreenOps() {}

    static void layout(CelesteScreen s) {
        float minecraftScale = s.client == null ? s.REFERENCE_MINECRAFT_GUI_SCALE : (float) s.client.getWindow().getScaleFactor();
        float normalization = s.REFERENCE_MINECRAFT_GUI_SCALE / Math.max(0.1f, minecraftScale);
        float referenceWidth = s.width / normalization;
        float referenceHeight = s.height / normalization;
        float fitScale = Math.min(1.0f, Math.min(Math.max(0.1f, (referenceWidth - 20.0f) / 690.0f), Math.max(0.1f, (referenceHeight - 20.0f) / 430.0f)));
        float designWidth = referenceWidth / fitScale;
        float designHeight = referenceHeight / fitScale;
        float margin = 22;
        s.panelWidth = Math.min(1040, Math.max(690, designWidth - margin * 2));
        s.panelHeight = Math.min(600, Math.max(430, designHeight - margin * 2));
        float requestedReferenceScale = fitScale * s.COMPACT_SCALE * (s.renderedInterfaceScale / InterfaceConfig.DEFAULT_SCALE);
        float maximumReferenceScale = Math.min(Math.max(0.1f, (referenceWidth - 12.0f) / s.panelWidth), Math.max(0.1f, (referenceHeight - 12.0f) / s.panelHeight));
        s.uiScale = normalization * Math.min(requestedReferenceScale, maximumReferenceScale);
        float virtualWidth = s.width / s.uiScale;
        float virtualHeight = s.height / s.uiScale;
        s.panelX = (virtualWidth - s.panelWidth) / 2;
        s.panelY = (virtualHeight - s.panelHeight) / 2;
        s.sidebarWidth = Math.min(205, Math.max(174, s.panelWidth * 0.205f));
        s.contentX = s.panelX + s.sidebarWidth + 23;
        s.contentWidth = s.panelX + s.panelWidth - 22 - s.contentX;
        s.contentY = s.panelY + 72;
        s.contentHeight = s.panelHeight - 91;
    }

    static List<GuiModule> filteredModules(CelesteScreen s) {
        int visibleLength = visibleSearchLength(s, System.nanoTime());
        String query = s.search.substring(0, visibleLength).toLowerCase(Locale.ROOT).trim();
        return GuiCatalog.modules().stream()
            .filter(module -> module.page() == s.selectedPage)
            .filter(module -> query.isEmpty() || module.name().toLowerCase(Locale.ROOT).contains(query) || module.description().toLowerCase(Locale.ROOT).contains(query))
            .toList();
    }

    static void updateDraggedSlider(CelesteScreen s, double mouseX) {
        if (s.draggingSlider == null) return;
        for (SettingHit hit : s.settingHits) {
            if (hit.setting() != s.draggingSlider) continue;
            double progress = clamp((float) ((mouseX - hit.x()) / hit.width()), 0, 1);
            s.draggingSlider.setValue(s.draggingSlider.minimum() + (s.draggingSlider.maximum() - s.draggingSlider.minimum()) * progress);
            break;
        }
    }

    static void updateDraggedConfig(CelesteScreen s, double mouseX) {
        if (s.draggingConfig == null) return;
        double progress = clamp((float) ((mouseX - s.draggingConfig.x()) / s.draggingConfig.width()), 0, 1);
        FrostedOption option = s.draggingConfig.option();
        option.set(option.minimum + (option.maximum - option.minimum) * progress);
    }

    static void deletePreviousSearchWord(CelesteScreen s) {
        int end = s.search.length();
        while (end > 0 && s.search.charAt(end - 1) == ' ') end--;
        int start = end;
        while (start > 0 && s.search.charAt(start - 1) != ' ') start--;
        setSearchLength(s, start);
    }

    static void appendSearch(CelesteScreen s, String value) {
        if (value.isEmpty()) return;
        int available = 32 - s.search.length();
        String addition = value.substring(0, Math.min(available, value.length()));
        long now = System.nanoTime();
        s.search += addition;
        for (int index = 0; index < addition.length(); index++) {
            s.searchRevealTimes.add(now + s.SEARCH_REVEAL_DELAY_NANOS + index * 12_000_000L);
        }
    }

    static void setSearchLength(CelesteScreen s, int length) {
        int clampedLength = Math.max(0, Math.min(length, s.search.length()));
        s.search = s.search.substring(0, clampedLength);
        while (s.searchRevealTimes.size() > clampedLength) {
            s.searchRevealTimes.remove(s.searchRevealTimes.size() - 1);
        }
    }

    static int visibleSearchLength(CelesteScreen s, long now) {
        while (s.searchRevealTimes.size() < s.search.length()) s.searchRevealTimes.add(now);
        int visible = 0;
        while (visible < s.search.length() && s.searchRevealTimes.get(visible) <= now) visible++;
        return visible;
    }

    static float searchCharacterFade(CelesteScreen s, int index, long now) {
        if (index < 0 || index >= s.searchRevealTimes.size()) return 1.0f;
        float elapsed = clamp((now - s.searchRevealTimes.get(index)) / (float) s.SEARCH_FADE_NANOS, 0, 1);
        return 1.0f - (float) Math.pow(1.0f - elapsed, 3);
    }

    static void collapseAllModules(CelesteScreen s) {
        s.expandedModule = null;
        s.expansionAnimations.clear();
    }

    static boolean isControlDown(CelesteScreen s) {
        if (s.client == null || s.client.getWindow() == null) return false;
        long handle = s.client.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    static void updateInterfaceScaleAnimation(CelesteScreen s) {
        long now = System.nanoTime();
        float deltaSeconds = clamp((now - s.lastScaleUpdateAt) / 1_000_000_000.0f, 0.0f, 0.05f);
        s.lastScaleUpdateAt = now;
        if (deltaSeconds <= 0.0f) return;
        float target = InterfaceConfig.scale();
        float smoothTime = 0.14f;
        float omega = 2.0f / smoothTime;
        float x = omega * deltaSeconds;
        float decay = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = s.renderedInterfaceScale - target;
        float temporary = (s.interfaceScaleVelocity + omega * change) * deltaSeconds;
        s.interfaceScaleVelocity = (s.interfaceScaleVelocity - omega * temporary) * decay;
        s.renderedInterfaceScale = target + (change + temporary) * decay;
        if (Math.abs(s.renderedInterfaceScale - target) < 0.0001f && Math.abs(s.interfaceScaleVelocity) < 0.0001f) {
            s.renderedInterfaceScale = target;
            s.interfaceScaleVelocity = 0.0f;
        }
    }

    static float hoverAnimation(CelesteScreen s, Object key, boolean hovered) {
        return s.hoverAnimations.computeIfAbsent(key, ignored -> new TimedAnimation(0.0f))
            .update(hovered ? 1.0f : 0.0f, s.HOVER_ANIMATION_NANOS);
    }

    static float pageEntrance(CelesteScreen s, int staggerIndex) {
        long elapsed = System.nanoTime() - s.pageChangedAt - Math.max(0, staggerIndex) * s.PAGE_STAGGER_NANOS;
        float t = clamp(elapsed / (float) s.PAGE_TRANSITION_NANOS, 0.0f, 1.0f);
        return 1.0f - (float) Math.pow(1.0f - t, 4);
    }

    static float settingsButtonY(CelesteScreen s) {
        return s.contentY + 5 + 2 * (82 + 10) + 5 + (1.0f - pageEntrance(s, FrostedOption.values().length + 1)) * 7.0f;
    }

    static float settingsButtonX(CelesteScreen s) {
        return s.contentX + (s.contentWidth - 157) / 2;
    }

    static float openAnimation(CelesteScreen s) {
        float t = clamp((System.nanoTime() - s.openedAt) / 260_000_000.0f, 0, 1);
        return 1 - (float) Math.pow(1 - t, 3);
    }

    static void runConfigAction(CelesteScreen s, String action) {
        ConfigProfileManager configs = ConfigProfileManager.getInstance();
        try {
            switch (action) {
                case "Create" -> setConfigStatus(s, "Created " + configs.create(s.configInput));
                case "Save" -> {
                    if (!s.configInput.isBlank()) setConfigStatus(s, "Saved " + configs.create(s.configInput));
                    else { configs.saveActive(); setConfigStatus(s, "Saved " + configs.active()); }
                }
                case "Import" -> { configs.importFromPath(s.configInput); setConfigStatus(s, "Imported " + configs.active()); }
                case "Set Folder" -> { configs.setCloudDirectory(s.configInput); setConfigStatus(s, "Cloud folder updated"); }
                default -> {}
            }
        } catch (RuntimeException exception) {
            setConfigStatus(s, exception.getMessage() == null ? "Config action failed" : exception.getMessage());
        }
    }

    static void runConfigLoad(CelesteScreen s, String name) {
        try { ConfigProfileManager.getInstance().load(name); setConfigStatus(s, "Loaded " + name); }
        catch (RuntimeException exception) { setConfigStatus(s, exception.getMessage()); }
    }

    static void runConfigDelete(CelesteScreen s, String name) {
        try { ConfigProfileManager.getInstance().delete(name); setConfigStatus(s, "Deleted " + name); }
        catch (RuntimeException exception) { setConfigStatus(s, exception.getMessage()); }
    }

    static void setConfigStatus(CelesteScreen s, String message) {
        s.configStatus = message == null ? "" : message;
        s.configStatusUntil = System.nanoTime() + 4_000_000_000L;
    }
}
