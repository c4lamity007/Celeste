package dev.c4lam.celeste.module;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
public final class ModuleContext {
    private final MinecraftClient client;
    public final Map<String, Double> sliders = new HashMap<>();
    public ModuleContext(MinecraftClient client) { this.client = client; }
    public MinecraftClient client() { return client; }
    public double slider(String label, double fallback) { return sliders.getOrDefault(label, fallback); }
}
