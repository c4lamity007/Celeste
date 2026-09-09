package net.minecraft.util.math;
/** Value adapter only; no world, targeting, or combat logic. */
public record Vec3d(double x, double y, double z) {
    public Vec3d subtract(Vec3d other) { return new Vec3d(x - other.x, y - other.y, z - other.z); }
    public Vec3d multiply(double a, double b, double c) { return new Vec3d(x * a, y * b, z * c); }
    public Vec3d normalize() {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-4 ? new Vec3d(0, 0, 0) : multiply(1 / length, 1 / length, 1 / length);
    }
    public double dotProduct(Vec3d other) { return x * other.x + y * other.y + z * other.z; }
}
