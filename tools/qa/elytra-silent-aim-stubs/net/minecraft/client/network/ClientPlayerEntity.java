package net.minecraft.client.network;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

public class ClientPlayerEntity extends LivingEntity {
    public Vec3d velocity = new Vec3d(0, -.2, 1);
    public final FlightState state = new FlightState();
    public Vec3d getVelocity() { return velocity; }
    public FlightState getState() { return state; }
    public ClientWorld getEntityWorld() { return MinecraftClient.getInstance().world; }
    public ClientWorld getWorld() { return getEntityWorld(); }
    public static final class FlightState {
        public Vec3d velocity = new Vec3d(0, -.1, 1);
        public Vec3d getVelocity() { return velocity; }
    }
}
