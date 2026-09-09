package net.minecraft.client;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
public final class MinecraftClient {
    public ClientPlayerEntity player;
    public ClientWorld world;
    public ClientPlayerInteractionManager interactionManager = new ClientPlayerInteractionManager();
    public Object currentScreen;
}
