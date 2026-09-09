package net.fabricmc.loader.api;
import java.util.Optional;
public final class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    public static FabricLoader getInstance() { return INSTANCE; }
    public Optional<ModContainer> getModContainer(String id) {
        String version = switch (id) { case "minecraft" -> "1.21.11"; case "fabricloader" -> "0.19.3-fixture"; default -> null; };
        return version == null ? Optional.empty() : Optional.of(() -> () -> () -> version);
    }
}
