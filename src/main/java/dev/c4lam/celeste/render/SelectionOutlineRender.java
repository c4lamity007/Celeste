package dev.c4lam.celeste.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/**
 * Antialiased rounded selection ring with a clockwise animated highlight.
 *
 * <p>{@code phase} is measured in turns. Increasing it from {@code 0} to
 * {@code 1} makes one complete clockwise revolution.</p>
 */
public final class SelectionOutlineRender {
    private static final float SMOOTHNESS = 1.0f;

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            float thickness,
            float phase,
            float opacity
    ) {
        if (matrices == null
                || width <= 0.0f
                || height <= 0.0f
                || thickness <= 0.0f
                || opacity <= 0.0f
                || !Float.isFinite(x)
                || !Float.isFinite(y)
                || !Float.isFinite(width)
                || !Float.isFinite(height)
                || !Float.isFinite(radius)
                || !Float.isFinite(thickness)
                || !Float.isFinite(phase)
                || !Float.isFinite(opacity)) {
            return;
        }

        float maximumRadius = Math.min(width, height) * 0.5f;
        float safeRadius = Math.clamp(radius, 0.0f, maximumRadius);
        float safeThickness = Math.clamp(thickness, 0.0f, Math.min(width, height) * 0.5f);
        float normalizedPhase = phase - (float) Math.floor(phase);
        float safeOpacity = Math.clamp(opacity, 0.0f, 1.0f);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        RectRender.Bounds bounds = RectRender.Bounds.padded(x, y, width, height, SMOOTHNESS);
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.SELECTION_OUTLINE);

        builder.vertex(matrix, bounds.x(), bounds.y(), 0.0f).color(0xFFFFFFFF);
        builder.vertex(matrix, bounds.x(), bounds.bottom(), 0.0f).color(0xFFFFFFFF);
        builder.vertex(matrix, bounds.right(), bounds.bottom(), 0.0f).color(0xFFFFFFFF);
        builder.vertex(matrix, bounds.right(), bounds.y(), 0.0f).color(0xFFFFFFFF);

        renderer.submit(builder, GuiPipelines.SELECTION_OUTLINE, GuiUniform.of(
                safeRadius, safeRadius, safeRadius, safeRadius,
                width, height, SMOOTHNESS, normalizedPhase,
                safeThickness, safeOpacity, 0.0f, 0.0f
        ));
    }
}
