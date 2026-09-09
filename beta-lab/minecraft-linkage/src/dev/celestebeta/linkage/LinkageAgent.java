package dev.celestebeta.linkage;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

/** Diagnostic agent: its transformer always returns null; it installs no gameplay hooks. */
public final class LinkageAgent {
    private static JarFile retainedBridge;
    private static String retainedBridgeHash;
    private static final String[][] TARGETS = {
        {"net.minecraft.client.MinecraftClient", "net.minecraft.class_310"},
        {"net.minecraft.client.render.GameRenderer", "net.minecraft.class_757"},
        {"net.minecraft.client.gui.hud.InGameHud", "net.minecraft.class_329"},
        {"net.minecraft.client.gui.screen.ChatScreen", "net.minecraft.class_408"},
        {"net.minecraft.client.render.entity.LivingEntityRenderer", "net.minecraft.class_922"},
        {"net.minecraft.entity.LivingEntity", "net.minecraft.class_1309"},
        {"net.minecraft.client.network.ClientPlayNetworkHandler", "net.minecraft.class_634"},
        {"net.minecraft.client.network.ClientPlayerEntity", "net.minecraft.class_746"},
        {"net.minecraft.client.network.ClientPlayerInteractionManager", "net.minecraft.class_636"},
        {"net.minecraft.entity.Entity", "net.minecraft.class_1297"},
        {"net.minecraft.client.input.KeyboardInput", "net.minecraft.class_743"},
        {"net.minecraft.entity.player.PlayerInventory", "net.minecraft.class_1661"},
        {"net.minecraft.client.render.SkyRendering", "net.minecraft.class_9975"},
        {"net.minecraft.client.render.fog.FogRenderer", "net.minecraft.class_758"},
        {"net.minecraft.client.render.LightmapTextureManager", "net.minecraft.class_765"},
        {"net.minecraft.entity.ItemEntity", "net.minecraft.class_1542"},
        {"net.minecraft.screen.ScreenHandler", "net.minecraft.class_1703"},
        {"net.minecraft.client.world.WorldEventHandler", "net.minecraft.class_9959"},
        {"net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen", "net.minecraft.class_480"},
        {"net.minecraft.client.option.KeyBinding", "net.minecraft.class_304"},
        {"net.minecraft.client.gui.screen.ingame.HandledScreen", "net.minecraft.class_465"}
    };

    private LinkageAgent() { }

    public static synchronized void agentmain(String encoded, Instrumentation instrumentation) throws Exception {
        Request request = Request.decode(encoded);
        ProcessHandle self = ProcessHandle.current();
        if (self.pid() != request.pid() || self.info().startInstant().orElseThrow().toEpochMilli() != request.startedAt()) {
            throw new IllegalArgumentException("Probe target identity mismatch");
        }
        Path output = Report.checkedOutput(request);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "celeste-minecraft-linkage-1");
        report.put("nonce", request.nonce());
        report.put("pid", request.pid());
        report.put("startedAt", request.startedAt());
        report.put("capturedAt", java.time.Instant.now().toString());
        report.put("status", "failed");
        report.put("bridgeAppended", false);
        report.put("transformerReturnsNull", true);
        report.put("warning", "Retransformation also invokes other registered capable transformers; captured bytes are the view at this observer, not proof of the final output of later transformers.");
        String operation = "verify-target";
        try {
            if (Runtime.version().feature() != 21) throw new IOException("Selected runtime must be Java 21");
            Class<?>[] loaded = instrumentation.getAllLoadedClasses();
            Class<?> game = uniqueGame(loaded);
            ClassLoader gameLoader = game.getClassLoader();
            if (gameLoader == null) throw new IOException("Minecraft has no application class loader");
            Class<?> fabric = Arrays.stream(loaded).filter(type -> type.getName().equals("net.fabricmc.loader.api.FabricLoader"))
                .filter(type -> visibleAs(type, gameLoader)).findFirst().orElseThrow(() -> new IOException("Expected loaded Fabric API is absent"));
            String minecraftVersion = modVersion(fabric, gameLoader, "minecraft");
            if (!minecraftVersion.equals("1.21.11")) throw new IOException("Expected Minecraft 1.21.11, found " + minecraftVersion);
            report.put("minecraft", minecraftVersion);
            report.put("fabricLoader", modVersion(fabric, gameLoader, "fabricloader"));
            report.put("gameLoaderType", gameLoader.getClass().getName());
            report.put("gameClass", game.getName());
            report.put("retransformSupported", instrumentation.isRetransformClassesSupported());
            if (!instrumentation.isRetransformClassesSupported()) throw new IOException("Selected JVM does not support retransformation");

            operation = "bridge-linkage";
            List<String> bridgeNames = bridgeNames(request.bridge(), request.bridgeHash());
            for (Class<?> type : loaded) {
                if (bridgeNames.contains(type.getName()) && (type.getClassLoader() != null || retainedBridge == null)) {
                    throw new IOException("A bridge definition is already resident outside this diagnostic registration: " + type.getName());
                }
            }
            if (retainedBridge == null) {
                retainedBridge = new JarFile(request.bridge().toFile());
                try { instrumentation.appendToBootstrapClassLoaderSearch(retainedBridge); }
                catch (RuntimeException | Error failure) { retainedBridge.close(); retainedBridge = null; throw failure; }
                retainedBridgeHash = request.bridgeHash();
            } else if (!retainedBridgeHash.equals(request.bridgeHash())) {
                throw new IOException("A different diagnostic bridge is already resident; restart the JVM first");
            }
            report.put("bridgeAppended", true);
            report.put("bridgeSha256", retainedBridgeHash);
            for (String name : bridgeNames) {
                Class<?> canonical = Class.forName(name, false, null);
                if (Class.forName(name, false, gameLoader) != canonical
                        || Class.forName(name, false, LinkageAgent.class.getClassLoader()) != canonical) {
                    throw new IOException("Game/agent cannot resolve the canonical bridge: " + name);
                }
            }
            report.put("canonicalBridgeClasses", bridgeNames);

            operation = "capture-targets";
            List<Map<String, Object>> targets = new ArrayList<>();
            List<Class<?>> selected = new ArrayList<>();
            for (String[] pair : TARGETS) {
                List<Class<?>> candidates = Arrays.stream(loaded).filter(type -> type.getName().equals(pair[0]) || type.getName().equals(pair[1]))
                    .filter(type -> type.getClassLoader() == gameLoader).toList();
                if (candidates.size() > 1) throw new IOException("Ambiguous loaded Minecraft target: " + pair[0]);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("named", pair[0]);
                target.put("loaded", !candidates.isEmpty());
                if (!candidates.isEmpty()) {
                    Class<?> type = candidates.getFirst();
                    target.put("actual", type.getName());
                    target.put("modifiable", instrumentation.isModifiableClass(type));
                    if (instrumentation.isModifiableClass(type)) selected.add(type);
                }
                targets.add(target);
            }
            report.put("targets", targets);
            Capture capture = new Capture(selected);
            instrumentation.addTransformer(capture, true);
            try { instrumentation.retransformClasses(selected.toArray(Class<?>[]::new)); }
            finally { instrumentation.removeTransformer(capture); }
            Path classes = Files.createDirectory(output.resolve("classes"));
            List<Map<String, Object>> captures = new ArrayList<>();
            for (Class<?> type : selected) {
                byte[] bytes = capture.bytes(type);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("class", type.getName());
                metadata.put("sha256", Report.hash(bytes));
                metadata.put("bytes", bytes.length);
                metadata.put("schema", ClassSchema.read(bytes, type.getName()));
                Path destination = classes.resolve(type.getName().replace('.', '/') + ".class");
                Files.createDirectories(destination.getParent());
                Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                captures.add(metadata);
            }
            report.put("captures", captures);
            report.put("capturedTargetCount", captures.size());
            report.put("allLoadedTargetsModifiable", targets.stream().allMatch(target -> !Boolean.TRUE.equals(target.get("loaded")) || Boolean.TRUE.equals(target.get("modifiable"))));
            report.put("status", "captured");
        } catch (Exception | LinkageError failure) {
            report.put("failedOperation", operation);
            report.put("errorType", failure.getClass().getName());
            report.put("error", failure.getMessage() == null ? "Probe operation failed" : failure.getMessage());
        } finally {
            Report.write(output, report);
        }
    }

    private static Class<?> uniqueGame(Class<?>[] loaded) throws IOException {
        List<Class<?>> matches = Arrays.stream(loaded).filter(type -> type.getName().equals(TARGETS[0][0]) || type.getName().equals(TARGETS[0][1])).toList();
        if (matches.size() != 1) throw new IOException("Expected exactly one already-loaded Minecraft client class");
        return matches.getFirst();
    }

    private static boolean visibleAs(Class<?> type, ClassLoader loader) {
        try { return Class.forName(type.getName(), false, loader) == type; }
        catch (ClassNotFoundException | LinkageError failure) { return false; }
    }

    private static String modVersion(Class<?> fabric, ClassLoader loader, String id) throws ReflectiveOperationException, IOException {
        Object api = fabric.getMethod("getInstance").invoke(null);
        Object candidate = fabric.getMethod("getModContainer", String.class).invoke(api, id);
        if (!(candidate instanceof Optional<?> found) || found.isEmpty()) throw new IOException("Expected Fabric metadata is absent: " + id);
        Object metadata = Class.forName("net.fabricmc.loader.api.ModContainer", false, loader).getMethod("getMetadata").invoke(found.get());
        Object version = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, loader).getMethod("getVersion").invoke(metadata);
        return (String) Class.forName("net.fabricmc.loader.api.Version", false, loader).getMethod("getFriendlyString").invoke(version);
    }

    private static List<String> bridgeNames(Path jar, String expectedHash) throws IOException {
        if (!Report.hash(Files.readAllBytes(jar)).equals(expectedHash)) throw new IOException("Diagnostic bridge digest changed");
        try (JarFile archive = new JarFile(jar.toFile())) {
            List<String> names = new ArrayList<>();
            for (var entry : archive.stream().toList()) {
                if (entry.isDirectory() || entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                if (!entry.getName().startsWith("dev/celestebeta/host/") || !entry.getName().endsWith(".class")) {
                    throw new IOException("Bridge JAR contains non-bridge content");
                }
                names.add(entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.'));
            }
            if (!names.contains("dev.celestebeta.host.ResourceReloadBridge")) throw new IOException("Canonical resource bridge is missing");
            Collections.sort(names);
            return List.copyOf(names);
        }
    }

    private static final class Capture implements ClassFileTransformer {
        private final Set<Class<?>> expected = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Class<?>, byte[]> captured = new IdentityHashMap<>();
        private boolean duplicate;
        private Capture(List<Class<?>> types) { expected.addAll(types); }
        @Override public synchronized byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                                        ProtectionDomain domain, byte[] bytes) {
            if (redefined != null && expected.contains(redefined)) {
                if (captured.put(redefined, bytes.clone()) != null) duplicate = true;
            }
            return null;
        }
        private synchronized byte[] bytes(Class<?> type) throws IOException {
            byte[] result = captured.get(type);
            if (duplicate || result == null) throw new IOException("Target was not captured exactly once: " + type.getName());
            return result;
        }
    }
}
