package dev.c4lam.celeste.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.awt.Color;

/** Tinted texture regions with optional rounded corners. */
public final class TextureRectRender {
    private static final float SMOOTHNESS = 0.8f;

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            Color color,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            Identifier texture
    ) {
        draw(matrices, x, y, width, height, new Vector4f(radius), color, u, v, textureWidth, textureHeight, GuiTextures.view(texture));
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            float radius,
            Color color,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            GpuTextureView texture
    ) {
        draw(matrices, x, y, width, height, new Vector4f(radius), color, u, v, textureWidth, textureHeight, texture);
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            Vector4f radius,
            Color color,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            Identifier texture
    ) {
        draw(matrices, x, y, width, height, radius, color, u, v, textureWidth, textureHeight, GuiTextures.view(texture));
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            Vector4f radius,
            Color color,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            GpuTextureView texture
    ) {
        draw(
                matrices,
                x,
                y,
                width,
                height,
                radius,
                color,
                u,
                v,
                textureWidth,
                textureHeight,
                texture,
                // GUI artwork is routinely reduced from its resource size;
                // linear sampling keeps small monochrome glyphs smooth.
                GuiTextures.linear(),
                false
        );
    }

    public void draw(
            MatrixStack matrices,
            float x,
            float y,
            float width,
            float height,
            Vector4f radius,
            Color color,
            float u,
            float v,
            float textureWidth,
            float textureHeight,
            GpuTextureView texture,
            GpuSampler sampler,
            boolean keepUvInsideRegion
    ) {
        if (width <= 0.0f || height <= 0.0f || color.getAlpha() == 0) {
            return;
        }

        float smoothness = hasRadius(radius) ? SMOOTHNESS : 0.0f;
        RectRender.Bounds bounds = RectRender.Bounds.padded(x, y, width, height, smoothness);

        // U and V intentionally use independent scales: non-square destinations
        // and non-square source regions must not distort one another.
        float uScale = textureWidth / width;
        float vScale = textureHeight / height;
        float xPadding = (bounds.width() - width) * 0.5f;
        float yPadding = (bounds.height() - height) * 0.5f;
        float uMin = keepUvInsideRegion ? u : u - xPadding * uScale;
        float vMin = keepUvInsideRegion ? v : v - yPadding * vScale;
        float uMax = keepUvInsideRegion ? u + textureWidth : uMin + bounds.width() * uScale;
        float vMax = keepUvInsideRegion ? v + textureHeight : vMin + bounds.height() * vScale;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.TEXTURE_RECT);
        int argb = color.getRGB();

        builder.vertex(matrix, bounds.x(), bounds.y(), 0.0f).texture(uMin, vMin).color(argb);
        builder.vertex(matrix, bounds.x(), bounds.bottom(), 0.0f).texture(uMin, vMax).color(argb);
        builder.vertex(matrix, bounds.right(), bounds.bottom(), 0.0f).texture(uMax, vMax).color(argb);
        builder.vertex(matrix, bounds.right(), bounds.y(), 0.0f).texture(uMax, vMin).color(argb);

        renderer.submit(
                builder,
                GuiPipelines.TEXTURE_RECT,
                TextureSetup.of(texture, sampler),
                RectRender.shapeUniform(radius, width, height, smoothness)
        );
    }

    private static boolean hasRadius(Vector4f radius) {
        return radius.x != 0.0f || radius.y != 0.0f || radius.z != 0.0f || radius.w != 0.0f;
    }
}
