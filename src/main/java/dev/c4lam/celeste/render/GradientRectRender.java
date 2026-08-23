package dev.c4lam.celeste.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Color;

/** Four-corner rounded gradient rectangles. */
public final class GradientRectRender {
    private static final float SMOOTHNESS = 1.0f;

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            Color topLeft,
            Color topRight,
            Color bottomLeft,
            Color bottomRight
    ) {
        draw(matrices, x, y, width, height, new Vector4f(radius), topLeft, topRight, bottomLeft, bottomRight);
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            Vector4f radius,
            Color topLeft,
            Color topRight,
            Color bottomLeft,
            Color bottomRight
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        RectRender.Bounds bounds = RectRender.Bounds.padded(x, y, width, height, SMOOTHNESS);
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.GRADIENT_RECT);

        builder.vertex(matrix, bounds.x(), bounds.y(), 0.0f).color(topLeft.getRGB());
        builder.vertex(matrix, bounds.x(), bounds.bottom(), 0.0f).color(bottomLeft.getRGB());
        builder.vertex(matrix, bounds.right(), bounds.bottom(), 0.0f).color(bottomRight.getRGB());
        builder.vertex(matrix, bounds.right(), bounds.y(), 0.0f).color(topRight.getRGB());

        float[] tl = normalize(topLeft);
        float[] bl = normalize(bottomLeft);
        float[] br = normalize(bottomRight);
        float[] tr = normalize(topRight);
        renderer.submit(builder, GuiPipelines.GRADIENT_RECT, GuiUniform.of(
                radius.x, radius.z, radius.w, radius.y,
                width, height, SMOOTHNESS, 0.0f,
                tl[0], tl[1], tl[2], tl[3],
                bl[0], bl[1], bl[2], bl[3],
                br[0], br[1], br[2], br[3],
                tr[0], tr[1], tr[2], tr[3]
        ));
    }

    static float[] normalize(Color color) {
        return new float[]{
                color.getRed() / 255.0f,
                color.getGreen() / 255.0f,
                color.getBlue() / 255.0f,
                color.getAlpha() / 255.0f
        };
    }
}
