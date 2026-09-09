package dev.c4lam.celeste.beta.resources;

import dev.celestebeta.host.ResourceReloadBridge;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Real resource managers with a deterministic Minecraft overlay/client-thread boundary. */
public final class InternalResourcesHostCheck {
    private static final Identifier ASSET = Identifier.of("celeste", "host-test.txt");
    private static int checks;
    private InternalResourcesHostCheck() { }

    public static void run() throws Exception {
        successfulActivationAndReload();
        retirementWaitsForReloadAndOverlay();
        retirementSchedulingFailureRetainsAssets();
        activationFailureRestoresSelection();
        successCallbackFailureReplays();
        cleanupFailureUsesVanillaFallback(false);
        cleanupFailureUsesVanillaFallback(true);
        missingHookCannotReportSuccess();
        synchronousSubmissionFailure(false);
        synchronousSubmissionFailure(true);
        observerCancellationDoesNotCloseStorage();
        System.out.println("PASS internal asset host: " + checks + " checks; real resource managers, fixture scheduling");
    }

    private static InternalMemoryResources assets() {
        return new InternalMemoryResources(Map.of("assets/celeste/host-test.txt", "verified".getBytes(StandardCharsets.UTF_8)));
    }

    private static void successfulActivationAndReload() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            CompletableFuture<Void> activation = host.activateAndReload();
            check(client.manager.getResource(ASSET).isPresent(), "actual manager receives verified Celeste asset");
            check(!activation.isDone(), "input collection is not activation success");
            check(client.selectedIds.equals(List.of("vanilla", "user-pack")), "activation does not add chooser profile");
            client.finish(null, null);
            check(!activation.isDone(), "host settlement waits until bridge callback dispatch has ended");
            client.drain();
            activation.join();
            check(client.successCallbacks == 1, "vanilla success callback still executes");
            expect(IllegalStateException.class, host::close, "active renderer assets cannot be closed");

            client.requestReload(); // Later ordinary F3+T reload must retain internal assets.
            check(client.manager.getResource(ASSET).isPresent(), "subsequent ordinary reload also includes internal resources");
            client.finish(null, null);
            client.drain();
            CompletableFuture<Void> deactivation = host.deactivateAndReload();
            deactivation.thenRun(host::close);
            check(client.manager.getResource(ASSET).isEmpty(), "deactivation excludes internal resources");
            client.finish(null, null);
            client.drain();
            deactivation.join();
            check(assets.retainedAssetBytes() == 0, "completion callback can close storage and bridge registration");
            check(client.selectedIds.equals(List.of("vanilla", "user-pack")), "full lifecycle preserves selected IDs/order");
            host.close();
        }
    }

    private static void retirementWaitsForReloadAndOverlay() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            host.activateAndReload(); client.finish(null, null); client.drain();
            client.requestReload();
            int[] removed = {0};
            CompletableFuture<Void> cancelledObserver = host.retireWhenIdle(() -> removed[0]++);
            CompletableFuture<Void> stopped = host.retireWhenIdle(() -> { throw new AssertionError("duplicate cleanup"); });
            cancelledObserver.cancel(false);
            check(removed[0] == 0 && !stopped.isDone(), "Stop waits for existing resource reload without removing its assets");
            check(client.delayed.size() == 1, "repeated Stop creates one delayed wait, not a render-thread spin");
            client.advanceDelay();
            check(client.delayed.size() == 1 && removed[0] == 0, "busy reload reschedules only one bounded check");
            client.callback(null, null); client.drain();
            client.advanceDelay();
            check(removed[0] == 0 && !stopped.isDone(), "finished resource work still waits for the old overlay fade");
            client.overlay = false; client.advanceDelay();
            check(removed[0] == 1 && client.manager.getResource(ASSET).isEmpty(), "render state retires exactly once before the assetless replacement");
            client.callback(null, null); client.drain();
            check(!stopped.isDone() && assets.retainedAssetBytes() > 0, "successful replacement waits for its own overlay before confirming close");
            client.advanceDelay();
            check(!stopped.isDone() && client.delayed.size() == 1, "final overlay wait does not produce restart-required failure");
            client.overlay = false; client.advanceDelay();
            stopped.join();
            check(assets.retainedAssetBytes() == 0 && client.delayed.isEmpty(), "confirmed Stop clears resource bytes and every delayed task");
            host.retireWhenIdle(() -> { throw new AssertionError("closed cleanup repeated"); }).join();
            check(removed[0] == 1 && client.selectedIds.equals(List.of("vanilla", "user-pack")), "repeat Stop preserves resource choices and stays idempotent");
            // Same client accepts a fresh layer after all old ownership has ended.
            MinecraftInternalResourcesHost next = new MinecraftInternalResourcesHost(client, assets());
            next.activateAndReload(); client.finish(null, null); client.drain();
            CompletableFuture<Void> nextStop = next.retireWhenIdle(() -> { });
            client.finish(null, null); client.drain(); nextStop.join();
            check(client.manager.getResource(ASSET).isEmpty(), "second lifetime on the same client also retires cleanly");
        }
    }

    private static void retirementSchedulingFailureRetainsAssets() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            host.activateAndReload(); client.finish(null, null); client.drain();
            client.overlay = true;
            RuntimeException rejected = new IllegalStateException("client queue rejected delayed cleanup");
            client.delayFailure = rejected;
            CompletableFuture<Void> stopped = host.retireWhenIdle(() -> { throw new AssertionError("cleanup ran while busy"); });
            failedWith(stopped, rejected);
            check(assets.retainedAssetBytes() > 0 && client.manager.getResource(ASSET).isPresent(), "scheduling failure retains resources owned by Minecraft");
            client.overlay = false;
            // Explicit isolated-fixture recovery, not an automatic success claim.
            host.deactivateAndReload(); client.finish(null, null); client.drain(); host.close();
        }
    }

    private static void activationFailureRestoresSelection() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            CompletableFuture<Void> activation = host.activateAndReload();
            CompletableFuture<Void> originalAttempt = client.current;
            ResourcePack priorView = client.manager.streamResourcePacks().filter(pack -> pack.getId().contains("celeste")).findFirst().orElseThrow();
            RuntimeException failure = new RuntimeException("bad internal shader fixture");
            client.finish(failure, null);
            client.drain();
            check(client.vanillaRecoveries == 0, "owned failure skips vanilla's pack-selection clearing");
            check(client.queued != null && !activation.isDone(), "activation waits for queued cleanup attempt");
            check(assets.retainedAssetBytes() > 0, "failed activation retains bytes until cleanup");
            client.startQueued();
            check(client.manager.getResource(ASSET).isEmpty(), "cleanup rebuilds resources without internal assets");
            check(priorView.getNamespaces(ResourceType.CLIENT_RESOURCES).isEmpty(), "replacement manager retires the old internal view");
            client.finish(null, null);
            client.drain();
            failedWith(activation, failure);
            check(originalAttempt.isCompletedExceptionally(), "failed vanilla attempt no longer remains pending forever");
            check(client.selectedIds.equals(List.of("vanilla", "user-pack")), "cleanup keeps original selected packs");
            check(client.failures.size() == 1 && client.failures.getFirst() == failure, "original asset failure remains visible");
            client.requestReload();
            check(client.manager.getResource(ASSET).isEmpty(), "ordinary reload does not silently retry failed internal assets");
            client.finish(null, null);
            client.drain();
            host.close();
            check(assets.retainedAssetBytes() == 0, "successful failed-activation cleanup permits close");
        }
    }

    private static void successCallbackFailureReplays() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets());
            CompletableFuture<Void> activation = host.activateAndReload();
            RuntimeException failure = new RuntimeException("renderer callback fixture");
            client.finish(null, failure);
            client.drain();
            check(client.queued != null && client.vanillaRecoveries == 0,
                "same-token failure replay retains ownership after success callback throws");
            check(!activation.isDone(), "throwing success callback must wait for cleanup");
            client.startQueued();
            client.finish(null, null);
            client.drain();
            failedWith(activation, failure);
            host.close();
        }
    }

    private static void cleanupFailureUsesVanillaFallback(boolean forcedFails) throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets());
            CompletableFuture<Void> activation = host.activateAndReload();
            RuntimeException original = new RuntimeException("internal failure fixture");
            client.finish(original, null);
            client.startQueued();
            client.finish(new RuntimeException("independent user-pack fixture"), null);
            client.drain();
            check(client.vanillaRecoveries == 1, "failure without internal assets keeps ordinary vanilla recovery");
            check(!activation.isDone(), "host observes subsequent forced recovery before settling");
            client.startQueued();
            client.finish(forcedFails ? new RuntimeException("forced recovery fixture") : null, null);
            client.drain();
            failedWith(activation, original);
            check(original.getSuppressed().length >= 1, "cleanup failures remain attached to the original cause");
            if (forcedFails) {
                expect(IllegalStateException.class, host::close, "failed forced reload cannot prove safe resource retirement");
                CompletableFuture<Void> deactivation = host.deactivateAndReload();
                client.finish(null, null);
                client.drain();
                deactivation.join();
            }
            host.close();
        }
    }

    private static void missingHookCannotReportSuccess() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            client.hooked = false;
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            expect(IllegalStateException.class, host::activateAndReload, "absent real input hook rejects activation");
            check(!assets.isActive() && client.manager.getResource(ASSET).isEmpty(), "missing hook restores dormant asset state");
            client.finish(null, null);
            client.drain();
            host.close();
        }
    }

    private static void observerCancellationDoesNotCloseStorage() throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            CompletableFuture<Void> observer = host.activateAndReload();
            observer.cancel(false);
            check(!client.current.isCancelled(), "observer cancellation cannot cancel actual Minecraft reload");
            expect(IllegalStateException.class, host::deactivateAndReload, "cancelled observer does not clear in-flight guard");
            client.finish(null, null);
            client.drain();
            CompletableFuture<Void> deactivation = host.deactivateAndReload();
            client.finish(null, null);
            client.drain();
            deactivation.join();
            host.close();
            check(assets.retainedAssetBytes() == 0, "cancelled observer still permits later orderly cleanup");
        }
    }

    private static void synchronousSubmissionFailure(boolean duringDeactivation) throws Exception {
        try (FixtureClient client = new FixtureClient()) {
            InternalMemoryResources assets = assets();
            MinecraftInternalResourcesHost host = new MinecraftInternalResourcesHost(client, assets);
            if (duringDeactivation) {
                CompletableFuture<Void> activation = host.activateAndReload();
                client.finish(null, null);
                client.drain();
                activation.join();
            }
            RuntimeException exactFailure = new RuntimeException("synchronous reload construction fixture");
            client.submissionFailure = exactFailure;
            try {
                if (duringDeactivation) host.deactivateAndReload();
                else host.activateAndReload();
                throw new AssertionError("Expected synchronous submission failure");
            } catch (RuntimeException failure) {
                check(failure == exactFailure, "synchronous submission preserves original failure identity");
            }
            failedWith(client.current, exactFailure);
            check(!client.overlay, "submission failed before installation of an overlay/result callback");
            check(assets.isActive() == duringDeactivation, "submission failure restores prior requested asset state");
            check(client.manager.getResource(ASSET).isPresent() != duringDeactivation,
                "real manager was already replaced before submission failure");
            check(assets.retainedAssetBytes() > 0, "synchronous failure retains assets until verified retirement");
            expect(IllegalStateException.class, host::close, "submission failure cannot prove asset retirement");
            CompletableFuture<Void> deactivation = host.deactivateAndReload();
            client.finish(null, null);
            client.drain();
            deactivation.join();
            host.close();
            check(assets.retainedAssetBytes() == 0, "unstarted token does not prevent successful retry and close");
            check(client.selectedIds.equals(List.of("vanilla", "user-pack")),
                "synchronous submission recovery preserves user pack selection");
        }
    }

    private static final class FixtureClient implements MinecraftInternalResourcesHost.Control, AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private final ArrayDeque<Runnable> delayed = new ArrayDeque<>();
        private RuntimeException delayFailure;
        private final List<String> selectedIds = new ArrayList<>(List.of("vanilla", "user-pack"));
        private final List<Throwable> failures = new ArrayList<>();
        private LifecycledResourceManagerImpl manager;
        private CompletableFuture<Void> current;
        private CompletableFuture<Void> queued;
        private boolean overlay;
        private boolean forced;
        private boolean queuedForced;
        private boolean hooked = true;
        private RuntimeException submissionFailure;
        private int vanillaRecoveries;
        private int successCallbacks;

        public Object clientIdentity() { return this; }
        public boolean isOnThread() { return Thread.currentThread() == owner; }
        public boolean isReloading() { return overlay; }
        public void executeLater(Runnable task) { tasks.add(task); }
        public void executeAfterDelay(Runnable task, java.util.function.Consumer<Throwable> rejected) {
            if (delayFailure != null) rejected.accept(delayFailure);
            else delayed.add(task);
        }
        private void advanceDelay() {
            List<Runnable> ready = List.copyOf(delayed); delayed.clear();
            tasks.addAll(ready); drain();
        }
        public void reportFailure(Throwable failure) { failures.add(failure); }

        public CompletableFuture<Void> requestReload() {
            if (overlay) {
                if (queued == null) queued = new CompletableFuture<>();
                return queued;
            }
            return start(false);
        }

        private CompletableFuture<Void> start(boolean force) {
            forced = force;
            current = new CompletableFuture<>();
            List<ResourcePack> selected = selectedIds.stream().map(FixturePack::new).map(ResourcePack.class::cast).toList();
            List<?> inputs = hooked ? ResourceReloadBridge.inputs(this, forced, current, selected) : selected;
            if (manager != null) manager.close();
            manager = new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES,
                inputs.stream().map(ResourcePack.class::cast).toList());
            if (submissionFailure != null) {
                RuntimeException failure = submissionFailure;
                submissionFailure = null;
                throw failure;
            }
            overlay = true;
            return current;
        }

        private void startQueued() {
            check(!overlay && queued != null, "queued reload starts after prior overlay has finished");
            CompletableFuture<Void> waiting = queued;
            queued = null;
            boolean force = queuedForced;
            queuedForced = false;
            start(force).whenComplete((unused, failure) -> {
                if (failure == null) waiting.complete(null);
                else waiting.completeExceptionally(failure);
            });
        }

        private void finish(Throwable failure, Throwable successCallbackFailure) {
            callback(failure, successCallbackFailure);
            if (failure == null && successCallbackFailure != null) {
                callback(successCallbackFailure, null); // Actual SplashOverlay same-token error replay.
            }
            overlay = false;
        }

        private void callback(Throwable failure, Throwable callbackFailure) {
            Optional<?> result = Optional.ofNullable(failure);
            boolean handled = hooked && ResourceReloadBridge.beforeResult(this, forced, current, result);
            if (!handled) {
                if (failure == null && callbackFailure == null) {
                    successCallbacks++;
                    current.complete(null);
                } else if (failure != null && !forced) {
                    vanillaRecoveries++;
                    selectedIds.clear(); // Model vanilla's independently verified failure branch.
                    selectedIds.add("vanilla");
                    queuedForced = true;
                    requestReload();
                }
            }
            if (hooked) ResourceReloadBridge.afterResult(this, forced, current, result, callbackFailure);
        }

        private void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
        public void close() { if (manager != null) manager.close(); }
    }

    private static final class FixturePack extends AbstractFileResourcePack {
        private FixturePack(String id) {
            super(new ResourcePackInfo(id, Text.literal(id), ResourcePackSource.BUILTIN, Optional.empty()));
        }
        public InputSupplier<InputStream> openRoot(String... segments) { return null; }
        public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
            return id.getNamespace().equals("fixture") ? () -> new ByteArrayInputStream(new byte[] {1}) : null;
        }
        public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) { }
        public Set<String> getNamespaces(ResourceType type) { return Set.of("fixture"); }
        public void close() { }
    }

    private static void failedWith(CompletableFuture<Void> future, Throwable expected) {
        check(future.isCompletedExceptionally(), "failed activation result is exceptional");
        try { future.join(); throw new AssertionError("Expected failed activation"); }
        catch (CompletionException failure) { check(failure.getCause() == expected, "original failure identity preserved"); }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message, failure);
        }
        throw new AssertionError(message);
    }
}
