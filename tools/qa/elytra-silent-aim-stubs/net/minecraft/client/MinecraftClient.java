package net.minecraft.client;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    public ClientPlayerEntity player;
    public ClientWorld world = new ClientWorld();
    public boolean onThread = true;
    public final Options options = new Options();
    public static MinecraftClient getInstance() { return INSTANCE; }
    public boolean isOnThread() { return onThread; }
    public static final class Player extends ClientPlayerEntity { }
    public static final class Options {
        public Sensitivity getMouseSensitivity() { return new Sensitivity(); }
    }
    public static final class Sensitivity {
        public double getValue() { return .5; }
    }
}
