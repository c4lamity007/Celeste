package net.minecraft.entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
public class Entity {
    public int age = 100, id = 7;
    public float yaw = 27, pitch = 13, lastYaw = 26, lastPitch = 12;
    public boolean vehicle, alive = true;
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public int getId() { return id; }
    public boolean hasVehicle() { return vehicle; }
    public boolean isAlive() { return alive; }
    public Vec3d getEyePos() { return new Vec3d(0, 0, 0); }
    public Box getBoundingBox() { return new Box(); }
    public Vec3d getRotationVector() { return getRotationVector(pitch, yaw); }
    public final Vec3d getRotationVector(float pitch, float yaw) {
        float p = pitch * .017453292F, y = -yaw * .017453292F;
        float horizontal = MathHelper.cos(p);
        return new Vec3d(MathHelper.sin(y) * horizontal, -MathHelper.sin(p), MathHelper.cos(y) * horizontal);
    }
}
