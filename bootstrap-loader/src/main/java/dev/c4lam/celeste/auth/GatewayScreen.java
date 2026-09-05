package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.render.GradientRectRender;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RectRender;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** A deliberately small activation gate with no soft-focus overlays. */
final class GatewayScreen extends Screen {
    private static final RectRender RECT = new RectRender();
    private static final GradientRectRender GRADIENT = new GradientRectRender();
    private static final String LICENSE_MASK =
        "CST-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX";

    private final long openedAt = System.nanoTime();
    private TextFieldWidget license;
    private GatewayButton check;
    private GatewayButton reset;
    private int panelX;
    private int panelY;
    private int panelWidth;

    GatewayScreen() {
        super(Text.literal("Celeste License"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(380, Math.max(300, width - 32));
        int panelHeight = 176;
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(18, (height - panelHeight) / 2);

        int controlX = panelX + 24;
        int controlWidth = panelWidth - 48;
        license = new InputCapture(textRenderer, controlX, panelY + 68,
            controlWidth, 32, Text.literal("License code"));
        license.setMaxLength(LICENSE_MASK.length());
        license.setDrawsBackground(false);
        license.setPlaceholder(Text.empty());
        license.setCentered(true);
        license.setChangedListener(ignored -> refresh());
        addDrawableChild(license);

        check = addDrawableChild(new GatewayButton(controlX, panelY + 111, controlWidth, 32,
            "Check", true, () -> GatewayRuntime.submitLicense(license.getText())));
        reset = addDrawableChild(new GatewayButton(controlX, panelY + 111, controlWidth, 32,
            "Use another license", false, () -> {
                GatewayRuntime.clearBinding();
                license.setText("");
                license.setFocused(true);
            }));

        if (!GatewayRuntime.hasCredential()) {
            setInitialFocus(license);
        }
        refresh();
    }

    void refresh() {
        if (license == null) {
            return;
        }
        boolean busy = GatewayRuntime.busy();
        boolean stored = GatewayRuntime.hasCredential();
        license.setEditable(!busy && !stored);
        check.visible = !stored;
        check.active = !busy && license.getText().strip().length() >= 20;
        reset.visible = stored;
        reset.active = !busy;
    }

    @Override
    public void tick() {
        super.tick();
        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        float seconds = (System.nanoTime() - openedAt) / 1_000_000_000.0f;

        drawBackground(seconds);
        drawCard();
        drawPanelText(seconds);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)
            && check != null && check.visible && check.active) {
            GatewayRuntime.submitLicense(license.getText());
            return true;
        }
        return super.keyPressed(input);
    }

    private void drawBackground(float seconds) {
        var matrices = GuiRenderer.getInstance().matrices();
        GRADIENT.draw(matrices, 0, 0, width, height, 0,
            new Color(7, 8, 15), new Color(11, 10, 23),
            new Color(4, 6, 12), new Color(7, 8, 18));

        int count = Math.max(26, Math.min(48, width / 17));
        for (int index = 0; index < count; index++) {
            float speed = 4.0f + (index % 6) * 0.75f;
            float x = positiveModulo(index * 79.31f + seconds * speed, width + 64.0f) - 32.0f;
            float baseY = positiveModulo(index * 47.17f, height + 24.0f) - 12.0f;
            float y = baseY + (float) Math.sin(seconds * (0.32f + (index % 4) * 0.05f)
                + index * 0.81f) * (5.0f + index % 5);
            float pulse = 0.5f + 0.5f * (float) Math.sin(seconds * 0.9f + index * 0.67f);
            float size = 0.9f + (index % 3) * 0.45f;
            Color color = index % 4 == 0
                ? new Color(120, 145, 255, Math.round(42 + pulse * 38))
                : new Color(145, 111, 255, Math.round(32 + pulse * 32));
            RECT.draw(matrices, x, y, size, size, size * 0.5f, color);
        }
    }

    private void drawCard() {
        var matrices = GuiRenderer.getInstance().matrices();
        int controlX = panelX + 24;
        int controlWidth = panelWidth - 48;

        RECT.draw(matrices, panelX, panelY, panelWidth, 176, 18,
            new Color(18, 20, 31, 242));
        GRADIENT.draw(matrices, panelX + panelWidth / 2.0f - 21, panelY + 18, 42, 3, 1.5f,
            new Color(145, 105, 255), new Color(92, 105, 255),
            new Color(125, 84, 238), new Color(73, 94, 232));

        Color field = license != null && license.isFocused()
            ? new Color(35, 34, 57, 250)
            : new Color(11, 13, 22, 250);
        RECT.draw(matrices, controlX, panelY + 68, controlWidth, 32, 11, field);
    }

    private void drawPanelText(float seconds) {
        var renderer = GuiRenderer.getInstance();
        var matrices = renderer.matrices();
        float center = width * 0.5f;

        Font semibold = Fonts.semibold();
        Font regular = Fonts.regular();
        semibold.drawCenteredText(matrices, "CELESTE", center, panelY + 27.5f, 10.0f,
            new Color(246, 247, 252));
        regular.drawCenteredText(matrices, "Enter your license code", center, panelY + 47.0f, 7.0f,
            new Color(151, 156, 174));

        String value = license == null ? "" : license.getText();
        String visibleValue = value.substring(0, Math.min(value.length(), LICENSE_MASK.length()));
        String remainingMask = LICENSE_MASK.substring(visibleValue.length());
        float inputSize = 6.6f;
        float inputY = panelY + 68 + (32.0f - regular.height(inputSize)) * 0.5f;
        float inputX = center - regular.width(LICENSE_MASK, inputSize) * 0.5f;
        if (!visibleValue.isEmpty()) {
            regular.drawText(matrices, visibleValue, inputX, inputY, inputSize,
                new Color(248, 249, 253));
        }
        if (!remainingMask.isEmpty()) {
            regular.drawText(matrices, remainingMask,
                inputX + regular.width(visibleValue, inputSize), inputY, inputSize,
                new Color(94, 99, 116));
        }

        if (!value.isEmpty() && license.isFocused() && !GatewayRuntime.busy()
            && ((int) (seconds * 2.0f) & 1) == 0) {
            int cursor = Math.max(0, Math.min(license.getCursor(), visibleValue.length()));
            float caretX = inputX + regular.width(visibleValue.substring(0, cursor), inputSize) + 0.8f;
            RECT.draw(matrices, caretX, panelY + 77.5f, 1.0f, 13.0f, 0.5f,
                new Color(248, 249, 253));
        }

        String status = visibleStatus();
        if (!status.isEmpty()) {
            Color color = GatewayRuntime.busy()
                ? new Color(255, 200, 120)
                : new Color(142, 148, 168);
            regular.drawCenteredText(matrices, trim(status, 58), center, panelY + 153.0f, 5.8f, color);
        }
    }

    private static String visibleStatus() {
        String status = GatewayRuntime.status();
        if (status == null || status.isBlank()
            || status.equals("Waiting for a license")
            || status.equals("Enter a license to bind this installation")) {
            return "";
        }
        return status;
    }

    private static float positiveModulo(float value, float divisor) {
        float result = value % divisor;
        return result < 0.0f ? result + divisor : result;
    }

    private static String trim(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void close() {
        // This authorization gate intentionally cannot be dismissed.
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    /** Keeps native keyboard, selection, clipboard, and narration behavior. */
    private static final class InputCapture extends TextFieldWidget {
        InputCapture(TextRenderer renderer, int x, int y, int width, int height, Text narration) {
            super(renderer, x, y, width, height, narration);
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            // Celeste's MSDF renderer draws the visible value and caret.
        }
    }
}
