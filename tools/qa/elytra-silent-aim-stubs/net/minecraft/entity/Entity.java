package net.minecraft.entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
public class Entity {
    public Vec3d getEyePos() { return new Vec3d(0, 0, 0); }
    public Box getBoundingBox() { return new Box(); }
    public final Vec3d getRotationVector(float pitch, float yaw) {
        float p = pitch * .017453292F, y = -yaw * .017453292F;
        float horizontal = MathHelper.cos(p);
        return new Vec3d(MathHelper.sin(y) * horizontal, -MathHelper.sin(p), MathHelper.cos(y) * horizontal);
    }
}
