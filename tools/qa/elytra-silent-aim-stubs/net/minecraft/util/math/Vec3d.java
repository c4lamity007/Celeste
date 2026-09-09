package net.minecraft.util.math;

public final class Vec3d {
    public final double x, y, z;
    public Vec3d(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vec3d lerp(Vec3d other, double delta) {
        return new Vec3d(x + (other.x - x) * delta, y + (other.y - y) * delta, z + (other.z - z) * delta);
    }
    public static Vec3d fromPolar(float pitch, float yaw) {
        float a = MathHelper.cos(-yaw * .017453292F - (float) Math.PI);
        float b = MathHelper.sin(-yaw * .017453292F - (float) Math.PI);
        float c = -MathHelper.cos(-pitch * .017453292F);
        return new Vec3d(b * c, MathHelper.sin(-pitch * .017453292F), a * c);
    }
}
