package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Borderless, in-world editor for Celeste HUD widgets. */
public final class HudEditorScreen extends Screen {
    private static final Color TEXT = color(250, 244, 250, 255);
    private static final Color MUTED = color(177, 162, 178, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color MENU_TOP = color(29, 18, 30, 236);
    private static final Color MENU_BOTTOM = color(10, 8, 14, 244);
    private static final Color MENU_HOVER = color(62, 36, 58, 165);
    private static final float SNAP_DISTANCE = 5.0f;

    private final Screen parent;
    private final HudManager manager = HudManager.getInstance();
    private final List<MenuHit> menuHits = new ArrayList<>();
    private final List<TargetPlacementHit> targetPlacementHits = new ArrayList<>();
    private HudElementState dragging;
    private float dragOffsetX;
    private float dragOffsetY;
    private boolean boxSelecting;
    private float boxSelectStartX;
    private float boxSelectStartY;
    private float menuX = -1;
    private float menuY = -1;
    private boolean menuOpen;
    private boolean targetPlacementMenu;
    private Float verticalGuide;
    private Float horizontalGuide;
    private final float[] menuHover = new float[HudElementType.values().length];
    private final float[] targetPlacementHover = new float[HudManager.TargetPlacement.values().length];
    private float screenProgress;
    private float menuProgress;
    private float guideProgress;
    private float frameSeconds = 1.0f / 60.0f;
    private long lastFrameTime;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Celeste HUD Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        for (HudElementState state : manager.elements()) {
            state.setSelected(false);
        }
        manager.beginEditorSession();
        screenProgress = 0.0f;
        menuProgress = 0.0f;
        guideProgress = 0.0f;
        lastFrameTime = System.nanoTime();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        frameSeconds = Math.min(0.05f, Math.max(0.0f, (now - lastFrameTime) / 1_000_000_000.0f));
        lastFrameTime = now;
        screenProgress = damp(screenProgress, 1.0f, 12.0f, frameSeconds);
        menuProgress = damp(menuProgress, menuOpen ? 1.0f : 0.0f,
            menuOpen ? 20.0f : 24.0f, frameSeconds);
        guideProgress = damp(guideProgress, verticalGuide != null || horizontalGuide != null ? 1.0f : 0.0f,
            18.0f, frameSeconds);

        if (manager.clampToViewport(width, height)) {
            manager.saveLayout();
        }
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();

        manager.renderEditor(context, mouseX, mouseY, dragging);
        drawGuides(matrices);
        drawHelp(matrices);
        if (boxSelecting) {
            drawBoxSelection(matrices, mouseX, mouseY);
        }
        if (menuOpen || menuProgress > 0.01f) {
            if (targetPlacementMenu) {
                drawTargetPlacementMenu(matrices, mouseX, mouseY, easeOutCubic(menuProgress));
            } else {
                drawAddMenu(matrices, mouseX, mouseY, easeOutCubic(menuProgress));
            }
        } else {
            menuHits.clear();
            targetPlacementHits.clear();
        }
    }

    private void drawHelp(MatrixStack matrices) {
        String title = "Celeste HUD Editor";
        String hint = "Drag widgets  •  Shift-click for multi-select  •  Scroll to resize selection  •  Right-click to configure  •  Esc to return";
        float width = Math.max(226, Fonts.regular().getWidth(hint, 7.0f) + 22);
        float x = (this.width - width) / 2.0f;
        float progress = easeOutCubic(screenProgress);
        float y = 9 - (1.0f - progress) * 8.0f;
        Color top = color(27, 18, 29, Math.round(211 * progress));
        Color bottom = color(9, 7, 13, Math.round(226 * progress));
        RenderUtil.BLUR_RECT.draw(matrices, x, y, width, 36, new Vector4f(10),
            top, top, bottom, bottom, InterfaceConfig.glassTintMix());
        Fonts.semibold().drawCenteredText(matrices, title, this.width / 2.0f, y + 7, 8.4f, withAlpha(TEXT, progress));
        Fonts.regular().drawCenteredText(matrices, hint, this.width / 2.0f, y + 22, 6.7f, withAlpha(MUTED, progress));
    }

    private void drawBoxSelection(MatrixStack matrices, int mouseX, int mouseY) {
        float x1 = Math.min(boxSelectStartX, (float) mouseX);
        float y1 = Math.min(boxSelectStartY, (float) mouseY);
        float x2 = Math.max(boxSelectStartX, (float) mouseX);
        float y2 = Math.max(boxSelectStartY, (float) mouseY);

        float selectionWidth = x2 - x1;
        float selectionHeight = y2 - y1;
        if (selectionWidth > 2 && selectionHeight > 2) {
            float radius = Math.min(4.5f, Math.min(selectionWidth, selectionHeight) * 0.5f);
            Color glassTop = color(86, 86, 91, 76);
            Color glassBottom = color(38, 38, 43, 96);
            RenderUtil.BLUR_RECT.draw(
                matrices,
                x1,
                y1,
                selectionWidth,
                selectionHeight,
                new Vector4f(radius),
                glassTop,
                glassTop,
                glassBottom,
                glassBottom,
                InterfaceConfig.glassTintMix()
            );

            float thickness = 0.75f;
            float inset = Math.min(radius * 0.45f, 2.0f);
            Color topEdge = color(224, 224, 228, 100);
            Color otherEdge = color(170, 170, 176, 72);
            RenderUtil.RECT.draw(matrices, x1 + inset, y1,
                Math.max(0, selectionWidth - inset * 2), thickness, thickness / 2, topEdge);
            RenderUtil.RECT.draw(matrices, x1 + inset, y2 - thickness,
                Math.max(0, selectionWidth - inset * 2), thickness, thickness / 2, otherEdge);
            RenderUtil.RECT.draw(matrices, x1, y1 + inset,
                thickness, Math.max(0, selectionHeight - inset * 2), thickness / 2, otherEdge);
            RenderUtil.RECT.draw(matrices, x2 - thickness, y1 + inset,
                thickness, Math.max(0, selectionHeight - inset * 2), thickness / 2, otherEdge);
        }
    }

    private void drawGuides(MatrixStack matrices) {
        if (verticalGuide != null) {
            RenderUtil.RECT.draw(matrices, verticalGuide - 0.5f, 0, 1, height, 0,
                color(255, 86, 164, Math.round(145 * guideProgress)));
        }
        if (horizontalGuide != null) {
            RenderUtil.RECT.draw(matrices, 0, horizontalGuide - 0.5f, width, 1, 0,
                color(255, 86, 164, Math.round(145 * guideProgress)));
        }
    }

    private void drawAddMenu(MatrixStack matrices, int mouseX, int mouseY, float progress) {
        float preferredColumnWidth = 156;
        float rowHeight = 21;
        int itemCount = HudElementType.values().length;
        int maximumRows = Math.max(1, (int) Math.floor((Math.max(rowHeight + 12, height - 12) - 12) / rowHeight));
        int minimumColumns = width >= preferredColumnWidth * 2 + 12 ? 2 : 1;
        int columnCount = Math.max(minimumColumns, (itemCount + maximumRows - 1) / maximumRows);
        int rowCount = (itemCount + columnCount - 1) / columnCount;
        float columnWidth = Math.min(preferredColumnWidth, Math.max(1, width - 12) / (float) columnCount);
        float menuWidth = columnWidth * columnCount;
        float menuHeight = 12 + rowCount * rowHeight;
        menuX = Math.max(6, Math.min(menuX, width - menuWidth - 6));
        menuY = Math.max(6, Math.min(menuY, height - menuHeight - 6));
        Color top = withAlpha(MENU_TOP, progress);
        Color bottom = withAlpha(MENU_BOTTOM, progress);
        RenderUtil.BLUR_RECT.draw(matrices, menuX, menuY, menuWidth, menuHeight, new Vector4f(11),
            top, top, bottom, bottom, InterfaceConfig.glassTintMix());
        menuHits.clear();
        targetPlacementHits.clear();

        HudElementType[] types = HudElementType.values();
        for (int index = 0; index < types.length; index++) {
            HudElementType type = types[index];
            int column = index / rowCount;
            int row = index % rowCount;
            float rowX = menuX + column * columnWidth;
            float rowY = menuY + 6 + row * rowHeight;
            HudElementState state = manager.state(type);
            boolean hovered = menuOpen && inside(mouseX, mouseY, rowX + 5, rowY, columnWidth - 10, rowHeight - 2);
            menuHover[index] = damp(menuHover[index], hovered ? 1.0f : 0.0f, hovered ? 22.0f : 15.0f, frameSeconds);
            if (menuHover[index] > 0.01f) {
                RenderUtil.RECT.draw(matrices, rowX + 5, rowY, columnWidth - 10, rowHeight - 2, 6,
                    withAlpha(MENU_HOVER, progress * menuHover[index]));
            }
            Fonts.regular().drawText(matrices, state.enabled() ? "−" : "+", rowX + 12, rowY + 6, 7.5f,
                state.enabled() ? MUTED : ACCENT);
            Fonts.semibold().drawText(matrices, type.label(), rowX + 27, rowY + 5, 7.6f,
                withAlpha(blend(MUTED, TEXT, menuHover[index]), progress));
            menuHits.add(new MenuHit(type, MenuAction.TOGGLE, rowX + 5, rowY, columnWidth - 10, rowHeight - 2));
        }

    }

    private void drawTargetPlacementMenu(MatrixStack matrices, int mouseX, int mouseY, float progress) {
        float menuWidth = 164.0F;
        float rowHeight = 22.0F;
        float menuHeight = 29.0F + HudManager.TargetPlacement.values().length * rowHeight;
        menuX = Math.max(6.0F, Math.min(menuX, width - menuWidth - 6.0F));
        menuY = Math.max(6.0F, Math.min(menuY, height - menuHeight - 6.0F));
        Color top = withAlpha(MENU_TOP, progress);
        Color bottom = withAlpha(MENU_BOTTOM, progress);
        RenderUtil.BLUR_RECT.draw(matrices, menuX, menuY, menuWidth, menuHeight, new Vector4f(11),
            top, top, bottom, bottom, InterfaceConfig.glassTintMix());
        Fonts.semibold().drawText(matrices, "Target HUD placement", menuX + 12.0F, menuY + 8.0F,
            7.4F, withAlpha(TEXT, progress));

        menuHits.clear();
        targetPlacementHits.clear();
        HudManager.TargetPlacement selected = manager.targetPlacement();
        HudManager.TargetPlacement[] placements = HudManager.TargetPlacement.values();
        for (int index = 0; index < placements.length; index++) {
            HudManager.TargetPlacement placement = placements[index];
            float rowX = menuX + 6.0F;
            float rowY = menuY + 25.0F + index * rowHeight;
            float rowWidth = menuWidth - 12.0F;
            boolean hovered = menuOpen && inside(mouseX, mouseY, rowX, rowY, rowWidth, rowHeight - 2.0F);
            targetPlacementHover[index] = damp(targetPlacementHover[index], hovered ? 1.0F : 0.0F,
                hovered ? 22.0F : 15.0F, frameSeconds);
            if (targetPlacementHover[index] > 0.01F) {
                RenderUtil.RECT.draw(matrices, rowX, rowY, rowWidth, rowHeight - 2.0F, 6.0F,
                    withAlpha(MENU_HOVER, progress * targetPlacementHover[index]));
            }

            boolean active = placement == selected;
            Fonts.regular().drawText(matrices, active ? "●" : "○", rowX + 8.0F, rowY + 6.0F,
                7.0F, withAlpha(active ? ACCENT : MUTED, progress));
            Fonts.semibold().drawText(matrices, placement.label(), rowX + 26.0F, rowY + 5.0F,
                7.5F, withAlpha(blend(MUTED, TEXT, Math.max(active ? 0.65F : 0.0F,
                    targetPlacementHover[index])), progress));
            targetPlacementHits.add(new TargetPlacementHit(placement, rowX, rowY, rowWidth,
                rowHeight - 2.0F));
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            HudElementState clicked = manager.hit(click.x(), click.y());
            for (HudElementState state : manager.elements()) {
                state.setSelected(state == clicked && clicked.type() == HudElementType.TARGET_HUD);
            }
            menuX = (float) click.x();
            menuY = (float) click.y();
            menuOpen = true;
            targetPlacementMenu = clicked != null && clicked.type() == HudElementType.TARGET_HUD;
            dragging = null;
            boxSelecting = false;
            return true;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        if (menuOpen) {
            if (targetPlacementMenu) {
                for (TargetPlacementHit hit : targetPlacementHits) {
                    if (!hit.contains(click.x(), click.y())) {
                        continue;
                    }
                    manager.setTargetPlacement(hit.placement());
                    menuOpen = false;
                    return true;
                }
                menuOpen = false;
                return true;
            }
            for (MenuHit hit : menuHits) {
                if (!hit.contains(click.x(), click.y())) {
                    continue;
                }
                manager.toggle(hit.type());
                if (manager.state(hit.type()).enabled()) {
                    placeNewElement(manager.state(hit.type()), menuX, menuY);
                }
                menuOpen = false;
                return true;
            }
            menuOpen = false;
            return true;
        }

        boolean additiveSelection = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!additiveSelection && manager.handleMediaClick(click.x(), click.y())) {
            boxSelecting = false;
            return true;
        }

        HudElementState hit = manager.hit(click.x(), click.y());
        if (hit != null) {
            if (additiveSelection) {
                hit.setSelected(!hit.selected());
                dragging = null;
                boxSelecting = false;
                return true;
            }
            for (HudElementState state : manager.elements()) {
                state.setSelected(state == hit);
            }
            dragging = hit;
            dragOffsetX = (float) click.x() - hit.displayX();
            dragOffsetY = (float) click.y() - hit.displayY();
            boxSelecting = false;
            return true;
        } else {
            for (HudElementState state : manager.elements()) {
                state.setSelected(false);
            }
            boxSelecting = true;
            boxSelectStartX = (float) click.x();
            boxSelectStartY = (float) click.y();
            return true;
        }
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        
        if (dragging != null) {
            Vector2f snapped = snap(
                (float) click.x() - dragOffsetX,
                (float) click.y() - dragOffsetY,
                dragging
            );
            dragging.moveTo(
                Math.max(0, Math.min(width - dragging.width(), snapped.x)),
                Math.max(0, Math.min(height - dragging.height(), snapped.y))
            );
            boxSelecting = false;
            return true;
        } else if (boxSelecting) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            manager.saveLayout();
            dragging = null;
        } else if (boxSelecting) {
            float x1 = Math.min(boxSelectStartX, (float) click.x());
            float y1 = Math.min(boxSelectStartY, (float) click.y());
            float x2 = Math.max(boxSelectStartX, (float) click.x());
            float y2 = Math.max(boxSelectStartY, (float) click.y());
            
            for (HudElementState state : manager.elements()) {
                if (state.enabled()) {
                    float elemX = state.displayX();
                    float elemY = state.displayY();
                    float elemWidth = state.width();
                    float elemHeight = state.height();
                    
                    if (elemX < x2 && elemX + elemWidth > x1 &&
                        elemY < y2 && elemY + elemHeight > y1) {
                        state.setSelected(true);
                    } else {
                        state.setSelected(false);
                    }
                }
            }
            boxSelecting = false;
        }
        
        dragging = null;
        verticalGuide = null;
        horizontalGuide = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<HudElementState> selectedElements = new ArrayList<>();
        for (HudElementState state : manager.elements()) {
            if (state.enabled() && state.selected()) {
                selectedElements.add(state);
            }
        }
        if (!selectedElements.isEmpty() && verticalAmount != 0.0D) {
            // One reversible scalar keeps every selected card's original
            // aspect, glyph size, and internal spacing in lockstep.
            float scaleFactor = (float) Math.pow(1.1D, verticalAmount);
            for (HudElementState state : selectedElements) {
                state.setScale(state.scale() * scaleFactor);
            }
            manager.clampToViewport(width, height);
            manager.saveLayout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private Vector2f snap(float rawX, float rawY, HudElementState moving) {
        verticalGuide = null;
        horizontalGuide = null;

        List<Float> verticalGuides = new ArrayList<>();
        verticalGuides.add(0.0f);
        verticalGuides.add(width / 2.0f);
        verticalGuides.add((float) width);
        List<Float> horizontalGuides = new ArrayList<>();
        horizontalGuides.add(0.0f);
        horizontalGuides.add(height / 2.0f);
        horizontalGuides.add((float) height);

        for (HudElementState other : manager.elements()) {
            if (!other.enabled() || other == moving) {
                continue;
            }
            verticalGuides.add(other.displayX());
            verticalGuides.add(other.displayX() + other.width() / 2.0f);
            verticalGuides.add(other.displayX() + other.width());
            horizontalGuides.add(other.displayY());
            horizontalGuides.add(other.displayY() + other.height() / 2.0f);
            horizontalGuides.add(other.displayY() + other.height());
        }

        SnapResult snappedX = nearestSnap(rawX, new float[]{0, moving.width() / 2.0f, moving.width()}, verticalGuides);
        SnapResult snappedY = nearestSnap(rawY, new float[]{0, moving.height() / 2.0f, moving.height()}, horizontalGuides);
        verticalGuide = snappedX.guide();
        horizontalGuide = snappedY.guide();
        return new Vector2f(snappedX.position(), snappedY.position());
    }

    private static SnapResult nearestSnap(float origin, float[] anchorOffsets, List<Float> guides) {
        float closestDistance = SNAP_DISTANCE + 1.0f;
        float closestDelta = 0.0f;
        Float closestGuide = null;
        for (float guide : guides) {
            for (float offset : anchorOffsets) {
                float delta = guide - (origin + offset);
                float distance = Math.abs(delta);
                if (distance <= SNAP_DISTANCE && distance < closestDistance) {
                    closestDistance = distance;
                    closestDelta = delta;
                    closestGuide = guide;
                }
            }
        }
        return new SnapResult(origin + closestDelta, closestGuide);
    }

    private void placeNewElement(HudElementState state, float requestedX, float requestedY) {
        state.moveImmediately(
            Math.max(0, Math.min(width - state.width(), requestedX)),
            Math.max(48, Math.min(height - state.height(), requestedY))
        );
        manager.saveLayout();
    }

    @Override
    public void close() {
        manager.saveLayout();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    protected void applyBlur(DrawContext context) {
    }

    @Override
    public void removed() {
        manager.saveLayout();
        manager.releaseBlurCapture();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    private static float easeOutCubic(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        float inverse = 1.0f - clamped;
        return 1.0f - inverse * inverse * inverse;
    }

    private static Color blend(Color from, Color to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        return color(
            Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
            Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t)
        );
    }

    private static Color withAlpha(Color source, float opacity) {
        return color(source.getRed(), source.getGreen(), source.getBlue(),
            Math.max(0, Math.min(255, Math.round(source.getAlpha() * opacity))));
    }

    private enum MenuAction {
        TOGGLE
    }

    private record MenuHit(HudElementType type, MenuAction action, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record TargetPlacementHit(HudManager.TargetPlacement placement, float x, float y,
                                      float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record SnapResult(float position, Float guide) {
    }
}
