package dev.c4lam.celeste.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Color;

/** Rounded frosted-glass rectangles backed by an explicit blur capture. */
public final class BlurRectRender {
    private static final float SMOOTHNESS = 0.8f;

    private final KawaseBlurProgram blur;
    private final GradientRectRender fallback = new GradientRectRender();

    public BlurRectRender() {
        this(KawaseBlurProgram.getInstance());
    }

    BlurRectRender(KawaseBlurProgram blur) {
        this.blur = blur;
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            Color tint,
            float tintMix
    ) {
        draw(matrices, x, y, width, height, new Vector4f(radius), tint, tint, tint, tint, tintMix);
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            Color tint
    ) {
        draw(matrices, x, y, width, height, radius, tint, 0.38f);
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            Vector4f radius,
            Color tint
    ) {
        draw(matrices, x, y, width, height, radius, tint, tint, tint, tint, 0.38f);
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
        draw(matrices, x, y, width, height, radius, topLeft, topRight, bottomLeft, bottomRight, 0.38f);
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
            Color bottomRight,
            float tintMix
    ) {
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        float mix = Math.max(0.0f, Math.min(1.0f, tintMix));
        Framebuffer captured = blur.capturedFramebuffer();
        if (captured == null || mix >= 1.0f) {
            fallback.draw(matrices, x, y, width, height, radius, topLeft, topRight, bottomLeft, bottomRight);
            return;
        }

        float[] tl = GradientRectRender.normalize(topLeft);
        float[] bl = GradientRectRender.normalize(bottomLeft);
        float[] br = GradientRectRender.normalize(bottomRight);
        float[] tr = GradientRectRender.normalize(topRight);
        float tintAlpha = (tl[3] + bl[3] + br[3] + tr[3]) * 0.25f;

        RectRender.Bounds bounds = RectRender.Bounds.padded(x, y, width, height, SMOOTHNESS);
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();

        // X and Y are mapped independently. This remains correct with a
        // stretched window or any non-uniform GUI scaling.
        float scaleX = window.getScaledWidth() == 0
                ? 0.0f
                : (float) captured.textureWidth / window.getScaledWidth();
        float scaleY = window.getScaledHeight() == 0
                ? 0.0f
                : (float) captured.textureHeight / window.getScaledHeight();

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float screenLeft = matrix.m00() * bounds.x() + matrix.m10() * bounds.y() + matrix.m30();
        float screenTop = matrix.m01() * bounds.x() + matrix.m11() * bounds.y() + matrix.m31();
        float screenRight = matrix.m00() * bounds.right() + matrix.m10() * bounds.bottom() + matrix.m30();
        float screenBottom = matrix.m01() * bounds.right() + matrix.m11() * bounds.bottom() + matrix.m31();
        float uLeft = screenLeft * scaleX / captured.textureWidth;
        float uRight = screenRight * scaleX / captured.textureWidth;
        float vTop = 1.0f - screenTop * scaleY / captured.textureHeight;
        float vBottom = 1.0f - screenBottom * scaleY / captured.textureHeight;

        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.BLURRED_RECT);

        builder.vertex(matrix, bounds.x(), bounds.y(), 0.0f).texture(uLeft, vTop).color(topLeft.getRGB());
        builder.vertex(matrix, bounds.x(), bounds.bottom(), 0.0f).texture(uLeft, vBottom).color(bottomLeft.getRGB());
        builder.vertex(matrix, bounds.right(), bounds.bottom(), 0.0f).texture(uRight, vBottom).color(bottomRight.getRGB());
        builder.vertex(matrix, bounds.right(), bounds.y(), 0.0f).texture(uRight, vTop).color(topRight.getRGB());

        renderer.submit(
                builder,
                GuiPipelines.BLURRED_RECT,
                TextureSetup.of(captured.getColorAttachmentView(), GuiTextures.linear()),
                GuiUniform.of(
                        radius.x, radius.z, radius.w, radius.y,
                        width, height, SMOOTHNESS, mix,
                        tl[0], tl[1], tl[2], tl[3],
                        bl[0], bl[1], bl[2], bl[3],
                        br[0], br[1], br[2], br[3],
                        tr[0], tr[1], tr[2], tr[3],
                        tintAlpha, 0.0f, 0.0f, 0.0f
                )
        );
    }
}
