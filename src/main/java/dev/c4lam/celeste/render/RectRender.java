package dev.c4lam.celeste.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Color;

/** Rounded, antialiased solid-color rectangles. */
public final class RectRender {
    private static final float SMOOTHNESS = 0.8f;

    public void draw(MatrixStack matrices, float x, float y, float width, float height, float radius, Color color) {
        draw(matrices, x, y, width, height, new Vector4f(radius), color);
    }

    public void draw(MatrixStack matrices, float x, float y, float width, float height, Vector4f radius, Color color) {
        if (width <= 0.0f || height <= 0.0f || color.getAlpha() == 0) {
            return;
        }

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        Bounds bounds = Bounds.padded(x, y, width, height, SMOOTHNESS);
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.RECT);
        int argb = color.getRGB();

        builder.vertex(matrix, bounds.x(), bounds.y(), 0.0f).color(argb);
        builder.vertex(matrix, bounds.x(), bounds.bottom(), 0.0f).color(argb);
        builder.vertex(matrix, bounds.right(), bounds.bottom(), 0.0f).color(argb);
        builder.vertex(matrix, bounds.right(), bounds.y(), 0.0f).color(argb);

        renderer.submit(builder, GuiPipelines.RECT, shapeUniform(radius, width, height, SMOOTHNESS));
    }

    static GuiUniform shapeUniform(Vector4f radius, float width, float height, float smoothness) {
        return GuiUniform.of(
                radius.x, radius.z, radius.w, radius.y,
                width, height, smoothness, 0.0f
        );
    }

    record Bounds(float x, float y, float width, float height) {
        static Bounds padded(float x, float y, float width, float height, float padding) {
            float totalPadding = padding * 1.5f;
            return new Bounds(
                    x - totalPadding * 0.5f,
                    y - totalPadding * 0.5f,
                    width + totalPadding,
                    height + totalPadding
            );
        }

        float right() {
            return x + width;
        }

        float bottom() {
            return y + height;
        }
    }
}
