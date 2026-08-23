package dev.c4lam.celeste.render.font;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Gson model for an msdf-atlas-gen JSON file. */
public final class FontData {
    private AtlasData atlas;
    private MetricsData metrics;
    private List<GlyphData> glyphs;
    @SerializedName("kerning")
    private List<KerningData> kernings;

    public AtlasData atlas() {
        return atlas;
    }

    public MetricsData metrics() {
        return metrics;
    }

    public List<GlyphData> glyphs() {
        return glyphs;
    }

    public List<KerningData> kernings() {
        return kernings;
    }

    public static final class AtlasData {
        @SerializedName("distanceRange")
        private float range;
        private float width;
        private float height;

        public float range() {
            return range;
        }

        public float width() {
            return width;
        }

        public float height() {
            return height;
        }
    }

    public static final class MetricsData {
        private float lineHeight;
        private float ascender;
        private float descender;

        public float lineHeight() {
            return lineHeight;
        }

        public float ascender() {
            return ascender;
        }

        public float descender() {
            return descender;
        }

        public float baselineHeight() {
            return lineHeight + descender;
        }
    }

    public static final class GlyphData {
        private int unicode;
        private float advance;
        private BoundsData planeBounds;
        private BoundsData atlasBounds;

        public int unicode() {
            return unicode;
        }

        public float advance() {
            return advance;
        }

        public BoundsData planeBounds() {
            return planeBounds;
        }

        public BoundsData atlasBounds() {
            return atlasBounds;
        }
    }

    public static final class BoundsData {
        private float left;
        private float top;
        private float right;
        private float bottom;

        public float left() {
            return left;
        }

        public float top() {
            return top;
        }

        public float right() {
            return right;
        }

        public float bottom() {
            return bottom;
        }
    }

    public static final class KerningData {
        @SerializedName("unicode1")
        private int leftChar;
        @SerializedName("unicode2")
        private int rightChar;
        private float advance;

        public int leftChar() {
            return leftChar;
        }

        public int rightChar() {
            return rightChar;
        }

        public float advance() {
            return advance;
        }
    }
}
