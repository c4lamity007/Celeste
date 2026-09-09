package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;

/** Shared production geometry emitter; one callback per batch, with no per-vertex objects. */
public final class SkyLinesMesh {
    public static final int MAX_VERTICES = SkyLinesMath.MAX_SEGMENTS * 24 + SkyLinesMath.MAX_STARS * 8;
    private static final double[] RAIL_OFFSETS = {-4, -2, -.7, 0, .7, 2, 4};
    private static final double[] RAIL_LIGHT = {0, .04, .30, .90, .30, .04, 0};
    @FunctionalInterface public interface Vertex { void vertex(double x, double y, double z, int color); }
    private SkyLinesMesh() { }
    public static void emit(SkyLinesMath.Geometry geometry, Vertex output) {
        // Geometry is already camera-relative: translation never drags the celestial sphere.
        List<SkyLinesMath.Segment> segments = geometry.segments();
        for (int i = 0; i < segments.size(); i++) {
            SkyLinesMath.Segment segment = segments.get(i);
            if (segment.width() >= .5) {
                // Broad rails share exact endpoints, normals and color across each join. Their
                // soft cross-section is one surface, not overlapping transparent glow strips.
                softRail(output, segments, i);
            } else {
                ribbon(output, segment, 7, .055);
                ribbon(output, segment, 3.2, .11);
                ribbon(output, segment, 1, .82);
            }
        }
        for (SkyLinesMath.Star star : geometry.stars()) {
            star(output, star, 4, .09);
            star(output, star, 1, .9);
        }
    }

    private static void softRail(Vertex output, List<SkyLinesMath.Segment> segments, int index) {
        var s = segments.get(index);
        var before = index > 0 && segments.get(index - 1).to().equals(s.from())
            ? segments.get(index - 1) : null;
        var after = index + 1 < segments.size() && segments.get(index + 1).from().equals(s.to())
            ? segments.get(index + 1) : null;
        var p = s.from(); var q = s.to();
        var a = before == null ? p : before.from();
        var b = after == null ? q : after.to();
        double ax = q.x() - a.x(), ay = q.y() - a.y(), az = q.z() - a.z();
        double bx = b.x() - p.x(), by = b.y() - p.y(), bz = b.z() - p.z();
        double nx = ay * p.z() - az * p.y(), ny = az * p.x() - ax * p.z(), nz = ax * p.y() - ay * p.x();
        double mx = by * q.z() - bz * q.y(), my = bz * q.x() - bx * q.z(), mz = bx * q.y() - by * q.x();
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz), ml = Math.sqrt(mx * mx + my * my + mz * mz);
        if (nl < 1.0e-8 || ml < 1.0e-8) return;
        double startWidth = before == null ? s.width() : (before.width() + s.width()) * .5;
        double endWidth = after == null ? s.width() : (s.width() + after.width()) * .5;
        nx *= startWidth / nl; ny *= startWidth / nl; nz *= startWidth / nl;
        mx *= endWidth / ml; my *= endWidth / ml; mz *= endWidth / ml;
        double startOpacity = before == null ? 0 : (before.opacity() + s.opacity()) * .5;
        double endOpacity = after == null ? 0 : (s.opacity() + after.opacity()) * .5;
        for (int lane = 0; lane < RAIL_OFFSETS.length - 1; lane++) {
            double lo = RAIL_OFFSETS[lane], hi = RAIL_OFFSETS[lane + 1];
            int lowColor = railColor(s.color(), lane), highColor = railColor(s.color(), lane + 1);
            output.vertex(p.x() + nx * lo, p.y() + ny * lo, p.z() + nz * lo,
                argb((float)(startOpacity * RAIL_LIGHT[lane]), lowColor));
            output.vertex(q.x() + mx * lo, q.y() + my * lo, q.z() + mz * lo,
                argb((float)(endOpacity * RAIL_LIGHT[lane]), lowColor));
            output.vertex(q.x() + mx * hi, q.y() + my * hi, q.z() + mz * hi,
                argb((float)(endOpacity * RAIL_LIGHT[lane + 1]), highColor));
            output.vertex(p.x() + nx * hi, p.y() + ny * hi, p.z() + nz * hi,
                argb((float)(startOpacity * RAIL_LIGHT[lane + 1]), highColor));
        }
    }

    private static int railColor(int color, int lane) {
        if (lane != 3) return color;
        return (((color >> 16 & 255) * 2 + 255) / 3) << 16
            | (((color >> 8 & 255) * 2 + 255) / 3) << 8
            | ((color & 255) * 2 + 255) / 3;
    }
    private static void ribbon(Vertex output, SkyLinesMath.Segment s,
                               double spread, double alpha) {
        SkyLinesMath.Point a = s.from(), b = s.to();
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double mx = a.x() + b.x(), my = a.y() + b.y(), mz = a.z() + b.z();
        double nx = dy * mz - dz * my, ny = dz * mx - dx * mz, nz = dx * my - dy * mx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-8) return;
        double scale = s.width() * spread / length;
        nx *= scale; ny *= scale; nz *= scale;
        int color = argb((float)(s.opacity() * alpha), s.color());
        output.vertex(a.x()-nx,a.y()-ny,a.z()-nz,color);
        output.vertex(b.x()-nx,b.y()-ny,b.z()-nz,color);
        output.vertex(b.x()+nx,b.y()+ny,b.z()+nz,color);
        output.vertex(a.x()+nx,a.y()+ny,a.z()+nz,color);
    }

    private static void star(Vertex output, SkyLinesMath.Star star,
                             double spread, double alpha) {
        SkyLinesMath.Point p = star.position();
        double horizontal = Math.hypot(p.x(), p.z());
        if (horizontal < 1.0e-8) return;
        double r = star.radius() * spread;
        double ux = -p.z() / horizontal * r, uz = p.x() / horizontal * r;
        double vx = -p.y() * p.x() / (horizontal * SkyLinesMath.RADIUS) * r;
        double vy = horizontal / SkyLinesMath.RADIUS * r;
        double vz = -p.y() * p.z() / (horizontal * SkyLinesMath.RADIUS) * r;
        int color = argb((float)(star.opacity() * alpha), star.color());
        output.vertex(p.x()-ux,p.y(),p.z()-uz,color);
        output.vertex(p.x()+vx,p.y()+vy,p.z()+vz,color);
        output.vertex(p.x()+ux,p.y(),p.z()+uz,color);
        output.vertex(p.x()-vx,p.y()-vy,p.z()-vz,color);
    }

    private static int argb(float alpha, int rgb) {
        return Math.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }
}
