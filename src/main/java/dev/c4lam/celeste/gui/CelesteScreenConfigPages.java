package dev.c4lam.celeste.gui;

import static dev.c4lam.celeste.gui.CelesteScreenTypes.*;

import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.List;

final class CelesteScreenConfigPages {
    private CelesteScreenConfigPages() {
    }

    static void drawConfigsPage(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float animation) {
        ConfigProfileManager configs = ConfigProfileManager.getInstance();
        float x = s.contentX;
        float width = s.contentWidth;
        float cursor = s.contentY + s.contentScroll + 8;
        int card = 0;
        float headerEntrance = CelesteScreenTypes.pageEntrance(s, card++);
        float headerOpacity = animation * headerEntrance;
        float headerHeight = 72;
        RenderUtil.BLUR_RECT.draw(matrices, x, cursor + (1.0f - headerEntrance) * 7.0f, width, headerHeight, new Vector4f(12),
            withAlpha(color(44, 27, 43, 205), Math.round(205 * headerOpacity)),
            withAlpha(color(44, 27, 43, 205), Math.round(205 * headerOpacity)),
            withAlpha(color(18, 12, 21, 222), Math.round(222 * headerOpacity)),
            withAlpha(color(18, 12, 21, 222), Math.round(222 * headerOpacity)),
            InterfaceConfig.glassTintMix());
        Fonts.semibold().drawText(matrices, "Your configs", x + 16, cursor + 14, 10.4f,
            withAlpha(s.TEXT, Math.round(255 * headerOpacity)));
        Fonts.regular().drawText(matrices, "1. Name it   2. Save   3. Click a card to use it",
            x + 16, cursor + 32, 6.6f, withAlpha(s.MUTED, Math.round(255 * headerOpacity)));
        String status = !s.configStatus.isEmpty() && System.nanoTime() < s.configStatusUntil
            ? s.configStatus : "Using  " + configs.active();
        Fonts.regular().drawText(matrices, status, x + 16, cursor + 48, 6.4f,
            withAlpha(!s.configStatus.isEmpty() && System.nanoTime() < s.configStatusUntil ? s.ACCENT : s.DIM,
                Math.round(255 * headerOpacity)));
        drawHelpChip(s, matrices, x + width - 28, cursor + 14, mouseX, mouseY, headerOpacity,
            "Every config is a PNG. Save one for legit, one for crystal, then click a card to swap.");
        cursor += headerHeight + 10;
        float fieldEntrance = CelesteScreenTypes.pageEntrance(s, card++);
        float fieldY = cursor + (1.0f - fieldEntrance) * 6.0f;
        float fieldOpacity = animation * fieldEntrance;
        boolean fieldHovered = hovered(mouseX, mouseY, x, fieldY, width - 26, 32);
        RenderUtil.RECT.draw(matrices, x, fieldY, width - 26, 32, 8,
            withAlpha(blend(s.CONTROL, s.CARD_HOVER, fieldHovered || s.configFieldFocused ? 1.0f : 0.0f),
                Math.round(210 * fieldOpacity)));
        String fieldText = s.configInput.isEmpty()
            ? (s.configFieldFocused ? (pulse() ? "|" : "") : "Name this config, e.g. legit")
            : s.configInput + (s.configFieldFocused && pulse() ? "|" : "");
        Fonts.regular().drawText(matrices, trim(fieldText, 52), x + 12, centeredTextY(fieldY, 32, 7.4f), 7.4f,
            withAlpha(s.configInput.isEmpty() && !s.configFieldFocused ? s.DIM : s.TEXT, Math.round(255 * fieldOpacity)));
        s.configFieldHit = new FieldHit(x, fieldY, width - 26, 32);
        drawHelpChip(s, matrices, x + width - 18, fieldY + 7, mouseX, mouseY, fieldOpacity,
            "Type a short name to save, or paste a PNG path to import.");
        cursor += 42;
        ConfigAction[] actions = {
            new ConfigAction("Save", "Save", "Saves what you are using right now as a PNG config."),
            new ConfigAction("Import", "Import", "Loads a PNG from the path you pasted above."),
            new ConfigAction("Set Folder", "Folder", "Sets the folder where config PNGs are stored.")
        };
        float buttonEntrance = CelesteScreenTypes.pageEntrance(s, card++);
        float buttonY = cursor + (1.0f - buttonEntrance) * 6.0f;
        float buttonOpacity = animation * buttonEntrance;
        float gap = 8;
        float slotWidth = (width - gap * (actions.length - 1)) / actions.length;
        for (int index = 0; index < actions.length; index++) {
            ConfigAction action = actions[index];
            float slotX = x + index * (slotWidth + gap);
            float buttonWidth = slotWidth - 20;
            boolean hovered = hovered(mouseX, mouseY, slotX, buttonY, buttonWidth, 28);
            RenderUtil.RECT.draw(matrices, slotX, buttonY, buttonWidth, 28, 7,
                withAlpha(hovered ? s.ACCENT_SOFT : s.CONTROL, Math.round(210 * buttonOpacity)));
            Fonts.semibold().drawCenteredText(matrices, action.label, slotX + buttonWidth / 2,
                centeredTextY(buttonY, 28, 6.8f), 6.8f, withAlpha(s.TEXT, Math.round(255 * buttonOpacity)));
            s.actionHits.add(new ActionHit(action.id, action.help, false, slotX, buttonY, buttonWidth, 28));
            if (hovered) {
                CelesteScreenPages.setPendingTooltip(s, mouseX, mouseY, action.help);
            }
            drawHelpChip(s, matrices, slotX + slotWidth - 16, buttonY + 5, mouseX, mouseY, buttonOpacity, action.help);
        }
        cursor += 42;
        cursor = drawConfigGrid(s, matrices, mouseX, mouseY, animation, card, x, width, cursor,
            "Saved configs", "Each card is a PNG. Click one to switch.", configs.listProfiles(), configs.active());
        float totalHeight = cursor - (s.contentY + s.contentScroll) + 12;
        float minimum = Math.min(0, s.contentHeight - totalHeight);
        s.targetScroll = Math.max(minimum, Math.min(0, s.targetScroll));
    }

    static float drawConfigGrid(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float animation,
        int stagger, float x, float width, float cursor, String title, String hint, List<String> names, String activeName) {
        float titleEntrance = CelesteScreenTypes.pageEntrance(s, stagger);
        Fonts.semibold().drawText(matrices, title, x, cursor + (1.0f - titleEntrance) * 4.0f, 8.0f,
            withAlpha(s.TEXT, Math.round(255 * animation * titleEntrance)));
        Fonts.regular().drawText(matrices, hint, x + Fonts.semibold().getWidth(title, 8.0f) + 8,
            cursor + 1.4f + (1.0f - titleEntrance) * 4.0f, 6.2f,
            withAlpha(s.DIM, Math.round(255 * animation * titleEntrance)));
        cursor += 22;
        if (names.isEmpty()) {
            Fonts.regular().drawText(matrices, "None yet. Name it above, then press Save.",
                x, cursor, 6.6f, withAlpha(s.MUTED, Math.round(255 * animation * titleEntrance)));
            return cursor + 26;
        }
        float gap = 9;
        float cardWidth = (width - gap * 2) / 3.0f;
        float cardHeight = 58;
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            int column = index % 3;
            int row = index / 3;
            float cardX = x + column * (cardWidth + gap);
            float cardY = cursor + row * (cardHeight + gap) + (1.0f - CelesteScreenTypes.pageEntrance(s, stagger + 1 + index)) * 5.0f;
            float opacity = animation * CelesteScreenTypes.pageEntrance(s, stagger + 1 + index);
            boolean active = name.equals(activeName);
            boolean hovered = hovered(mouseX, mouseY, cardX, cardY, cardWidth, cardHeight);
            RenderUtil.RECT.draw(matrices, cardX, cardY, cardWidth, cardHeight, 10,
                withAlpha(active ? s.ACCENT_SOFT : hovered ? s.CARD_HOVER : s.CARD, Math.round(205 * opacity)));
            Fonts.semibold().drawText(matrices, trim(name, 14), cardX + 10, cardY + 11, 7.6f,
                withAlpha(s.TEXT, Math.round(255 * opacity)));
            Fonts.regular().drawText(matrices, active ? "In use" : "Click to use",
                cardX + 10, cardY + 30, 5.9f,
                withAlpha(active ? s.ACCENT : s.MUTED, Math.round(255 * opacity)));
            float deleteX = cardX + cardWidth - 22;
            float deleteY = cardY + 8;
            boolean deleteHovered = hovered(mouseX, mouseY, deleteX, deleteY, 14, 14);
            Fonts.semibold().drawCenteredText(matrices, "x", deleteX + 7, centeredTextY(deleteY, 14, 6.4f), 6.4f,
                withAlpha(deleteHovered ? s.TEXT : s.DIM, Math.round(255 * opacity)));
            s.profileHits.add(new ProfileHit(name, cardX, cardY, cardWidth, cardHeight, deleteX, deleteY, 14, 14));
            if (deleteHovered) {
                CelesteScreenPages.setPendingTooltip(s, mouseX, mouseY, "Delete this PNG config.");
            } else if (hovered) {
                CelesteScreenPages.setPendingTooltip(s, mouseX, mouseY, active ? "This config is already active." : "Switch to this PNG config.");
            }
        }
        int rows = (names.size() + 2) / 3;
        return cursor + rows * cardHeight + Math.max(0, rows - 1) * gap + 14;
    }

    static void drawHelpChip(CelesteScreen s, MatrixStack matrices, float x, float y, int mouseX, int mouseY,
                              float opacity, String tooltip) {
        float size = 16;
        boolean hovered = hovered(mouseX, mouseY, x, y, size, size);
        RenderUtil.RECT.draw(matrices, x, y, size, size, size / 2,
            withAlpha(hovered ? s.ACCENT_SOFT : s.CONTROL, Math.round(220 * opacity)));
        RenderUtil.TEXTURE_RECT.draw(matrices, x + 2.5f, y + 2.5f, 11, 11, 0,
            withAlpha(hovered ? Color.WHITE : s.MUTED, Math.round(255 * opacity)), 0, 0, 1, 1, s.HELP_ICON);
        s.actionHits.add(new ActionHit("help", tooltip, true, x, y, size, size));
        if (hovered) {
            CelesteScreenPages.setPendingTooltip(s, mouseX, mouseY, tooltip);
        }
    }

    static void drawSettingsPage(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float animation) {
        float gap = 10;
        float cardWidth = (s.contentWidth - gap) / 2;
        float cardHeight = 82;
        FrostedOption[] options = FrostedOption.values();
        for (int index = 0; index < options.length; index++) {
            boolean rightColumn = options[index] == FrostedOption.PASSES;
            int row = options[index] == FrostedOption.OFFSET ? 1 : 0;
            float optionX = s.contentX + (rightColumn ? cardWidth + gap : 0);
            float entrance = CelesteScreenTypes.pageEntrance(s, index + 1);
            drawConfigCard(s, matrices, options[index], optionX,
                s.contentY + 5 + row * (cardHeight + gap) + (1.0f - entrance) * 7.0f,
                cardWidth, cardHeight, mouseX, mouseY, animation * entrance);
        }
        float buttonX = CelesteScreenTypes.settingsButtonX(s);
        float buttonEntrance = CelesteScreenTypes.pageEntrance(s, options.length + 1);
        float buttonY = CelesteScreenTypes.settingsButtonY(s);
        boolean buttonHovered = hovered(mouseX, mouseY, buttonX, buttonY, 157, 29);
        float buttonHover = CelesteScreenTypes.hoverAnimation(s, s.SETTINGS_BUTTON_HOVER_KEY, buttonHovered);
        RenderUtil.RECT.draw(matrices, buttonX, buttonY, 157, 29, 8,
            withAlpha(blend(s.CONTROL, s.ACCENT_SOFT, buttonHover), Math.round(205 * animation * buttonEntrance)));
        Fonts.semibold().drawCenteredText(matrices, "Open Minecraft options", buttonX + 78.5f,
            centeredTextY(buttonY, 29, 8.3f), 8.3f,
            withAlpha(s.TEXT, Math.round(255 * animation * buttonEntrance)));
    }

    static void drawConfigCard(CelesteScreen s, MatrixStack matrices, FrostedOption option, float x, float y,
        float width, float height, int mouseX, int mouseY, float animation) {
        boolean hovered = hovered(mouseX, mouseY, x, y, width, height);
        float hover = CelesteScreenTypes.hoverAnimation(s, option, hovered);
        RenderUtil.RECT.draw(matrices, x, y, width, height, 11,
            withAlpha(blend(s.CARD, s.CARD_HOVER, hover), Math.round((175 + 17 * hover) * animation)));
        Fonts.semibold().drawText(matrices, option.label, x + 14, y + 13, 9.5f,
            withAlpha(s.TEXT, Math.round(255 * animation)));
        String value = sliderValue(option.value());
        float valueWidth = Math.max(35, Fonts.semibold().getWidth(value, 7.5f) + 14);
        RenderUtil.RECT.draw(matrices, x + width - valueWidth - 12, y + 9, valueWidth, 19, 6,
            withAlpha(s.CONTROL, Math.round(210 * animation)));
        Fonts.semibold().drawCenteredText(matrices, value, x + width - valueWidth / 2 - 12,
            centeredTextY(y + 9, 19, 7.5f), 7.5f, withAlpha(s.TEXT, Math.round(255 * animation)));
        Fonts.regular().drawText(matrices, option.description, x + 14, y + 35, 7.1f,
            withAlpha(s.MUTED, Math.round(255 * animation)));
        float sliderX = x + 14;
        float sliderY = y + height - 17;
        float sliderWidth = width - 28;
        float targetProgress = (option.value() - option.minimum) / (option.maximum - option.minimum);
        float progress = s.sliderAnimations.computeIfAbsent(option, ignored -> new SpringAnimation())
            .update(targetProgress, s.SLIDER_SMOOTH_TIME_SECONDS);
        RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth, 4, 2,
            withAlpha(color(86, 59, 82, 230), Math.round(230 * animation)));
        RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth * progress, 4, 2,
            withAlpha(s.ACCENT, Math.round(255 * animation)));
        float knobX = sliderX + sliderWidth * progress - 3;
        RenderUtil.RECT.draw(matrices, knobX, sliderY - 1, 6, 6, 3,
            withAlpha(Color.WHITE, Math.round(250 * animation)));
        s.configHits.add(new ConfigHit(option, sliderX, sliderY - 8, sliderWidth, 20));
    }
}
