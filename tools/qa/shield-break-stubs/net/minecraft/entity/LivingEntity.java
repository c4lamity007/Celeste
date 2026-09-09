package net.minecraft.entity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
/** Explicit query/state boundary; does not simulate shield activation or the game tick. */
public class LivingEntity {
    public int id = 7;
    public ClientWorld world;
    public boolean alive = true;
    public boolean removed;
    public ItemStack heldShield;
    public ItemStack blockingItem;
    public int blockingQueries;
    public float headYaw;
    public float queriedPitch = Float.NaN;
    public float queriedYaw = Float.NaN;
    public Vec3d facing = new Vec3d(0, 0, 1);
    public Vec3d position = new Vec3d(0, 0, 0);
    public int getId() { return id; }
    public ClientWorld getEntityWorld() { return world; }
    public boolean isAlive() { return alive; }
    public boolean isRemoved() { return removed; }
    public ItemStack getBlockingItem() { blockingQueries++; return blockingItem; }
    public float getHeadYaw() { return headYaw; }
    public Vec3d getRotationVector(float pitch, float yaw) {
        queriedPitch = pitch; queriedYaw = yaw;
        return facing;
    }
    public Vec3d getEntityPos() { return position; }
}
