package dev.c4lam.celeste.beta.resources;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Plain-JVM storage/view contracts; deliberately does not bootstrap registries or codecs. */
public final class MemoryResourcePackStorageCheck {
    private static int assertions;

    private MemoryResourcePackStorageCheck() { }

    public static void main(String[] args) throws Exception {
        storageAndViews();
        validation();
        System.out.println("PASS memory resource storage/view contract: " + assertions
                + " assertions; actual mapped ResourcePack APIs; internal reload integration tested separately");
    }

    @SuppressWarnings("try") // Closing an individual view early is intentional.
    private static void storageAndViews() throws Exception {
        Map<String, byte[]> source = new HashMap<>();
        source.put("assets/celeste/fonts/example.json", bytes("font"));
        source.put("assets/celeste/shaders/core/example.vsh", bytes("shader"));
        source.put("assets/celeste/textures/a.png", bytes("texture"));
        source.put("assets/celeste/textures/a.png.mcmeta", bytes("sidecar"));
        source.put("assets/celeste/textures/nested/b.png", bytes("nested"));
        source.put("assets/celeste/textures2/c.png", bytes("not-child"));
        source.put("pack.png", bytes("icon"));
        MemoryResourceBytes store = new MemoryResourceBytes(source, bytes("{}"));
        check(store.retainedAssetBytes() == source.values().stream().mapToLong(value -> value.length).sum(), "retained byte count");
        source.get("assets/celeste/fonts/example.json")[0] = '!';
        source.clear();
        ResourcePackInfo info = new ResourcePackInfo("fixture", Text.literal("Fixture"), ResourcePackSource.BUILTIN, Optional.empty());
        try (MemoryResourcePack first = new MemoryResourcePack(info, store);
             MemoryResourcePack second = new MemoryResourcePack(info, store)) {
            check(read(first.open(ResourceType.CLIENT_RESOURCES, id("fonts/example.json"))).equals("font"), "defensive map/array snapshot");
            check(read(first.open(ResourceType.CLIENT_RESOURCES, id("shaders/core/example.vsh"))).equals("shader"), "shader lookup");
            check(first.getNamespaces(ResourceType.CLIENT_RESOURCES).equals(Set.of("celeste")), "client namespace");
            check(first.getNamespaces(ResourceType.SERVER_DATA).isEmpty(), "no server namespaces");
            check(first.open(ResourceType.SERVER_DATA, id("fonts/example.json")) == null, "no data lookup");
            check(first.open(ResourceType.CLIENT_RESOURCES, Identifier.of("minecraft", "fonts/example.json")) == null, "namespace isolation");
            check(first.open(ResourceType.CLIENT_RESOURCES, id("textures/../fonts/example.json")) == null, "no path traversal lookup");
            check(first.open(ResourceType.CLIENT_RESOURCES, id("missing")) == null, "missing resource");
            check(read(first.openRoot("pack.mcmeta")).equals("{}"), "root bytes available without extraction");
            check(read(first.openRoot("pack.png")).equals("icon"), "optional root icon");
            check(first.openRoot("../pack.mcmeta") == null && first.openRoot() == null, "unsafe root lookups rejected");
            check(first.openRoot("assets", "celeste", "fonts", "example.json") == null, "root cannot alias assets");
            check(list(first, "textures").equals(List.of("celeste:textures/a.png", "celeste:textures/a.png.mcmeta",
                    "celeste:textures/nested/b.png")), "sorted recursive directory-bounded listing and sidecar");
            check(list(first, "textures/nested").equals(List.of("celeste:textures/nested/b.png")), "nested listing");
            check(list(first, "").size() == 6, "empty-prefix listing");
            check(list(first, "text").isEmpty(), "directory boundary, not string prefix");
            check(list(first, "textures/").isEmpty() && list(first, "../textures").isEmpty(), "unsafe listing prefix");
            List<String> foreign = new ArrayList<>();
            first.findResources(ResourceType.CLIENT_RESOURCES, "minecraft", "", (id, supplier) -> foreign.add(id.toString()));
            first.findResources(ResourceType.SERVER_DATA, "celeste", "", (id, supplier) -> foreign.add(id.toString()));
            check(foreign.isEmpty(), "list namespace and resource type isolation");
            byte[] snapshotArray = store.bytes("assets/celeste/fonts/example.json");
            check(snapshotArray == store.bytes("assets/celeste/fonts/example.json"), "lookups reuse immutable backing array");
            InputSupplier<InputStream> supplier = first.open(ResourceType.CLIENT_RESOURCES, id("fonts/example.json"));
            try (InputStream opened = supplier.get()) {
                first.close();
                expect(FileNotFoundException.class, supplier::get, "closed view invalidates pending suppliers");
                check(first.open(ResourceType.CLIENT_RESOURCES, id("fonts/example.json")) == null, "closed view cannot reopen");
                check(opened.read() == 'f', "already opened stream can finish after view close");
                check(read(second.open(ResourceType.CLIENT_RESOURCES, id("fonts/example.json"))).equals("font"), "other view survives");
                opened.close();
                check(opened.read() == -1, "closed stream no longer reads retained bytes");
            }
            InputSupplier<InputStream> pending = second.open(ResourceType.CLIENT_RESOURCES, id("fonts/example.json"));
            store.close();
            check(store.retainedAssetBytes() == 0 && store.identifiers().isEmpty(), "shared store close clears references/index");
            check(second.getNamespaces(ResourceType.CLIENT_RESOURCES).isEmpty(), "views see closed store");
            expect(FileNotFoundException.class, pending::get, "shared close invalidates pending supplier");
            check(second.openRoot("pack.mcmeta") == null, "shared close also releases root metadata");
            store.close();
        }
    }

    private static void validation() {
        for (String name : List.of("../assets/celeste/a", "assets/celeste/../a", "assets/celeste/./a", "assets/celeste//a",
                "assets/celeste/a/", "/assets/celeste/a", "assets\\celeste\\a", "assets/celeste/Upper.png",
                "assets/minecraft/a", "data/celeste/a", "pack.mcmeta", "fabric.mod.json", "assets/celeste/a:b",
                "assets/celeste/%2e%2e/a", "assets/celeste/space name", "assets/celeste/a\u0000b")) {
            expect(IllegalArgumentException.class, () -> store(Map.of(name, new byte[0])), "reject unsafe/unscoped name");
        }
        expect(IllegalArgumentException.class, () -> store(Map.of()), "empty map");
        expect(IllegalArgumentException.class, () -> store(Map.of("pack.png", new byte[0])), "root-only map");
        expect(IllegalArgumentException.class, () -> store(Map.of("assets/celeste/large.png", new byte[32 * 1024 * 1024 + 1])),
                "entry memory bound");
        Map<String, byte[]> tooMany = new HashMap<>();
        for (int i = 0; i < 8193; i++) {
            tooMany.put("assets/celeste/" + i, new byte[0]);
        }
        expect(IllegalArgumentException.class, () -> store(tooMany), "entry-count bound");
        expect(IllegalArgumentException.class,
                () -> store(Map.of("assets/celeste/" + "x".repeat(512), new byte[0])), "name-length bound");
    }

    private static void store(Map<String, byte[]> input) {
        new MemoryResourceBytes(input, bytes("{}")).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Identifier id(String path) {
        return Identifier.of("celeste", path);
    }

    private static String read(InputSupplier<InputStream> supplier) throws Exception {
        if (supplier == null) {
            throw new AssertionError("missing resource");
        }
        try (InputStream stream = supplier.get()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> list(MemoryResourcePack pack, String prefix) {
        List<String> names = new ArrayList<>();
        pack.findResources(ResourceType.CLIENT_RESOURCES, "celeste", prefix, (id, supplier) -> names.add(id.toString()));
        return names;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Throwing action, String message) {
        assertions++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception " + failure, failure);
        }
        throw new AssertionError(message + ": did not throw");
    }

    @FunctionalInterface
    private interface Throwing {
        void run() throws Exception;
    }
}
