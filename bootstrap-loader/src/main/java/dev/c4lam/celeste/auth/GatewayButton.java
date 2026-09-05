package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.render.GradientRectRender;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RectRender;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

final class GatewayButton extends ClickableWidget {
    private static final RectRender RECT = new RectRender();
    private static final GradientRectRender GRADIENT = new GradientRectRender();

    private final Runnable action;
    private final boolean primary;
    private float hover;
    private long lastFrame = System.nanoTime();

    GatewayButton(int x, int y, int width, int height, String label, boolean primary, Runnable action) {
        super(x, y, width, height, Text.literal(label));
        this.primary = primary;
        this.action = action;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float seconds = Math.min(0.05f, (now - lastFrame) / 1_000_000_000.0f);
        lastFrame = now;
        float target = hovered && active ? 1.0f : 0.0f;
        hover += (target - hover) * Math.min(1.0f, seconds * 14.0f);

        var matrices = GuiRenderer.getInstance().matrices();
        if (primary) {
            int alpha = active ? 255 : 105;
            int lift = Math.round(hover * 10.0f);
            GRADIENT.draw(matrices, getX(), getY(), width, height, 11,
                new Color(130 + lift, 91 + lift / 2, 246, alpha),
                new Color(84 + lift / 2, 92 + lift / 2, 230, alpha),
                new Color(106 + lift / 2, 70 + lift / 2, 220, alpha),
                new Color(69 + lift / 2, 82 + lift / 2, 213, alpha));
        } else {
            int tone = Math.round(28 + hover * 8);
            RECT.draw(matrices, getX(), getY(), width, height, 11,
                active ? new Color(tone, tone + 2, tone + 12, 245) : new Color(23, 25, 34, 170));
        }

        Font font = Fonts.semibold();
        float size = 7.8f;
        float textY = getY() + (height - font.height(size)) * 0.5f;
        Color textColor = active ? new Color(248, 248, 252) : new Color(119, 123, 138);
        font.drawCenteredText(matrices, getMessage().getString(), getX() + width * 0.5f,
            textY, size, textColor);
    }

    @Override
    public void onClick(Click click, boolean doubleClick) {
        if (active) {
            action.run();
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
