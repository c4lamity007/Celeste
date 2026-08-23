package dev.c4lam.celeste.render.font;

import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.GuiUniform;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.Map;

/** Lightweight batched MSDF font renderer for Celeste's GUI. */
public final class Font {
    private static final float DEFAULT_SMOOTHNESS = 0.5f;

    private final String name;
    private final AbstractTexture texture;
    private final FontData.AtlasData atlas;
    private final FontData.MetricsData metrics;
    private final Map<Integer, MsdfGlyph> glyphs;
    private final Map<Integer, Map<Integer, Float>> kernings;

    Font(
            String name,
            AbstractTexture texture,
            FontData.AtlasData atlas,
            FontData.MetricsData metrics,
            Map<Integer, MsdfGlyph> glyphs,
            Map<Integer, Map<Integer, Float>> kernings
    ) {
        this.name = name;
        this.texture = texture;
        this.atlas = atlas;
        this.metrics = metrics;
        this.glyphs = Map.copyOf(glyphs);
        this.kernings = kernings;
    }

    public String name() {
        return name;
    }

    public FontData.AtlasData atlas() {
        return atlas;
    }

    public FontData.MetricsData metrics() {
        return metrics;
    }

    public void draw(MatrixStack matrices, String text, float x, float y, float size, Color color) {
        draw(matrices, text, x, y, size, color.getRGB(), 0.0f, DEFAULT_SMOOTHNESS, 0.0f, 0, 0.0f);
    }

    public void drawText(MatrixStack matrices, String text, float x, float y, float size, Color color) {
        draw(matrices, text, x, y, size, color);
    }

    public void draw(
            MatrixStack matrices,
            String text,
            float x,
            float y,
            float size,
            int argb,
            float thickness,
            float smoothness,
            float letterSpacing,
            int outlineArgb,
            float outlineThickness
    ) {
        if (text == null || text.isEmpty() || size <= 0.0f || (argb >>> 24) == 0) {
            return;
        }

        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.TEXT);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float cursorX = x;
        float baseline = y + metrics.baselineHeight() * size;
        int previous = -1;

        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n') {
                cursorX = x;
                baseline += metrics.lineHeight() * size;
                previous = -1;
                continue;
            }

            MsdfGlyph glyph = glyphs.get(codePoint);
            if (glyph == null) {
                previous = -1;
                continue;
            }

            cursorX += kerning(previous, codePoint) * size;
            cursorX += glyph.emit(matrix, builder, size, cursorX, baseline, 0.0f, argb) + letterSpacing;
            previous = codePoint;
        }

        boolean outline = outlineThickness > 0.0f && (outlineArgb >>> 24) != 0;
        float[] outlineColor = normalize(outlineArgb);
        renderer.submit(
                builder,
                GuiPipelines.TEXT,
                TextureSetup.of(texture.getGlTextureView(), GuiTextures.linear()),
                GuiUniform.of(
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, smoothness, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        atlas.range(), thickness, outline ? 1.0f : 0.0f, outlineThickness,
                        outlineColor[0], outlineColor[1], outlineColor[2], outlineColor[3]
                )
        );
    }

    public void drawCentered(MatrixStack matrices, String text, float centerX, float y, float size, Color color) {
        draw(matrices, text, centerX - width(text, size) * 0.5f, y, size, color);
    }

    public void drawCenteredText(MatrixStack matrices, String text, float centerX, float y, float size, Color color) {
        drawCentered(matrices, text, centerX, y, size, color);
    }

    public float width(String text, float size) {
        if (text == null || text.isEmpty() || size <= 0.0f) {
            return 0.0f;
        }

        float lineWidth = 0.0f;
        float maxWidth = 0.0f;
        int previous = -1;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n') {
                maxWidth = Math.max(maxWidth, lineWidth);
                lineWidth = 0.0f;
                previous = -1;
                continue;
            }

            MsdfGlyph glyph = glyphs.get(codePoint);
            if (glyph == null) {
                previous = -1;
                continue;
            }
            lineWidth += kerning(previous, codePoint) * size + glyph.advance(size);
            previous = codePoint;
        }
        return Math.max(maxWidth, lineWidth);
    }

    public float getWidth(String text, float size) {
        return width(text, size);
    }

    public float height(float size) {
        return metrics.lineHeight() * size;
    }

    public float getHeight(float size) {
        return height(size);
    }

    public static FontBuilder builder() {
        return new FontBuilder();
    }

    private float kerning(int left, int right) {
        Map<Integer, Float> rightCharacters = kernings.get(left);
        return rightCharacters == null ? 0.0f : rightCharacters.getOrDefault(right, 0.0f);
    }

    private static float[] normalize(int argb) {
        return new float[]{
                ((argb >>> 16) & 0xFF) / 255.0f,
                ((argb >>> 8) & 0xFF) / 255.0f,
                (argb & 0xFF) / 255.0f,
                ((argb >>> 24) & 0xFF) / 255.0f
        };
    }
}
