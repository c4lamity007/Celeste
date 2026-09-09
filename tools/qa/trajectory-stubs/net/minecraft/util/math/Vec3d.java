package net.minecraft.util.math;

/** Value-only adapter for isolated geometry tests. Not a Minecraft integration mock. */
public final class Vec3d {
    public final double x, y, z;
    public Vec3d(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vec3d add(Vec3d v) { return new Vec3d(x + v.x, y + v.y, z + v.z); }
    public Vec3d subtract(Vec3d v) { return new Vec3d(x - v.x, y - v.y, z - v.z); }
    public Vec3d multiply(double s) { return new Vec3d(x * s, y * s, z * s); }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double squaredDistanceTo(Vec3d v) { return subtract(v).lengthSquared(); }
    public double distanceTo(Vec3d v) { return Math.sqrt(squaredDistanceTo(v)); }
    public double dotProduct(Vec3d v) { return x * v.x + y * v.y + z * v.z; }
    public Vec3d crossProduct(Vec3d v) { return new Vec3d(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x); }
    public Vec3d normalize() { double length = Math.sqrt(lengthSquared()); return length < 1e-4 ? new Vec3d(0, 0, 0) : multiply(1 / length); }
    public Vec3d lerp(Vec3d v, double t) { return new Vec3d(x + (v.x - x) * t, y + (v.y - y) * t, z + (v.z - z) * t); }
}
