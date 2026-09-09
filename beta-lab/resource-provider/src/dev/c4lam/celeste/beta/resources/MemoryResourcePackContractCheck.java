package dev.c4lam.celeste.beta.resources;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackCompatibility;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.PackResourceMetadata;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.dynamic.Range;

/** Headless internal reload-layer checks using actual 1.21.11 classes, not API stubs. */
public final class MemoryResourcePackContractCheck {
    private static final ResourceType CLIENT = ResourceType.CLIENT_RESOURCES;
    private static int assertions;

    private MemoryResourcePackContractCheck() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        // Actual vanilla metadata codecs require the normal registry bootstrap.
        Bootstrap.initialize();
        lateVerifiedInstallation();
        augmentationAndChooserIsolation();
        lookupAndLifetimes();
        reloadTransitions();
        failureTransitions();
        validation();
        System.out.println("PASS internal memory resource-layer contract: " + assertions
                + " assertions; actual mapped Minecraft APIs; no game/render verification");
    }

    private static void lateVerifiedInstallation() throws Exception {
        List<ResourcePack> original = new ArrayList<>();
        try (InternalMemoryResources layer = new InternalMemoryResources()) {
            check(layer.retainedAssetBytes() == 0 && !layer.isActive(), "empty layer starts dormant");
            check(layer.augmentReloadPacks(original) == original, "empty layer returns exact input list");
            AtomicInteger reloads = new AtomicInteger();
            expect(IllegalStateException.class, () -> layer.activateAndReload(() -> {
                reloads.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }), "activation without verified assets rejected");
            check(reloads.get() == 0 && !layer.isActive(), "empty activation performs no reload");
            Map<String, byte[]> invalid = new LinkedHashMap<>();
            invalid.put("assets/celeste/valid.txt", bytes("must-not-publish"));
            invalid.put("assets/celeste/../invalid.txt", bytes("invalid"));
            expect(IllegalArgumentException.class, () -> layer.installVerifiedAssets(invalid), "failed install validation");
            check(layer.retainedAssetBytes() == 0 && layer.augmentReloadPacks(original) == original,
                    "failed install publishes no partial bytes or view");
            Map<String, byte[]> verified = fixtures();
            layer.installVerifiedAssets(verified);
            verified.get("assets/celeste/fonts/sf_pro/sf_regular.json")[0] = '!';
            verified.clear();
            check(layer.retainedAssetBytes() > 0 && !layer.isActive(), "corrected installation stays dormant");
            check(layer.augmentReloadPacks(original) == original, "installed inactive layer returns exact input");
            expect(IllegalStateException.class, () -> layer.installVerifiedAssets(fixtures()), "one-shot install cannot replace bytes");
            CompletableFuture<Void> inFlight = new CompletableFuture<>();
            CompletableFuture<Void> activation = layer.activateAndReload(() -> inFlight);
            expect(IllegalStateException.class, () -> layer.installVerifiedAssets(fixtures()), "install during reload rejected");
            inFlight.complete(null);
            activation.join();
            try (ResourcePack pack = layer.augmentReloadPacks(original).getLast()) {
                check(read(pack.open(CLIENT, id("fonts/sf_pro/sf_regular.json"))).startsWith("{"),
                        "late-installed defensive snapshot reaches actual pack");
            }
            expect(IllegalStateException.class, () -> layer.installVerifiedAssets(fixtures()), "install while active rejected");
            layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            expect(IllegalStateException.class, () -> layer.installVerifiedAssets(fixtures()), "deactivation does not reset one-shot install");
        }
        InternalMemoryResources closed = new InternalMemoryResources();
        closed.close();
        expect(IllegalStateException.class, () -> closed.installVerifiedAssets(fixtures()), "closed empty layer rejects install");
        check(closed.retainedAssetBytes() == 0 && closed.augmentReloadPacks(original) == original,
                "closed empty layer retains no bytes and returns exact input");
    }

    @SuppressWarnings("try") // Explicit early close is the behavior under test.
    private static void augmentationAndChooserIsolation() throws Exception {
        try (FixtureChooser chooser = new FixtureChooser();
             InternalMemoryResources layer = new InternalMemoryResources(fixtures())) {
            List<ResourcePack> original = chooser.packs;
            List<ResourcePack> originalSnapshot = List.copyOf(original);
            check(layer.augmentReloadPacks(original) == original, "inactive layer leaves chooser input unchanged");
            chooser.assertUntouched("before activation");
            AtomicInteger reloads = new AtomicInteger();
            AtomicReference<List<ResourcePack>> requestedPacks = new AtomicReference<>();
            layer.activateAndReload(() -> {
                reloads.incrementAndGet();
                check(layer.isActive(), "layer activates before host collects reload inputs");
                requestedPacks.set(layer.augmentReloadPacks(original));
                return CompletableFuture.completedFuture(null);
            }).join();
            List<ResourcePack> augmented = requestedPacks.get();
            try (ResourcePack own = augmented.getLast()) {
                check(reloads.get() == 1 && layer.isActive(), "activation requests exactly one host reload");
                check(augmented != original && augmented.size() == original.size() + 1,
                        "active layer appends exactly one view to a new list");
                check(sameReferences(augmented.subList(0, original.size()), originalSnapshot),
                        "all selected pack identities and precedence order are preserved");
                check(sameReferences(original, originalSnapshot), "augmentation never mutates original list");
                check(own instanceof MemoryResourcePack, "internal-only memory view is appended last");
                expect(UnsupportedOperationException.class, () -> augmented.add(original.getFirst()), "augmented list is immutable");
                check(layer.augmentReloadPacks(augmented) == augmented, "same-layer augmentation is exact-list idempotent");
                chooser.assertUntouched("after activation and augmentation");
                check(read(original.getFirst().open(CLIENT, Identifier.of("fixture", "first.txt"))).equals("fixture:b"),
                        "augmentation leaves unrelated pack lookup intact");
                List<ResourcePack> mutableInput = new ArrayList<>(original);
                List<ResourcePack> copied = layer.augmentReloadPacks(mutableInput);
                try (ResourcePack copiedView = copied.getLast()) {
                    mutableInput.clear();
                    check(copied.size() == original.size() + 1
                                    && sameReferences(copied.subList(0, original.size()), original),
                            "active list is a shallow snapshot, not a mutable input alias");
                }
                try (InternalMemoryResources otherLayer = new InternalMemoryResources(fixtures())) {
                    otherLayer.activateAndReload(() -> CompletableFuture.completedFuture(null)).join();
                    List<ResourcePack> otherAugmented = otherLayer.augmentReloadPacks(original);
                    try (ResourcePack otherView = otherAugmented.getLast()) {
                        List<ResourcePack> both = layer.augmentReloadPacks(otherAugmented);
                        try (ResourcePack anotherOwn = both.getLast()) {
                            check(both.size() == original.size() + 2 && both.get(original.size()) == otherView,
                                    "another layer's same diagnostic ID is not hidden or replaced");
                        }
                    }
                    otherLayer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
                }
                own.close();
                List<ResourcePack> renewed = layer.augmentReloadPacks(augmented);
                try (ResourcePack renewedView = renewed.getLast()) {
                    check(renewed.size() == augmented.size() + 1 && renewed.get(original.size()) == own
                                    && renewedView != own,
                            "closed own view does not suppress a fresh view or get removed from caller input");
                }
                layer.deactivateAndReload(() -> {
                    check(!layer.isActive() && layer.augmentReloadPacks(original) == original,
                            "deactivation excludes layer before host collects reload input");
                    return CompletableFuture.completedFuture(null);
                }).join();
                chooser.assertUntouched("after deactivation");
                check(layer.augmentReloadPacks(original) == original, "deactivation returns exact original pack list");
                layer.close();
                check(layer.augmentReloadPacks(original) == original, "closed layer returns exact original pack list");
                chooser.assertUntouched("after layer close");
            }
        }
    }

    @SuppressWarnings("try") // Explicit early closes are the behavior under test.
    private static void lookupAndLifetimes() throws Exception {
        Map<String, byte[]> input = fixtures();
        byte[] original = input.get("assets/celeste/fonts/sf_pro/sf_regular.json");
        try (InternalMemoryResources layer = new InternalMemoryResources(input)) {
            long retained = layer.retainedAssetBytes();
            original[0] = '!';
            input.clear();
            check(retained > 0, "layer retains its own immutable snapshot");
            layer.activateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            List<ResourcePack> noPacks = List.of();
            List<ResourcePack> firstList = layer.augmentReloadPacks(noPacks);
            ResourcePack first = firstList.getLast();
            ResourcePack second = layer.augmentReloadPacks(noPacks).getLast();
            try (first; second) {
                check(firstList.size() == 1 && noPacks.isEmpty(), "active empty input gains only internal view");
                check(first != second, "each original reload input gets an independent view");
                check(first.getNamespaces(CLIENT).equals(Set.of("celeste")), "only Celeste namespace");
                check(first.getNamespaces(ResourceType.SERVER_DATA).isEmpty(), "no server namespace");
                Identifier font = id("fonts/sf_pro/sf_regular.json");
                check(read(first.open(CLIENT, font)).equals("{\"fixture\":true}"), "defensive copy and font lookup");
                check(first.open(ResourceType.SERVER_DATA, font) == null, "client-only lookup");
                check(first.open(CLIENT, Identifier.of("minecraft", font.getPath())) == null, "namespace isolation");
                check(first.open(CLIENT, id("missing.png")) == null, "missing resource is null");
                check(first.open(CLIENT, id("textures/../fonts/sf_pro/sf_regular.json")) == null, "lookup cannot traverse");
                check(first.openRoot("assets", "celeste", "fonts", "sf_pro", "sf_regular.json") == null,
                        "root is not an assets lookup alias");
                check(first.openRoot("../pack.mcmeta") == null && first.openRoot() == null, "unsafe root names rejected");
                check(read(first.openRoot("pack.png")).equals("icon"), "optional root icon");
                String metadataText = read(first.openRoot("pack.mcmeta"));
                check(metadataText.contains("pack") && metadataText.contains("min_format"), "modern root metadata is available");
                PackResourceMetadata metadata = first.parseMetadata(PackResourceMetadata.CLIENT_RESOURCES_SERIALIZER);
                check(metadata.supportedFormats().contains(SharedConstants.getGameVersion().packVersion(CLIENT)),
                        "supported metadata parsed through Minecraft");
                check(metadata.supportedFormats().minInclusive().equals(metadata.supportedFormats().maxInclusive()),
                        "metadata advertises exact current format only");
                check(list(first, "textures").equals(List.of("celeste:textures/example.png", "celeste:textures/example.png.mcmeta",
                        "celeste:textures/nested/child.png")), "recursive sorted listing including sidecar metadata");
                check(list(first, "textures/nested").equals(List.of("celeste:textures/nested/child.png")), "nested prefix");
                check(list(first, "").size() == 6, "empty prefix finds every asset");
                check(list(first, "textures/").isEmpty() && list(first, "../textures").isEmpty(), "invalid prefixes rejected");
                check(list(first, "text").isEmpty(), "prefix is a directory, not a raw string prefix");
                InputSupplier<InputStream> pending = first.open(CLIENT, font);
                InputStream opened = pending.get();
                first.close();
                expect(FileNotFoundException.class, pending::get, "unopened supplier invalidated by view close");
                check(first.open(CLIENT, font) == null && first.getNamespaces(CLIENT).isEmpty(), "closed view cannot open or enumerate");
                check(read(second.open(CLIENT, font)).startsWith("{"), "independent view survives another view's close");
                check(opened.read() == '{', "already open stream may finish after view close");
                opened.close();
                check(opened.read() == -1, "stream close releases its buffer");
                InputSupplier<InputStream> layerPending = second.open(CLIENT, font);
                InputStream survivingStream = layerPending.get();
                layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
                layer.close();
                check(layer.retainedAssetBytes() == 0 && second.getNamespaces(CLIENT).isEmpty(), "layer close drops shared bytes");
                expect(FileNotFoundException.class, layerPending::get, "layer close invalidates outstanding suppliers");
                check(survivingStream.read() == '{', "already open stream may finish after layer close");
                survivingStream.close();
                check(survivingStream.read() == -1, "post-layer-close stream releases its own buffer");
                check(layer.augmentReloadPacks(noPacks) == noPacks, "closed layer cannot create a stale new view");
                layer.close();
                expect(IllegalStateException.class,
                        () -> layer.activateAndReload(() -> CompletableFuture.completedFuture(null)), "closed layer cannot reactivate");
                expect(IllegalStateException.class,
                        () -> layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)), "closed layer cannot request a reload");
            }
        }
    }

    private static void reloadTransitions() throws Exception {
        try (FixtureChooser chooser = new FixtureChooser();
             InternalMemoryResources layer = new InternalMemoryResources(fixtures())) {
            layer.activateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            // This list is the real chooser output plus the host's internal hook.
            List<ResourcePack> augmented = layer.augmentReloadPacks(chooser.packs);
            try (LifecycledResourceManagerImpl loaded = new LifecycledResourceManagerImpl(CLIENT, augmented)) {
                try (InputStream font = loaded.getResource(id("fonts/sf_pro/sf_regular.json")).orElseThrow().getInputStream()) {
                    check(new String(font.readAllBytes(), StandardCharsets.UTF_8).equals("{\"fixture\":true}"),
                            "vanilla manager resolves internal bytes above an existing Celeste resource");
                }
                try (InputStream unrelated = loaded.getResource(Identifier.of("fixture", "first.txt")).orElseThrow().getInputStream()) {
                    check(new String(unrelated.readAllBytes(), StandardCharsets.UTF_8).equals("fixture:a"),
                            "vanilla manager preserves unrelated pack resolution precedence");
                }
                check(loaded.findResources("textures", identifier -> identifier.getNamespace().equals("celeste")
                                && identifier.getPath().endsWith(".png")).size() == 2,
                        "vanilla manager recursively enumerates internal assets");
                chooser.assertUntouched("while vanilla manager consumes augmented list");
                CompletableFuture<Void> inFlight = new CompletableFuture<>();
                CompletableFuture<Void> removal = layer.deactivateAndReload(() -> inFlight);
                check(!removal.isDone() && !layer.isActive() && layer.augmentReloadPacks(chooser.packs) == chooser.packs,
                        "deactivation waits for reload while removing only future internal input");
                check(loaded.getResource(id("textures/example.png")).isPresent(), "old manager readable during removal reload");
                check(layer.retainedAssetBytes() > 0, "old resource bytes are retained mid-reload");
                expect(IllegalStateException.class, layer::close, "close prohibited mid-reload");
                expect(IllegalStateException.class,
                        () -> layer.activateAndReload(() -> CompletableFuture.completedFuture(null)), "overlapping activation rejected");
                expect(IllegalStateException.class,
                        () -> layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)), "overlapping deactivation rejected");
                chooser.assertUntouched("during removal reload");
                inFlight.complete(null);
                removal.join();
                check(read(chooser.packs.getFirst().open(CLIENT, Identifier.of("fixture", "first.txt"))).equals("fixture:b"),
                        "layer deactivation does not close an unrelated resource pack");
            }
            check(layer.retainedAssetBytes() > 0, "resource manager view closure does not release installed snapshot");
            layer.activateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            try (ResourcePack renewed = layer.augmentReloadPacks(List.of()).getLast()) {
                check(layer.isActive() && read(renewed.open(CLIENT, id("textures/example.png"))).equals("texture"),
                        "same immutable payload reactivates with a fresh readable view");
            }
            layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            check(layer.augmentReloadPacks(chooser.packs) == chooser.packs, "deactivation leaves selected list exact even after view closure");
            CompletableFuture<Void> uncancelledReload = new CompletableFuture<>();
            CompletableFuture<Void> callerResult = layer.activateAndReload(() -> uncancelledReload);
            callerResult.cancel(false);
            check(callerResult.isCancelled() && !uncancelledReload.isCancelled(), "caller cancellation cannot cancel host reload");
            expect(IllegalStateException.class, layer::close, "caller cancellation does not end real reload guard");
            expect(IllegalStateException.class,
                    () -> layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)),
                    "caller cancellation does not permit an overlapping transition");
            uncancelledReload.complete(null);
            layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            check(!layer.isActive(), "real completion releases guard after caller cancellation");
            chooser.assertChooserUntouched("after all transitions");
        }
    }

    private static void failureTransitions() {
        try (FixtureChooser chooser = new FixtureChooser();
             InternalMemoryResources layer = new InternalMemoryResources(fixtures())) {
            expect(NullPointerException.class, () -> layer.activateAndReload(null), "missing host observer rejected");
            check(!layer.isActive(), "missing observer leaves state unchanged");
            expect(IllegalArgumentException.class,
                    () -> layer.activateAndReload(() -> { throw new IllegalArgumentException("fixture"); }),
                    "synchronous reload submission error propagated");
            check(!layer.isActive() && layer.augmentReloadPacks(chooser.packs) == chooser.packs,
                    "synchronous activation failure restores inactive input state");
            expect(NullPointerException.class, () -> layer.activateAndReload(() -> null), "null host future rejected");
            check(!layer.isActive(), "null future restores inactive state and releases guard");
            CompletableFuture<Void> failure = new CompletableFuture<>();
            CompletableFuture<Void> activation = layer.activateAndReload(() -> failure);
            failure.completeExceptionally(new IllegalStateException("fixture reload failure"));
            expect(CompletionException.class, activation::join, "asynchronous failure is not success");
            check(layer.retainedAssetBytes() > 0 && layer.isActive(), "failed activation retains bytes and requested retry state");
            expect(IllegalArgumentException.class,
                    () -> layer.deactivateAndReload(() -> { throw new IllegalArgumentException("fixture"); }),
                    "synchronous deactivation error propagated");
            check(layer.isActive(), "synchronous deactivation failure restores active input state");
            CompletableFuture<Void> failedRemoval = new CompletableFuture<>();
            CompletableFuture<Void> removal = layer.deactivateAndReload(() -> failedRemoval);
            failedRemoval.completeExceptionally(new IllegalStateException("fixture unload failure"));
            expect(CompletionException.class, removal::join, "asynchronous deactivation failure is not success");
            check(!layer.isActive() && layer.retainedAssetBytes() > 0
                            && layer.augmentReloadPacks(chooser.packs) == chooser.packs,
                    "failed deactivation retains bytes and requested inactive state");
            layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            check(!layer.isActive(), "explicit recovery reload after asynchronous failure succeeds");
            CompletableFuture<Void> cancelledHost = new CompletableFuture<>();
            CompletableFuture<Void> cancelledActivation = layer.activateAndReload(() -> cancelledHost);
            cancelledHost.cancel(false);
            expect(CancellationException.class, cancelledActivation::join, "host cancellation is surfaced without false success");
            check(layer.isActive() && layer.retainedAssetBytes() > 0, "cancelled host retains requested state and bytes");
            layer.deactivateAndReload(() -> CompletableFuture.completedFuture(null)).join();
            chooser.assertUntouched("after sync, async, and cancellation failures");
        }
    }

    private static void validation() {
        for (String name : List.of("../assets/celeste/a", "assets/celeste/../a", "assets/celeste/./a", "assets/celeste//a",
                "assets/celeste/a/", "/assets/celeste/a", "assets\\celeste\\a", "assets/celeste/Upper.png",
                "assets/minecraft/a", "data/celeste/a", "pack.mcmeta", "fabric.mod.json", "assets/celeste/a:b",
                "assets/celeste/%2e%2e/a", "assets/celeste/space name", "assets/celeste/a\u0000b")) {
            expect(IllegalArgumentException.class, () -> new InternalMemoryResources(Map.of(name, new byte[0])),
                    "reject unsafe or unscoped resource name");
        }
        expect(IllegalArgumentException.class, () -> new InternalMemoryResources(Map.of()), "empty payload rejected");
        expect(IllegalArgumentException.class, () -> new InternalMemoryResources(Map.of("pack.png", new byte[0])),
                "root-only payload rejected");
        expect(IllegalArgumentException.class, () -> new InternalMemoryResources(Map.of("assets/celeste/large.png",
                new byte[32 * 1024 * 1024 + 1])), "per-entry memory limit");
        Map<String, byte[]> tooMany = new HashMap<>();
        for (int i = 0; i < 8193; i++) {
            tooMany.put("assets/celeste/" + i, new byte[0]);
        }
        expect(IllegalArgumentException.class, () -> new InternalMemoryResources(tooMany), "entry-count limit");
    }

    /** Independent ordinary chooser fixtures; production internal resources never register here. */
    private static final class FixtureChooser implements AutoCloseable {
        private final AtomicInteger scans = new AtomicInteger();
        private final ResourcePackManager manager;
        private final List<ResourcePackProfile> profiles;
        private final List<ResourcePackProfile> enabledProfiles;
        private final List<String> enabledIds;
        private final List<ResourcePack> packs;

        FixtureChooser() {
            List<ResourcePackProfile> fixtures = List.of(fixtureProfile("fixture:a"), fixtureProfile("fixture:b"),
                    fixtureProfile("fixture:disabled"));
            ResourcePackProvider existingProvider = consumer -> {
                scans.incrementAndGet();
                fixtures.forEach(consumer);
            };
            manager = new ResourcePackManager(existingProvider);
            manager.scanPacks();
            manager.setEnabledProfiles(List.of("fixture:b", "fixture:a"));
            profiles = List.copyOf(manager.getProfiles());
            enabledProfiles = List.copyOf(manager.getEnabledProfiles());
            enabledIds = List.copyOf(manager.getEnabledIds());
            packs = manager.createResourcePacks();
            check(profiles.size() == 3 && enabledIds.equals(List.of("fixture:b", "fixture:a")),
                    "ordinary fixture includes disabled profile and intentional enabled order");
            check(fixtures.stream().allMatch(profile -> profile.getCompatibility() == ResourcePackCompatibility.COMPATIBLE),
                    "independent fixture profiles use actual Minecraft metadata loading");
        }

        void assertChooserUntouched(String phase) {
            check(sameReferences(new ArrayList<>(manager.getProfiles()), profiles), phase + ": chooser profile identity/order unchanged");
            check(List.copyOf(manager.getEnabledIds()).equals(enabledIds), phase + ": enabled IDs/order unchanged");
            check(sameReferences(new ArrayList<>(manager.getEnabledProfiles()), enabledProfiles),
                    phase + ": enabled profile identity/order unchanged");
            check(manager.getProfiles().size() == 3 && !manager.hasProfile(InternalMemoryResources.RESOURCE_ID),
                    phase + ": no internal profile enters chooser");
            check(scans.get() == 1, phase + ": internal layer never scans or invokes unrelated provider");
        }

        void assertUntouched(String phase) {
            assertChooserUntouched(phase);
            check(packs.stream().allMatch(pack -> !((FixturePack) pack).closed), phase + ": unrelated selected packs remain open");
        }

        @Override
        public void close() {
            packs.forEach(ResourcePack::close);
        }
    }

    private static ResourcePackProfile fixtureProfile(String name) {
        ResourcePackInfo info = new ResourcePackInfo(name, Text.literal(name), ResourcePackSource.BUILTIN, Optional.empty());
        ResourcePackProfile.PackFactory factory = new ResourcePackProfile.PackFactory() {
            @Override
            public ResourcePack open(ResourcePackInfo packInfo) {
                return new FixturePack(packInfo);
            }

            @Override
            public ResourcePack openWithOverlays(ResourcePackInfo packInfo, ResourcePackProfile.Metadata metadata) {
                return open(packInfo);
            }
        };
        return Objects.requireNonNull(ResourcePackProfile.create(info, factory, CLIENT,
                new ResourcePackPosition(false, ResourcePackProfile.InsertionPosition.TOP, false)), "fixture metadata");
    }

    /** Minimal independent pack, with colliding Celeste and unrelated fixture assets. */
    private static final class FixturePack extends AbstractFileResourcePack {
        private final Map<Identifier, byte[]> resources;
        private final byte[] metadata;
        private boolean closed;

        FixturePack(ResourcePackInfo info) {
            super(info);
            resources = Map.of(Identifier.of("fixture", "first.txt"), bytes(info.id()),
                    id("fonts/sf_pro/sf_regular.json"), bytes("ordinary-existing-celeste-font"));
            PackResourceMetadata packMetadata = new PackResourceMetadata(Text.literal("ordinary chooser fixture"),
                    new Range<>(SharedConstants.getGameVersion().packVersion(CLIENT)));
            JsonObject root = new JsonObject();
            root.add("pack", PackResourceMetadata.CLIENT_RESOURCES_SERIALIZER.codec()
                    .encodeStart(JsonOps.INSTANCE, packMetadata).getOrThrow());
            metadata = bytes(root.toString());
        }

        @Override
        public InputSupplier<InputStream> openRoot(String... segments) {
            return !closed && segments.length == 1 && "pack.mcmeta".equals(segments[0])
                    ? () -> new ByteArrayInputStream(metadata) : null;
        }

        @Override
        public InputSupplier<InputStream> open(ResourceType type, Identifier identifier) {
            byte[] value = resources.get(identifier);
            return !closed && type == CLIENT && value != null ? () -> new ByteArrayInputStream(value) : null;
        }

        @Override
        public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
            if (!closed && type == CLIENT) {
                resources.forEach((identifier, value) -> {
                    if (identifier.getNamespace().equals(namespace)
                            && (prefix.isEmpty() || identifier.getPath().startsWith(prefix + "/"))) {
                        consumer.accept(identifier, () -> new ByteArrayInputStream(value));
                    }
                });
            }
        }

        @Override
        public Set<String> getNamespaces(ResourceType type) {
            return !closed && type == CLIENT ? Set.of("fixture", "celeste") : Set.of();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Map<String, byte[]> fixtures() {
        Map<String, byte[]> fixtures = new HashMap<>();
        fixtures.put("assets/celeste/fonts/sf_pro/sf_regular.json", bytes("{\"fixture\":true}"));
        fixtures.put("assets/celeste/shaders/core/example.vsh", bytes("void main() {}"));
        fixtures.put("assets/celeste/textures/example.png", bytes("texture"));
        fixtures.put("assets/celeste/textures/example.png.mcmeta", bytes("{}"));
        fixtures.put("assets/celeste/textures/nested/child.png", bytes("child"));
        fixtures.put("assets/celeste/textures2/not_a_child.png", bytes("other"));
        fixtures.put("pack.png", bytes("icon"));
        return fixtures;
    }

    private static boolean sameReferences(List<?> actual, List<?> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            if (actual.get(i) != expected.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Identifier id(String path) {
        return Identifier.of("celeste", path);
    }

    private static String read(InputSupplier<InputStream> supplier) throws Exception {
        if (supplier == null) {
            throw new AssertionError("missing resource supplier");
        }
        try (InputStream stream = supplier.get()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> list(ResourcePack pack, String prefix) {
        List<String> names = new ArrayList<>();
        pack.findResources(CLIENT, "celeste", prefix, (identifier, supplier) -> names.add(identifier.toString()));
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
