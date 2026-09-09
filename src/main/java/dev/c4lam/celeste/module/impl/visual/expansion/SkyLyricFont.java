package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.media.TimedLyrics;
import dev.c4lam.celeste.render.font.FontData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable SF Pro atlas layout and bounded world-text mesh, without Minecraft/GPU state. */
public final class SkyLyricFont {
    public static final float EM_SCALE = .24f;
    public static final float MAX_WIDTH = 8.26875f / EM_SCALE;
    public static final int MAX_CHARACTERS = 180;
    public static final int MAX_VERTICES = MAX_CHARACTERS * 8;
    private static final float PACK = 32767;
    private final Map<Integer, FontData.GlyphData> glyphs = new HashMap<>();
    private final Map<Long, Float> kernings = new HashMap<>();
    private final float atlasWidth, atlasHeight;
    public record Glyph(int sourceChar, float left, float top, float right, float bottom,
                        float minU, float minV, float maxU, float maxV) { }
    public record Layout(List<Glyph> glyphs, float width, boolean truncated) { }
    @FunctionalInterface public interface Vertex {
        void vertex(float x, float y, float u, float v, int color, int localX, int localY,
                    int evaporation, int seed, int vaporLayer);
    }
    public SkyLyricFont(FontData data) {
        if (data == null || data.atlas() == null || data.metrics() == null || data.glyphs() == null
            || !Float.isFinite(data.atlas().width()) || !Float.isFinite(data.atlas().height())
            || data.atlas().width() <= 0 || data.atlas().height() <= 0 || !Float.isFinite(data.atlas().range())
            || Math.abs(data.atlas().range() - 12) > .001f)
            throw new IllegalArgumentException("Invalid SF Pro lyric atlas metadata");
        atlasWidth = data.atlas().width(); atlasHeight = data.atlas().height();
        for (var glyph : data.glyphs()) {
            if (glyph == null || !Float.isFinite(glyph.advance()) || glyph.advance() < 0) continue;
            if (glyph.planeBounds() != null && (!finite(glyph.planeBounds()) || !finite(glyph.atlasBounds()))) continue;
            glyphs.put(glyph.unicode(), glyph);
        }
        if (!glyphs.containsKey((int)'?') || !glyphs.containsKey((int)' '))
            throw new IllegalArgumentException("Lyric atlas is missing its fallback glyphs");
        if (data.kernings() != null) for (var pair : data.kernings())
            if (pair != null && Float.isFinite(pair.advance())) kernings.put(key(pair.leftChar(), pair.rightChar()), pair.advance());
    }
    private static boolean finite(FontData.BoundsData b) {
        return b != null && Float.isFinite(b.left()) && Float.isFinite(b.right())
            && Float.isFinite(b.top()) && Float.isFinite(b.bottom()) && b.right() >= b.left() && b.top() >= b.bottom();
    }
    public Layout layout(String source) {
        if (source == null || source.isEmpty()) return new Layout(List.of(), 0, false);
        int limit = Math.min(MAX_CHARACTERS, source.length());
        if (limit < source.length() && limit > 0 && Character.isHighSurrogate(source.charAt(limit - 1))) limit--;
        int end = fit(source, limit, MAX_WIDTH - .35f);
        boolean truncated = end < source.length();
        var ellipsis = glyphs.getOrDefault(0x2026, glyphs.get((int)'?'));
        if (truncated) end = fit(source, end, MAX_WIDTH - ellipsis.advance() - .5f);
        List<Glyph> result = new ArrayList<>(Math.min(end + 1, MAX_CHARACTERS));
        float cursor = 0; int previous = -1;
        for (int offset = 0; offset < end;) {
            int sourceOffset = offset, cp = source.codePointAt(offset); offset += Character.charCount(cp);
            var glyph = glyph(cp); cursor += kerning(previous, glyph.unicode());
            append(result, glyph, sourceOffset, cursor); cursor += glyph.advance(); previous = glyph.unicode();
        }
        if (truncated && result.size() < MAX_CHARACTERS) {
            cursor += kerning(previous, ellipsis.unicode());
            append(result, ellipsis, end, cursor); cursor += ellipsis.advance();
        }
        float left = 0, right = cursor;
        for (var glyph : result) { left = Math.min(left, glyph.left()); right = Math.max(right, glyph.right()); }
        float center = (left + right) * .5f;
        List<Glyph> centered = new ArrayList<>(result.size());
        for (var glyph : result) centered.add(new Glyph(glyph.sourceChar(), glyph.left() - center, glyph.top(),
            glyph.right() - center, glyph.bottom(), glyph.minU(), glyph.minV(), glyph.maxU(), glyph.maxV()));
        return new Layout(List.copyOf(centered), right - left, truncated);
    }
    private int fit(String source, int limit, float budget) {
        float cursor = 0; int previous = -1, offset = 0;
        while (offset < limit) {
            int cp = source.codePointAt(offset), next = offset + Character.charCount(cp);
            if (next > limit) break;
            var glyph = glyph(cp); float width = kerning(previous, glyph.unicode()) + glyph.advance();
            if (cursor + width > budget) break;
            cursor += width; previous = glyph.unicode(); offset = next;
        }
        return offset;
    }
    private FontData.GlyphData glyph(int cp) {
        if (Character.isWhitespace(cp) || Character.isISOControl(cp)) cp = ' ';
        return glyphs.getOrDefault(cp, glyphs.get((int)'?'));
    }
    private float kerning(int a, int b) { return kernings.getOrDefault(key(a, b), 0f); }
    private static long key(int a, int b) { return ((long)a << 32) | (b & 0xFFFFFFFFL); }
    private void append(List<Glyph> result, FontData.GlyphData g, int sourceChar, float x) {
        var p = g.planeBounds(); var a = g.atlasBounds();
        if (p == null || a == null) return;
        result.add(new Glyph(sourceChar, x + p.left(), -p.top(), x + p.right(), -p.bottom(),
            a.left() / atlasWidth, 1 - a.top() / atlasHeight, a.right() / atlasWidth, 1 - a.bottom() / atlasHeight));
    }
    /** Layer0 is anchored MSDF text, layer1 is only the upwards evaporating remnant. */
    public static void emit(Layout layout, TimedLyrics.WordCue cue, long playbackMs, float opacity,
                            int color, int endColor, int gradient, int layer, Vertex output) {
        if (layout == null || cue == null || cue.text().isEmpty() || !Float.isFinite(opacity) || opacity <= 0) return;
        int wordIndex = 0;
        for (var glyph : layout.glyphs()) {
            while (wordIndex + 1 < cue.words().size() && cue.words().get(wordIndex).endChar() <= glyph.sourceChar()) wordIndex++;
            var word = cue.words().isEmpty() ? null : cue.words().get(wordIndex);
            float phase = word == null ? 0 : evaporation(word, playbackMs);
            if (layer == 0 && phase >= 1 || layer == 1 && (phase <= .20f || phase >= 2)) continue;
            int packedPhase = Math.round(unit(phase * .5f) * PACK);
            int seed = ((word == null ? glyph.sourceChar() : word.startChar()) * 521 + 173) & 32767;
            float top = glyph.top(), bottom = glyph.bottom();
            if (layer == 1) {
                float lift = Math.max(0, phase - .2f);
                top -= lift * .48f + .16f;
                bottom -= lift * .18f;
            }
            emitVertex(output, layout, glyph.left(), top, glyph.top(), glyph.minU(), glyph.minV(), opacity, color, endColor, gradient, packedPhase, seed, layer);
            emitVertex(output, layout, glyph.left(), bottom, glyph.bottom(), glyph.minU(), glyph.maxV(), opacity, color, endColor, gradient, packedPhase, seed, layer);
            emitVertex(output, layout, glyph.right(), bottom, glyph.bottom(), glyph.maxU(), glyph.maxV(), opacity, color, endColor, gradient, packedPhase, seed, layer);
            emitVertex(output, layout, glyph.right(), top, glyph.top(), glyph.maxU(), glyph.minV(), opacity, color, endColor, gradient, packedPhase, seed, layer);
        }
    }
    public static float evaporation(TimedLyrics.Word word, long playbackMs) {
        if (word == null || playbackMs <= word.startMs()) return 0;
        if (playbackMs < word.endMs()) return (float)unit(word.progress(playbackMs));
        return 1 + unit((float)(((double)playbackMs - word.endMs()) / 650.0));
    }
    private static void emitVertex(Vertex output, Layout layout, float x, float y, float originalY,
                                   float u, float v, float opacity, int color, int endColor, int gradient,
                                   int phase, int seed, int layer) {
        float t = gradient == 1 ? unit(x / Math.max(.001f, layout.width()) + .5f)
            : gradient == 2 ? unit((originalY + .83f) / 1.05f) : 0;
        int argb = Math.round(unit(opacity) * 255) << 24 | mix(color, endColor, t);
        output.vertex(x, y, u, v, argb, Math.round(unit(x / (MAX_WIDTH * 2) + .5f) * PACK),
            Math.round(unit((originalY + 1.5f) / 3) * PACK), phase, seed, layer);
    }
    public static int mix(int a, int b, float t) {
        t = unit(t); int r = 0;
        for (int shift = 0; shift <= 16; shift += 8)
            r |= Math.round(((a >> shift) & 255) * (1 - t) + ((b >> shift) & 255) * t) << shift;
        return r;
    }
    private static float unit(float x) { return Float.isFinite(x) ? Math.clamp(x, 0, 1) : 0; }
    private static double unit(double x) { return Double.isFinite(x) ? Math.clamp(x, 0, 1) : 0; }
}
