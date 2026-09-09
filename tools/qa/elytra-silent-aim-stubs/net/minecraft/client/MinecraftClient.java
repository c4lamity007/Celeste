package net.minecraft.client;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    public Player player;
    public static MinecraftClient getInstance() { return INSTANCE; }
    public static final class Player extends Entity {
        public int age = 100, id = 7;
        public boolean gliding = true;
        public float yaw = 27, pitch = 13;
        public Vec3d velocity = new Vec3d(0, -.2, 1);
        public final FlightState state = new FlightState();
        public boolean isGliding() { return gliding; }
        public int getId() { return id; }
        public Vec3d getVelocity() { return velocity; }
        public FlightState getState() { return state; }
    }
    public static final class FlightState {
        public Vec3d velocity = new Vec3d(0, -.1, 1);
        public Vec3d getVelocity() { return velocity; }
    }
}
