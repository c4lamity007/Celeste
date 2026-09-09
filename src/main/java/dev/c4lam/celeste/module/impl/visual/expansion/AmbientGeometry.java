package dev.c4lam.celeste.module.impl.visual.expansion;

/** Allocation-free scratch geometry for closed ambient cores and folded leaf blades. */
public final class AmbientGeometry {
    public static final int SCRATCH_SIZE = 33;
    public static final int LEAF_EDGE_VERTICES = 8;
    public static final int LEAF_NORMAL_OFFSET = 30;
    // Clockwise outline in the local X/Z plane, then the raised midrib and underside.
    private static final double[] LEAF = {
        0, 0, -1.60, .36, .012, -.94, .66, -.014, -.28, .43, .006, .48,
        0, 0, 1.05, -.42, -.009, .50, -.68, -.008, -.22, -.36, .015, -.94,
        0, .16, -.20, 0, -.055, -.20
    };
    private AmbientGeometry() { }

    /** Slots 0..17 are world vertices; slots 18..20 are the current face normal. */
    public static void core(double[] out, double px, double py, double pz, double radius,
                            double stretch, double yaw, double pitch) {
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        for (int i = 0; i < 6; i++) {
            double vx = i == 2 ? radius : i == 4 ? -radius : 0;
            double vy = i == 0 ? radius * stretch : i == 1 ? -radius * stretch : 0;
            double vz = i == 3 ? radius : i == 5 ? -radius : 0;
            double y = vy * cp - vz * sp, z = vy * sp + vz * cp;
            out[i * 3] = (vx * cy - z * sy) + px;
            out[i * 3 + 1] = y + py;
            out[i * 3 + 2] = (vx * sy + z * cy) + pz;
        }
    }

    public static void normal(double[] out, int a, int b, int c) {
        normal(out, a, b, c, 18);
    }

    /** Ten vertices form a tapered, asymmetric blade with a real folded ridge, not a billboard. */
    public static void leaf(double[] out, double px, double py, double pz, double radius,
                            double yaw, double pitch) {
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        for (int i = 0; i < LEAF.length; i += 3) {
            double vx = LEAF[i] * radius, vy = LEAF[i + 1] * radius, vz = LEAF[i + 2] * radius;
            double y = vy * cp - vz * sp, z = vy * sp + vz * cp;
            out[i] = (vx * cy - z * sy) + px;
            out[i + 1] = y + py;
            out[i + 2] = (vx * sy + z * cy) + pz;
        }
    }

    /** U=2..3 selects the leaf material without a new batch, texture or shader uniform. */
    public static float leafU(int index) { return (float) (2.5 + LEAF[index * 3] * .5); }
    public static float leafV(int index) { return (float) ((LEAF[index * 3 + 2] + 1.60) / 2.65); }

    public static void leafNormal(double[] out, int a, int b, int c) {
        normal(out, a, b, c, LEAF_NORMAL_OFFSET);
    }

    private static void normal(double[] out, int a, int b, int c, int normalOffset) {
        int ai = a * 3, bi = b * 3, ci = c * 3;
        double bx = out[bi] - out[ai], by = out[bi + 1] - out[ai + 1], bz = out[bi + 2] - out[ai + 2];
        double cx = out[ci] - out[ai], cy = out[ci + 1] - out[ai + 1], cz = out[ci + 2] - out[ai + 2];
        double nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-5) {
            out[normalOffset] = out[normalOffset + 1] = out[normalOffset + 2] = 0;
        } else {
            double inverse = 1.0 / length;
            out[normalOffset] = nx * inverse; out[normalOffset + 1] = ny * inverse; out[normalOffset + 2] = nz * inverse;
        }
    }
}
