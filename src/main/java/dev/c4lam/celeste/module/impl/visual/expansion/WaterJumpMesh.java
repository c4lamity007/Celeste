package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Pure low, rolling water-wave geometry shared by the effect, preview and regression checks. */
public final class WaterJumpMesh {
    public static final int MAX_WAVE_QUADS = 144;
    private static final int PRIMARY_SEGMENTS = 48;
    private static final int SECONDARY_SEGMENTS = 24;
    private static final double TAU = Math.PI * 2.0;

    private WaterJumpMesh() { }

    public record Point(double x, double y, double z) { }

    /** a/b lie on the floor (V=0), c/d share the foam crest (V=1). */
    public record Face(Point a, Point b, Point c, Point d,
                       float uStart, float uEnd, double opacity) { }

    /**
     * Age is normalized over the existing pulse lifetime. The first low wave
     * rises and falls during 0..0.70; its lower follower runs from 0.16..0.82.
     * Every admitted wave is two complete slopes, never a budget-cut arc.
     */
    public static List<Face> build(double age, double size, double phase, int maxQuads) {
        if (!Double.isFinite(age) || !Double.isFinite(size) || !Double.isFinite(phase)
            || age <= 0.0 || age >= 1.0 || size <= 0.0 || maxQuads < SECONDARY_SEGMENTS * 2) {
            return List.of();
        }
        int budget = Math.min(MAX_WAVE_QUADS, maxQuads);
        double scale = Math.max(0.6, Math.min(3.0, size));
        double rotation = phase % TAU;
        List<Face> faces = new ArrayList<>(budget);
        wave(faces, age / 0.70, scale, rotation, PRIMARY_SEGMENTS, false, budget);
        wave(faces, (age - 0.16) / 0.66, scale, rotation + 0.83,
            SECONDARY_SEGMENTS, true, budget);
        return List.copyOf(faces);
    }

    private static void wave(List<Face> faces, double t, double size, double phase,
                              int segments, boolean secondary, int budget) {
        if (t <= 0.0 || t >= 1.0 || faces.size() + segments * 2 > budget) return;
        double opacity = smooth(t / 0.075) * (1.0 - smooth((t - 0.50) / 0.50));
        if (secondary) opacity *= 0.72;
        if (opacity < 0.002) return;

        // A shallow rolling profile rises quickly and visibly drops while
        // moving outward. Wide feet and low scallops avoid a bucket-like wall.
        double heightEnvelope = Math.sin(Math.PI * Math.pow(t, 0.58));
        double expansion = 0.55 * t + 0.45 * (1.0 - (1.0 - t) * (1.0 - t));
        double radius = size * ((secondary ? 0.28 : 0.20) + (secondary ? 0.79 : 0.80) * expansion);
        double innerWidth = size * ((secondary ? 0.16 : 0.23) + (secondary ? 0.065 : 0.10) * heightEnvelope);
        double outerWidth = size * ((secondary ? 0.13 : 0.19) + (secondary ? 0.06 : 0.08) * heightEnvelope);
        double curl = size * (secondary ? 0.055 : 0.11) * heightEnvelope * (0.20 + 0.80 * t);
        double height = size * (secondary ? 0.115 : 0.255) * heightEnvelope;
        Point[] inner = new Point[segments + 1];
        Point[] outer = new Point[segments + 1];
        Point[] crest = new Point[segments + 1];

        for (int i = 0; i < segments; i++) {
            double angle = TAU * i / segments + phase;
            double ripple = size * heightEnvelope * (0.025 * Math.sin(3.0 * angle + phase)
                + 0.012 * Math.sin(7.0 * angle - phase * 1.31));
            double crestAngle = angle + 0.018 * Math.sin(4.0 * angle + phase) * heightEnvelope;
            double crestHeight = height * (0.87 + 0.09 * Math.sin(3.0 * angle + phase)
                + 0.04 * Math.sin(6.0 * angle - phase * 1.7));
            inner[i] = point(angle, Math.max(size * 0.035, radius - innerWidth + ripple * 0.45), 0.0);
            outer[i] = point(angle, radius + outerWidth + ripple * 0.70, 0.0);
            crest[i] = point(crestAngle, radius + curl + ripple, crestHeight);
        }
        // Exact shared seam: neither trig roundoff nor independently built
        // skirts can leave a visible crack at U=0/1 or along the crown lip.
        inner[segments] = inner[0];
        outer[segments] = outer[0];
        crest[segments] = crest[0];
        skirt(faces, inner, crest, segments, opacity);
        skirt(faces, outer, crest, segments, opacity);
    }

    private static void skirt(List<Face> faces, Point[] base, Point[] crest, int segments, double opacity) {
        for (int i = 0; i < segments; i++) {
            faces.add(new Face(base[i], base[i + 1], crest[i + 1], crest[i],
                (float) i / segments, (float) (i + 1) / segments, opacity));
        }
    }

    private static Point point(double angle, double radius, double height) {
        return new Point(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    private static double smooth(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
    }
}
