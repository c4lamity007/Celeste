package dev.c4lam.celeste.gui;

import static dev.c4lam.celeste.gui.CelesteScreenTypes.*;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

final class CelesteScreenPages {
    private CelesteScreenPages() {
    }

    static void drawUtilityPage(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float animation) {
        switch (s.selectedPage) {
            case FRIENDS -> drawFriendsPage(s, matrices, animation);
            case CONFIGS -> CelesteScreenConfigPages.drawConfigsPage(s, matrices, mouseX, mouseY, animation);
            case HUD -> drawPlaceholder(s, matrices, "H", "HUD editor", "Arrange interface widgets on screen.", "Open editor", animation);
            case SETTINGS -> CelesteScreenConfigPages.drawSettingsPage(s, matrices, mouseX, mouseY, animation);
            default -> {
            }
        }
    }

    static void drawFriendsPage(CelesteScreen s, MatrixStack matrices, float animation) {
        List<FriendManager.Friend> friends = FriendManager.getInstance().getFriends();
        if (!friends.isEmpty()) {
            float gap = 9;
            float cardWidth = (s.contentWidth - gap * 2) / 3.0f;
            float cardHeight = 48;
            float startY = s.contentY + s.contentScroll + 7;
            for (int index = 0; index < friends.size(); index++) {
                FriendManager.Friend friend = friends.get(index);
                int column = index % 3;
                int row = index / 3;
                float entrance = CelesteScreenTypes.pageEntrance(s, index);
                float x = s.contentX + column * (cardWidth + gap);
                float y = startY + row * (cardHeight + gap) + (1.0f - entrance) * 7.0f;
                Color top = withAlpha(color(45, 29, 43, 205), Math.round(205 * animation * entrance));
                Color bottom = withAlpha(color(22, 15, 25, 220), Math.round(220 * animation * entrance));
                RenderUtil.BLUR_RECT.draw(matrices, x, y, cardWidth, cardHeight, new Vector4f(10),
                    top, top, bottom, bottom, InterfaceConfig.glassTintMix());
                float avatarX = x + 9;
                float avatarY = y + 9;
                RenderUtil.RECT.draw(matrices, avatarX, avatarY, 30, 30, 8,
                    withAlpha(s.ACCENT_SOFT, Math.round(220 * animation * entrance)));
                String initial = friend.lastKnownName().substring(0, 1).toUpperCase(Locale.ROOT);
                Fonts.bold().drawCenteredText(matrices, initial, avatarX + 15,
                    centeredTextY(avatarY, 30, 10.0f), 10.0f,
                    withAlpha(s.TEXT, Math.round(255 * animation * entrance)));
                float textCenter = x + 44 + (cardWidth - 49) / 2.0f;
                Fonts.semibold().drawCenteredText(matrices, friend.lastKnownName(), textCenter,
                    centeredTextY(y + 8, 18, 8.0f), 8.0f,
                    withAlpha(s.TEXT, Math.round(255 * animation * entrance)));
                Fonts.regular().drawCenteredText(matrices, "Middle-click to remove", textCenter,
                    centeredTextY(y + 25, 14, 5.8f), 5.8f,
                    withAlpha(s.MUTED, Math.round(255 * animation * entrance)));
                s.friendHits.add(new FriendHit(friend, x, y, cardWidth, cardHeight));
            }
            int rows = (friends.size() + 2) / 3;
            float totalHeight = 7 + rows * cardHeight + Math.max(0, rows - 1) * gap + 7;
            float minimum = Math.min(0, s.contentHeight - totalHeight);
            s.targetScroll = Math.max(minimum, Math.min(0, s.targetScroll));
            return;
        }
        float entrance = CelesteScreenTypes.pageEntrance(s, 1);
        float centerX = s.contentX + s.contentWidth / 2;
        float centerY = s.contentY + Math.min(105, s.contentHeight * 0.34f) + (1.0f - entrance) * 8.0f;
        Fonts.semibold().drawCenteredText(matrices, "You have no friends.", centerX, centerY, 12.2f,
            withAlpha(s.TEXT, Math.round(255 * animation * entrance)));
        Fonts.regular().drawCenteredText(matrices, "Then Add friends by middle clicking them!", centerX, centerY + 22, 8.7f,
            withAlpha(s.MUTED, Math.round(255 * animation * entrance)));
    }

    static void drawToggle(CelesteScreen s, MatrixStack matrices, Object key, float x, float y, boolean enabled, float animation) {
        float progress = s.toggleAnimations
            .computeIfAbsent(key, ignored -> new TimedAnimation())
            .update(enabled ? 1.0f : 0.0f, s.TOGGLE_ANIMATION_NANOS);
        Color track = blend(color(82, 62, 80, 225), s.ACCENT, progress);
        RenderUtil.RECT.draw(matrices, x, y, 28, 16, 8,
            withAlpha(track, Math.round(track.getAlpha() * animation)));
        float knobX = x + 2 + 12 * progress;
        RenderUtil.RECT.draw(matrices, knobX, y + 2, 12, 12, 6, withAlpha(Color.WHITE, Math.round(245 * animation)));
    }

    static void setPendingTooltip(CelesteScreen s, float mouseX, float mouseY, String text) {
        s.pendingTooltip = text;
        s.pendingTooltipX = mouseX + 10;
        s.pendingTooltipY = mouseY + 14;
    }

    static void drawHoverTooltip(CelesteScreen s, MatrixStack matrices, float x, float y, String text, float animation) {
        float paddingX = 8;
        float paddingY = 6;
        float font = 5.9f;
        float maxWidth = Math.min(220, s.contentWidth - 16);
        List<String> lines = wrapTooltip(text, font, maxWidth);
        float width = paddingX * 2;
        for (String line : lines) {
            width = Math.max(width, Fonts.regular().getWidth(line, font) + paddingX * 2);
        }
        float height = paddingY * 2 + lines.size() * 8.2f;
        float drawX = clamp(x, s.panelX + 8, s.panelX + s.panelWidth - width - 8);
        float drawY = clamp(y, s.panelY + 8, s.panelY + s.panelHeight - height - 8);
        RenderUtil.RECT.draw(matrices, drawX, drawY, width, height, 7,
            withAlpha(color(16, 10, 18, 240), Math.round(240 * animation)));
        for (int index = 0; index < lines.size(); index++) {
            Fonts.regular().drawText(matrices, lines.get(index), drawX + paddingX, drawY + paddingY + index * 8.2f, font,
                withAlpha(s.TEXT, Math.round(255 * animation)));
        }
    }

    static void drawPlaceholder(CelesteScreen s, MatrixStack matrices, String glyph, String title, String description, String action, float animation) {
        float entrance = CelesteScreenTypes.pageEntrance(s, 1);
        animation *= entrance;
        float cardWidth = Math.min(390, s.contentWidth - 30);
        float x = s.contentX + (s.contentWidth - cardWidth) / 2;
        float y = s.contentY + 34 + (1.0f - entrance) * 8.0f;
        RenderUtil.RECT.draw(matrices, x, y, cardWidth, 174, 15, withAlpha(s.CARD, Math.round(185 * animation)));
        RenderUtil.GRADIENT_RECT.draw(matrices, x + cardWidth / 2 - 25, y + 24, 50, 50, 15,
            withAlpha(color(255, 58, 155, 220), Math.round(220 * animation)),
            withAlpha(color(157, 33, 153, 220), Math.round(220 * animation)),
            withAlpha(color(215, 37, 132, 220), Math.round(220 * animation)),
            withAlpha(color(112, 25, 128, 220), Math.round(220 * animation)));
        Fonts.bold().drawCenteredText(matrices, glyph, x + cardWidth / 2,
            centeredTextY(y + 24, 50, 15), 15, withAlpha(Color.WHITE, Math.round(255 * animation)));
        Fonts.bold().drawCenteredText(matrices, title, x + cardWidth / 2, y + 91, 13, withAlpha(s.TEXT, Math.round(255 * animation)));
        Fonts.regular().drawCenteredText(matrices, description, x + cardWidth / 2, y + 113, 8, withAlpha(s.MUTED, Math.round(255 * animation)));
        RenderUtil.RECT.draw(matrices, x + cardWidth / 2 - 55, y + 139, 110, 25, 7, withAlpha(s.ACCENT_SOFT, Math.round(195 * animation)));
        Fonts.semibold().drawCenteredText(matrices, action, x + cardWidth / 2, y + 147, 8.2f, withAlpha(s.TEXT, Math.round(255 * animation)));
    }
}
