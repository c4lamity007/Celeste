package dev.c4lam.celeste.gui;

import static dev.c4lam.celeste.gui.CelesteScreenTypes.*;

import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.Color;

final class CelesteScreenChrome {
    private CelesteScreenChrome() {
    }

    static void drawSidebar(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float y, float animation) {
        float logoSize = 48;
        RenderUtil.TEXTURE_RECT.draw(matrices, s.panelX + 15, y + 14, logoSize, logoSize, 12,
            withAlpha(Color.WHITE, Math.round(255 * animation)), 0, 0, 1, 1, s.LOGO);
        Font bold = Fonts.bold();
        Font regular = Fonts.regular();
        bold.drawText(matrices, "Celeste Client", s.panelX + 69, y + 24, 12.5f, withAlpha(s.TEXT, Math.round(255 * animation)));
        regular.drawText(matrices, "Version 1.0", s.panelX + 69, y + 42, 8.5f, withAlpha(s.MUTED, Math.round(255 * animation)));
        float itemX = s.panelX + 14;
        float itemWidth = s.sidebarWidth - 28;
        float itemHeight = 29;
        float cursorY = y + 91;
        regular.drawText(matrices, "FEATURES", itemX + 4, cursorY - 14, 7.1f, withAlpha(s.DIM, Math.round(255 * animation)));
        for (CelestePage page : CelestePage.values()) {
            if (!page.feature()) {
                continue;
            }
            drawNavItem(s, matrices, page, itemX, cursorY, itemWidth, itemHeight, mouseX, mouseY, animation);
            cursorY += itemHeight + 4;
        }
        cursorY += 16;
        regular.drawText(matrices, "OTHER", itemX + 4, cursorY - 14, 7.1f, withAlpha(s.DIM, Math.round(255 * animation)));
        for (CelestePage page : CelestePage.values()) {
            if (page.feature()) {
                continue;
            }
            drawNavItem(s, matrices, page, itemX, cursorY, itemWidth, itemHeight, mouseX, mouseY, animation);
            cursorY += itemHeight + 4;
        }
    }

    static void drawNavItem(CelesteScreen s, MatrixStack matrices, CelestePage page, float x, float y,
        float width, float height, int mouseX, int mouseY, float animation) {
        boolean selected = page == s.selectedPage;
        boolean hovered = hovered(mouseX, mouseY, x, y, width, height);
        float hover = CelesteScreenTypes.hoverAnimation(s, page, hovered);
        float selection = s.navigationAnimations
            .computeIfAbsent(page, ignored -> new TimedAnimation())
            .update(selected ? 1.0f : 0.0f, s.NAVIGATION_ANIMATION_NANOS);
        if (hover > 0.001f && selection < 0.98f) {
            RenderUtil.RECT.draw(matrices, x, y, width, height, 8,
                withAlpha(color(69, 42, 65, 135), Math.round(135 * animation * hover * (1.0f - selection))));
        }
        if (selection > 0.001f) {
            RenderUtil.GRADIENT_RECT.draw(matrices, x, y, width, height, 8,
                withAlpha(color(255, 46, 150, 230), Math.round(230 * animation * selection)),
                withAlpha(color(183, 34, 146, 225), Math.round(225 * animation * selection)),
                withAlpha(color(221, 36, 126, 225), Math.round(225 * animation * selection)),
                withAlpha(color(133, 29, 132, 220), Math.round(220 * animation * selection)));
        }
        float iconX = x + 8;
        float iconY = y + 6;
        float iconSize = 17;
        if (page == CelestePage.MOVEMENT && (hovered || selected)) {
            double phase = System.nanoTime() / 100_000_000.0;
            float step = (float) Math.sin(phase);
            float stretch = 1.0f + (float) Math.sin(phase * 2.0) * 0.035f;
            iconX += step * 0.8f;
            iconY -= Math.abs((float) Math.cos(phase)) * 0.7f;
            iconSize *= stretch;
            iconX += (17 - iconSize) * 0.5f;
            iconY += (17 - iconSize) * 0.5f;
        }
        Color iconColor = blend(s.MUTED, Color.WHITE, Math.max(selection, hover * 0.35f));
        Color labelColor = blend(s.TEXT, Color.WHITE, Math.max(selection, hover * 0.22f));
        if (page.icon() != null) {
            RenderUtil.TEXTURE_RECT.draw(matrices, iconX, iconY, iconSize, iconSize, 0,
                withAlpha(iconColor, Math.round((215 + 40 * selection) * animation)), 0, 0, 1, 1, page.icon());
        } else {
            Fonts.bold().drawCenteredText(matrices, page.fallbackGlyph(), iconX + 8.5f, iconY + 1, 10.5f,
                withAlpha(iconColor, Math.round((215 + 40 * selection) * animation)));
        }
        Fonts.semibold().drawText(matrices, page.label(), x + 34, centeredTextY(y, height, 9.4f), 9.4f,
            withAlpha(labelColor, Math.round(255 * animation)));
        s.navHits.add(new NavHit(page, x, y, width, height));
    }

    static void drawHeader(CelesteScreen s, MatrixStack matrices, int mouseX, int mouseY, float y, float animation) {
        float headerX = s.panelX + s.sidebarWidth + 23;
        s.contentX = headerX;
        s.contentWidth = s.panelX + s.panelWidth - 22 - s.contentX;
        Font bold = Fonts.bold();
        Font regular = Fonts.regular();
        float pageEntrance = CelesteScreenTypes.pageEntrance(s, 0);
        float headerOffset = (1.0f - pageEntrance) * 5.0f;
        float headerAnimation = animation * pageEntrance;
        bold.drawText(matrices, s.selectedPage.label(), headerX, y + 23 + headerOffset, 14.5f,
            withAlpha(s.TEXT, Math.round(255 * headerAnimation)));
        regular.drawText(matrices, s.selectedPage.description(), headerX, y + 43 + headerOffset, 8.7f,
            withAlpha(s.MUTED, Math.round(255 * headerAnimation)));
        if (!s.selectedPage.feature()) {
            return;
        }
        float searchWidth = Math.min(176, s.contentWidth * 0.32f);
        float searchX = s.panelX + s.panelWidth - 22 - searchWidth;
        float searchY = y + 23;
        boolean hovered = hovered(mouseX, mouseY, searchX, searchY, searchWidth, 29);
        float focus = CelesteScreenTypes.hoverAnimation(s, s.SEARCH_HOVER_KEY, hovered || s.searchFocused);
        Color searchColor = blend(s.SIDEBAR, color(51, 32, 49, s.SIDEBAR.getAlpha()), focus);
        RenderUtil.RECT.draw(matrices, searchX, searchY, searchWidth, 29, 9,
            withAlpha(searchColor, Math.round(s.SIDEBAR.getAlpha() * animation)));
        RenderUtil.TEXTURE_RECT.draw(matrices, searchX + 9 - focus * 0.5f, searchY + 8, 13, 13, 0,
            withAlpha(blend(s.MUTED, s.TEXT, focus * 0.45f), Math.round(235 * animation)), 0, 0, 1, 1, s.SEARCH_ICON);
        float textX = searchX + 28;
        long now = System.nanoTime();
        int visibleLength = CelesteScreenTypes.visibleSearchLength(s, now);
        String visibleSearch = s.search.substring(0, visibleLength);
        float textWidth = regular.getWidth(visibleSearch, 8.5f);
        float textViewportWidth = searchWidth - 35;
        float textOffset = Math.max(0, textWidth - textViewportWidth + 2);
        GuiRenderer.getInstance().pushScissor(textX * s.uiScale, (searchY + 4) * s.uiScale, textViewportWidth * s.uiScale, 21 * s.uiScale);
        if (!s.search.isEmpty()) {
            float glyphX = textX - textOffset;
            for (int index = 0; index < visibleLength; index++) {
                String glyph = visibleSearch.substring(index, index + 1);
                float fade = CelesteScreenTypes.searchCharacterFade(s, index, now);
                regular.drawText(matrices, glyph, glyphX, centeredTextY(searchY, 29, 8.5f), 8.5f,
                    withAlpha(s.TEXT, Math.round(255 * animation * fade)));
                glyphX += regular.getWidth(glyph, 8.5f);
            }
        } else if (!s.searchFocused) {
            regular.drawText(matrices, "Search modules", textX, centeredTextY(searchY, 29, 8.5f), 8.5f,
                withAlpha(s.DIM, Math.round(255 * animation)));
        }
        if (s.searchFocused && pulse()) {
            float caretX = textX + textWidth - textOffset + 1;
            RenderUtil.RECT.draw(matrices, caretX, searchY + 7, 1, 15, 0.5f,
                withAlpha(s.TEXT, Math.round(245 * animation)));
        }
        GuiRenderer.getInstance().popScissor();
    }
}
