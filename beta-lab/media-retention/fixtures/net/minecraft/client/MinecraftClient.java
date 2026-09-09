package net.minecraft.client;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.texture.TextureManager;
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final TextureManager textures = new TextureManager();
    public static MinecraftClient getInstance() { return INSTANCE; }
    public TextureManager getTextureManager() { return textures; }
    public void execute(Runnable action) { queue.add(action); }
    public int queued() { return queue.size(); }
    public void pump() { for (Runnable action; (action = queue.poll()) != null;) action.run(); }
}
