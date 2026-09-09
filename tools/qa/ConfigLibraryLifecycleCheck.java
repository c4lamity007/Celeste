import java.lang.ref.WeakReference;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated real ConfigLibrary lifecycle checks; cloud/files/game are inert fixture boundaries. */
@SuppressWarnings("try")
public final class ConfigLibraryLifecycleCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        closeBeforeInitialize(fixtures);
        blockedRequest(fixtures);
        WeakReference<ClassLoader> idle = idleThenClose(fixtures);
        for (int i = 0; i < 80 && idle.get() != null; i++) {
            System.gc(); Thread.sleep(25);
        }
        check(idle.get() == null, "closed idle worker retains its payload loader");
        System.out.println("CONFIG_WORKER_PASS " + checks);
    }
    private static void closeBeforeInitialize(Path fixtures) throws Exception {
        try (Fixture fixture = new Fixture(fixtures)) {
            fixture.closeLibrary();
            fixture.stopped().get(2, TimeUnit.SECONDS);
            fixture.type.getMethod("initialize").invoke(fixture.library);
            check(fixture.request().isCompletedExceptionally(), "closed library admitted a request");
            check(fixture.calls().get() == 0, "close-before-init started cloud work");
            fixture.closeLibrary();
            check(fixture.stopped().isDone(), "repeat close lost completion");
        }
    }
    private static void blockedRequest(Path fixtures) throws Exception {
        try (Fixture fixture = new Fixture(fixtures)) {
            fixture.cloud.getField("block").setBoolean(null, true);
            CompletableFuture<?> request = fixture.request();
            CountDownLatch entered = (CountDownLatch) fixture.cloud.getField("entered").get(null);
            CountDownLatch release = (CountDownLatch) fixture.cloud.getField("release").get(null);
            CountDownLatch interrupted = (CountDownLatch) fixture.cloud.getField("interrupted").get(null);
            check(entered.await(2, TimeUnit.SECONDS), "cloud fixture never entered");
            AtomicInteger completions = new AtomicInteger();
            request.whenComplete((value, failure) -> completions.incrementAndGet());
            long begin = System.nanoTime();
            fixture.closeLibrary();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1000, "close blocked the client thread");
            check(request.isCancelled(), "close did not cancel the published UI future");
            check(completions.get() == 1, "pending callback did not settle before close returned");
            check(interrupted.await(2, TimeUnit.SECONDS), "running worker was not interrupted");
            check(!fixture.stopped().isDone(), "stop claimed clean before blocked work exited");
            check(fixture.request().isCompletedExceptionally(), "closed library submitted more cloud work");
            release.countDown();
            fixture.stopped().get(3, TimeUnit.SECONDS);
            check(completions.get() == 1, "worker published another callback after close");
            var cards = fixture.type.getDeclaredField("publicCards"); cards.setAccessible(true);
            check(((List<?>) cards.get(fixture.library)).isEmpty(), "late response republished public cards");
            check(!(Boolean) fixture.type.getMethod("cloudBusy").invoke(fixture.library), "closed library stayed busy");
            check(fixture.calls().get() == 1, "closed library executed another cloud request");
            Thread worker = (Thread) fixture.cloud.getField("worker").get(null);
            worker.join(1000);
            check(!worker.isAlive(), "worker did not exit after termination");
        }
    }
    private static WeakReference<ClassLoader> idleThenClose(Path fixtures) throws Exception {
        try (Fixture fixture = new Fixture(fixtures)) {
            fixture.request().get(2, TimeUnit.SECONDS);
            Thread worker = (Thread) fixture.cloud.getField("worker").get(null);
            check(worker.isAlive(), "fixture did not exercise an idle executor thread");
            check(worker.getContextClassLoader() == ClassLoader.getPlatformClassLoader(), "worker inherited payload TCCL");
            fixture.closeLibrary();
            fixture.stopped().get(3, TimeUnit.SECONDS);
            worker.join(1000);
            check(!worker.isAlive(), "idle worker survived close");
            return new WeakReference<>(fixture.loader);
        }
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> type, cloud;
        final Object library;
        Fixture(Path path) throws Exception {
            loader = new URLClassLoader(new java.net.URL[]{path.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                type = Class.forName("dev.c4lam.celeste.config.ConfigLibrary", true, loader);
                cloud = Class.forName("dev.c4lam.celeste.config.CloudConfigClient", true, loader);
                library = type.getMethod("getInstance").invoke(null);
            } finally { Thread.currentThread().setContextClassLoader(before); }
        }
        CompletableFuture<?> request() throws Exception { return (CompletableFuture<?>) type.getMethod("refreshPublicAsync").invoke(library); }
        CompletableFuture<?> stopped() throws Exception { return ((CompletionStage<?>) type.getMethod("stopped").invoke(library)).toCompletableFuture(); }
        AtomicInteger calls() throws Exception { return (AtomicInteger) cloud.getField("calls").get(null); }
        void closeLibrary() throws Exception { type.getMethod("close").invoke(library); }
        @Override public void close() throws Exception { closeLibrary(); loader.close(); }
    }
}
