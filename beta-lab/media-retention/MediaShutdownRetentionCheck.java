import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated JVM only: observes the real hook registry; never initializes JNI or Minecraft. */
public final class MediaShutdownRetentionCheck {
    private static int checks;
    private static Field hooksField;
    public static void main(String[] args) throws Exception {
        hooksField = Class.forName("java.lang.ApplicationShutdownHooks").getDeclaredField("hooks");
        hooksField.setAccessible(true);
        List<URL> urls = new ArrayList<>();
        for (String path : args[1].split(java.io.File.pathSeparator)) {
            if (!path.isBlank()) urls.add(Path.of(path).toUri().toURL());
        }
        if (args[0].equals("vm-shutdown")) {
            URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            Class.forName("dev.c4lam.celeste.media.SystemMediaService", true, loader);
            System.out.println("VM_SHUTDOWN_ARMED");
            return;
        }
        if (args[0].equals("faults")) {
            terminationFailure(urls, "executor"); terminationFailure(urls, "decoder");
            System.out.println("MEDIA_FAILURE_PASS checks=" + checks); return;
        }
        int baseline = hooks().size();
        List<WeakReference<ClassLoader>> retired = new ArrayList<>();
        for (int lifetime = 0; lifetime < 3; lifetime++) retired.add(lifetime(urls, baseline));
        for (int attempt = 0; attempt < 100 && retired.stream().anyMatch(r -> r.get() != null); attempt++) {
            System.gc(); System.runFinalization(); Thread.sleep(25);
            byte[] pressure = new byte[128 * 1024]; pressure[0] = 1;
        }
        for (WeakReference<ClassLoader> ref : retired) {
            if (ref.get() != null) for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getContextClassLoader() == ref.get()) System.out.println("RETAINED_TCCL " + thread.getName());
                Field accField = Thread.class.getDeclaredField("inheritedAccessControlContext"); accField.setAccessible(true);
                Object acc = accField.get(thread);
                if (acc != null) {
                    Field domainsField = acc.getClass().getDeclaredField("context"); domainsField.setAccessible(true);
                    java.security.ProtectionDomain[] domains = (java.security.ProtectionDomain[]) domainsField.get(acc);
                    if (domains != null) for (java.security.ProtectionDomain domain : domains) if (domain.getClassLoader() == ref.get()) System.out.println("RETAINED_ACC " + thread.getName());
                }
            }
            check(ref.get() == null, "closed lifetime loader is collectible");
        }
        check(hooks().size() == baseline, "JVM hook registry returns to baseline");
        System.out.println("MEDIA_RETENTION_PASS checks=" + checks + " collected_lifetimes=" + retired.size());
    }
    private static WeakReference<ClassLoader> lifetime(List<URL> urls, int baseline) throws Exception {
        Set<Thread> original = hooks();
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Set<Thread> created = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Thread caller = Thread.currentThread();
            ClassLoader prior = caller.getContextClassLoader();
            Object service;
            Class<?> type;
            try {
                caller.setContextClassLoader(loader);
                type = Class.forName("dev.c4lam.celeste.media.SystemMediaService", true, loader);
                service = type.getMethod("getInstance").invoke(null);
                created.addAll(hooks()); created.removeAll(original);
                check(created.size() == 1, "one lifecycle-owned shutdown hook");
                check(created.iterator().next().getContextClassLoader() == ClassLoader.getPlatformClassLoader(), "hook TCCL is platform-owned");
                Field executorField = type.getDeclaredField("executor"); executorField.setAccessible(true);
                ExecutorService executor = (ExecutorService) executorField.get(service);
                check(executor.submit(() -> Thread.currentThread().getContextClassLoader()).get(3, TimeUnit.SECONDS)
                    == ClassLoader.getPlatformClassLoader(), "media worker TCCL is platform-owned");
                CountDownLatch pollStarted = new CountDownLatch(1), pollRelease = new CountDownLatch(1);
                executor.execute(() -> { pollStarted.countDown(); awaitUninterruptibly(pollRelease); });
                check(pollStarted.await(3, TimeUnit.SECONDS), "native-poll boundary is running");
                Class<?> render = Class.forName("com.mojang.blaze3d.systems.RenderSystem", true, loader);
                Class<?> clientType = Class.forName("net.minecraft.client.MinecraftClient", true, loader);
                Object client = clientType.getMethod("getInstance").invoke(null);
                Class<?> artworkType = Class.forName("dev.c4lam.celeste.media.MediaArtwork", true, loader);
                Object artwork = artworkType.getMethod("getInstance").invoke(null);
                Class<?> nativeImage = Class.forName("net.minecraft.client.texture.NativeImage", true, loader);
                CountDownLatch readStarted = (CountDownLatch) nativeImage.getField("entered").get(null);
                CountDownLatch readRelease = (CountDownLatch) nativeImage.getField("release").get(null);
                Field decoderField = artworkType.getDeclaredField("decoder"); decoderField.setAccessible(true);
                ExecutorService decoder = (ExecutorService) decoderField.get(artwork);
                check(decoder.submit(() -> Thread.currentThread().getContextClassLoader()).get(3, TimeUnit.SECONDS)
                    == ClassLoader.getPlatformClassLoader(), "artwork worker TCCL is platform-owned");
                var decode = artworkType.getDeclaredMethod("decode", long.class, String.class, String.class);
                decode.setAccessible(true);
                byte[] png = { (byte)137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1 };
                String payload = java.util.Base64.getEncoder().encodeToString(png);
                decoder.execute(() -> { try { decode.invoke(artwork, 0L, "old-artwork", payload); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); } });
                check(readStarted.await(3, TimeUnit.SECONDS), "actual decode reaches controlled image boundary");
                type.getMethod("close").invoke(service);
                check(hooks().size() == baseline, "explicit close detaches owned JVM hook");
                CompletableFuture<?> stopped = ((CompletionStage<?>)type.getMethod("stopped").invoke(service)).toCompletableFuture();
                check(!stopped.isDone(), "interrupted native/decode work cannot report successful Stop");
                pollRelease.countDown();
                check(executor.awaitTermination(3, TimeUnit.SECONDS), "native worker truly terminated");
                check(!stopped.isDone(), "decoder must also terminate");
                readRelease.countDown();
                check(decoder.awaitTermination(3, TimeUnit.SECONDS), "decoder truly terminated");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((int)clientType.getMethod("queued").invoke(client) < 2 && System.nanoTime() < deadline) Thread.sleep(2);
                check((int)clientType.getMethod("queued").invoke(client) >= 2, "late install and final render barrier queued");
                check(!stopped.isDone(), "Stop awaits queued install disposal and render barrier");
                clientType.getMethod("pump").invoke(client);
                stopped.get(3, TimeUnit.SECONDS);
                check(nativeImage.getField("closed").getInt(null) == 1, "late decoded image disposed exactly once");
                Class<?> manager = Class.forName("net.minecraft.client.texture.TextureManager", true, loader);
                check(manager.getField("registered").getInt(null) == 0, "late install cannot register after close");
                check(manager.getField("destroyed").getInt(null) == 1, "owned artwork texture retired exactly once");
                type.getMethod("close").invoke(service);
                check(hooks().size() == baseline, "repeated close is inert");
                check(manager.getField("destroyed").getInt(null) == 1, "repeated close keeps texture cleanup inert");
            } finally { caller.setContextClassLoader(prior); }
            return new WeakReference<>(loader);
        } finally {
            // Test failure must not strand its own fixture hooks in this synthetic JVM.
            for (Thread hook : created) try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
            loader.close();
        }
    }
    private static void terminationFailure(List<URL> urls, String fieldName) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", true, loader);
            Class<?> clientType = Class.forName("net.minecraft.client.MinecraftClient", true, loader);
            Object client = clientType.getMethod("getInstance").invoke(null);
            Class<?> serviceType = Class.forName("dev.c4lam.celeste.media.SystemMediaService", true, loader);
            Object service = serviceType.getMethod("getInstance").invoke(null);
            Class<?> artworkType = Class.forName("dev.c4lam.celeste.media.MediaArtwork", true, loader);
            Object artwork = artworkType.getMethod("getInstance").invoke(null);
            Object owner = fieldName.equals("executor") ? service : artwork;
            Field field = owner.getClass().getDeclaredField(fieldName); field.setAccessible(true);
            ((ExecutorService)field.get(owner)).shutdownNow();
            field.set(owner, new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
                @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
            });
            serviceType.getMethod("close").invoke(service);
            CompletableFuture<?> completion = ((CompletionStage<?>)serviceType.getMethod("stopped").invoke(service)).toCompletableFuture();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!completion.isDone() && System.nanoTime() < until) { clientType.getMethod("pump").invoke(client); Thread.sleep(2); }
            check(completion.isCompletedExceptionally(), fieldName + " failed termination is an exceptional Stop");
            try { completion.join(); throw new AssertionError("failure hidden"); }
            catch (java.util.concurrent.CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException, "failed termination retains explicit cause");
            }
        }
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @SuppressWarnings("unchecked") private static Set<Thread> hooks() throws IllegalAccessException {
        Class<?> owner = hooksField.getDeclaringClass();
        synchronized (owner) {
            Map<Thread, Thread> registered = (Map<Thread, Thread>) hooksField.get(null);
            Set<Thread> copy = Collections.newSetFromMap(new IdentityHashMap<>());
            copy.addAll(registered.keySet()); return copy;
        }
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
}
