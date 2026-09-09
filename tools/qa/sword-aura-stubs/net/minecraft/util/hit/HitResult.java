package net.minecraft.util.hit;
import net.minecraft.util.math.Vec3d;
public class HitResult {
    private final Vec3d pos;
    public HitResult(Vec3d pos) { this.pos = pos; }
    public Vec3d getPos() { return pos; }
}
