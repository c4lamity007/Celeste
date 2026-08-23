package dev.c4lam.celeste.render.font;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/** One glyph's plane geometry and normalized atlas region. */
final class MsdfGlyph {
    private final int codePoint;
    private final float minU;
    private final float maxU;
    private final float minV;
    private final float maxV;
    private final float advance;
    private final float left;
    private final float top;
    private final float width;
    private final float height;
    private final boolean drawable;

    MsdfGlyph(FontData.GlyphData data, float atlasWidth, float atlasHeight) {
        codePoint = data.unicode();
        advance = data.advance();

        FontData.BoundsData atlasBounds = data.atlasBounds();
        FontData.BoundsData planeBounds = data.planeBounds();
        drawable = atlasBounds != null && planeBounds != null;

        if (atlasBounds == null) {
            minU = maxU = minV = maxV = 0.0f;
        } else {
            minU = atlasBounds.left() / atlasWidth;
            maxU = atlasBounds.right() / atlasWidth;
            minV = 1.0f - atlasBounds.top() / atlasHeight;
            maxV = 1.0f - atlasBounds.bottom() / atlasHeight;
        }

        if (planeBounds == null) {
            left = top = width = height = 0.0f;
        } else {
            left = planeBounds.left();
            top = planeBounds.top();
            width = planeBounds.right() - planeBounds.left();
            height = planeBounds.top() - planeBounds.bottom();
        }
    }

    float emit(Matrix4f matrix, VertexConsumer consumer, float size, float x, float baseline, float z, int argb) {
        if (drawable) {
            float drawX = x + left * size;
            float drawY = baseline - top * size;
            float drawWidth = width * size;
            float drawHeight = height * size;

            consumer.vertex(matrix, drawX, drawY, z).texture(minU, minV).color(argb);
            consumer.vertex(matrix, drawX, drawY + drawHeight, z).texture(minU, maxV).color(argb);
            consumer.vertex(matrix, drawX + drawWidth, drawY + drawHeight, z).texture(maxU, maxV).color(argb);
            consumer.vertex(matrix, drawX + drawWidth, drawY, z).texture(maxU, minV).color(argb);
        }
        return advance * size;
    }

    float advance(float size) {
        return advance * size;
    }

    int codePoint() {
        return codePoint;
    }
}
