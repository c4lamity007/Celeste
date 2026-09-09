package dev.celestebeta.host.fixture;

import dev.celestebeta.host.DetachableCallbacks;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executable lifecycle cases; no game process, network, credentials, or event-array edits. */
public final class CallbacksCheck {
    private static int checks;

    public interface Tick { void tick(); }
    public interface Reload extends Tick {
        default String label() { return "default label"; }
        default CompletableFuture<Void> later(Executor executor) {
            return CompletableFuture.runAsync(this::tick, executor);
        }
    }
    public interface Invalid { int value(); }

    private CallbacksCheck() { }

    public static void main(String[] ignored) throws Exception {
        reuseAndIdentity();
        closeFromCallback();
        closeDuringCallback();
        defaultsAndPendingWork();
        exceptionsAndFailedRegistration();
        subscriptionScope();
        System.out.println("Detachable callback lifecycle: " + checks + " checks passed");
    }

    private static void reuseAndIdentity() {
        AtomicReference<Tick> registered = new AtomicReference<>();
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Object owner = new EqualOwner();
        var first = DetachableCallbacks.connect(owner, "tick", Tick.class, calls::incrementAndGet, proxy -> {
            registered.set(proxy);
            registrations.incrementAndGet();
        });
        Tick proxy = registered.get();
        check(Proxy.isProxyClass(proxy.getClass()), "game-owned interface proxy");
        check(proxy.getClass().getClassLoader() == Tick.class.getClassLoader(), "proxy uses interface loader");
        check(Proxy.getInvocationHandler(proxy).getClass().getClassLoader() == DetachableCallbacks.class.getClassLoader(),
            "handler belongs to parent bridge");
        check(proxy.equals(proxy) && !proxy.equals(owner), "identity equality");
        check(proxy.hashCode() == System.identityHashCode(proxy), "identity hash");
        proxy.tick();
        check(calls.get() == 1 && !first.detached().toCompletableFuture().isDone(), "active callback");
        expect(IllegalStateException.class, () -> DetachableCallbacks.connect(owner, "tick", Tick.class, () -> {}, p -> {}));
        first.close();
        check(first.detached().toCompletableFuture().isDone(), "idle detach finishes immediately");
        proxy.tick();
        check(calls.get() == 1, "detached proxy is inert");
        var second = DetachableCallbacks.connect(owner, "tick", Tick.class, () -> calls.addAndGet(10), p -> {
            throw new AssertionError("must not register twice");
        });
        first.close();
        proxy.tick();
        check(calls.get() == 11 && registrations.get() == 1, "same proxy reuses new payload and ignores old close");
        AtomicReference<Tick> otherProxy = new AtomicReference<>();
        var other = DetachableCallbacks.connect(new EqualOwner(), "tick", Tick.class, () -> {}, otherProxy::set);
        check(otherProxy.get() != proxy, "equal owners remain distinct by identity");
        other.close();
        second.close();
        expect(IllegalArgumentException.class, () -> DetachableCallbacks.connect(new Object(), "invalid",
            Invalid.class, () -> 1, p -> {}));
    }

    private static void closeFromCallback() {
        AtomicReference<Tick> proxy = new AtomicReference<>();
        AtomicReference<DetachableCallbacks.Registration> registration = new AtomicReference<>();
        registration.set(DetachableCallbacks.connect(new Object(), "self", Tick.class, () -> {
            registration.get().close();
            check(!registration.get().detached().toCompletableFuture().isDone(), "close never waits inside its own call");
        }, proxy::set));
        proxy.get().tick();
        check(registration.get().detached().toCompletableFuture().isDone(), "self-close drains after return");
        proxy.get().tick();
    }

    private static void closeDuringCallback() throws Exception {
        Object owner = new Object();
        AtomicReference<Tick> proxy = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var registration = DetachableCallbacks.connect(owner, "concurrent", Tick.class, () -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }, proxy::set);
        Thread worker = new Thread(() -> {
            try { proxy.get().tick(); } catch (Throwable problem) { failure.set(problem); }
        }, "callback-fixture");
        worker.start();
        try {
            check(started.await(5, TimeUnit.SECONDS), "callback entered");
            registration.close();
            check(!registration.detached().toCompletableFuture().isDone(), "executing callback remains pending");
            expect(IllegalStateException.class, () -> DetachableCallbacks.connect(owner, "concurrent", Tick.class, () -> {}, p -> {}));
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        check(!worker.isAlive() && failure.get() == null, "in-flight callback returns normally");
        check(registration.detached().toCompletableFuture().isDone(), "concurrent detach drained");
    }

    private static void defaultsAndPendingWork() {
        Object owner = new Object();
        AtomicReference<Reload> proxy = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        var registration = DetachableCallbacks.connect(owner, "reload", Reload.class, calls::incrementAndGet, proxy::set);
        check(proxy.get().label().equals("default label"), "default method preserved");
        CompletableFuture<Void> reload = proxy.get().later(queued::add);
        check(!reload.isDone(), "default method schedules normal work");
        registration.close();
        check(!registration.detached().toCompletableFuture().isDone(), "default asynchronous work included in drainage");
        expect(IllegalStateException.class, () -> DetachableCallbacks.connect(owner, "reload", Reload.class, () -> {}, p -> {}));
        queued.remove().run();
        check(reload.isDone() && !reload.isCompletedExceptionally(), "detached reloader still satisfies default completion");
        check(calls.get() == 0, "queued reloader cannot call closed payload");
        check(registration.detached().toCompletableFuture().isDone(), "old reload has drained");
        CompletableFuture<Void> inactive = proxy.get().later(queued::add);
        expect(IllegalStateException.class, () -> DetachableCallbacks.connect(owner, "reload", Reload.class, () -> {}, p -> {}));
        queued.remove().run();
        check(inactive.isDone(), "inactive default work cannot leak into a new attachment");
        var replacement = DetachableCallbacks.connect(owner, "reload", Reload.class, calls::incrementAndGet,
            p -> { throw new AssertionError("duplicate reloader"); });
        proxy.get().later(Runnable::run).join();
        check(calls.get() == 1, "replacement runs after old default work drains");
        replacement.close();
    }

    private static void exceptionsAndFailedRegistration() {
        AtomicReference<Tick> proxy = new AtomicReference<>();
        RuntimeException expected = new IllegalStateException("fixture exception");
        var registration = DetachableCallbacks.connect(new Object(), "throws", Tick.class, () -> { throw expected; }, proxy::set);
        try { proxy.get().tick(); throw new AssertionError("missing listener failure"); }
        catch (RuntimeException actual) { check(actual == expected, "listener failure preserved exactly"); }
        registration.close();
        check(registration.detached().toCompletableFuture().isDone(), "throwing callback drains");
        Object failedOwner = new Object();
        AtomicInteger calls = new AtomicInteger();
        try {
            DetachableCallbacks.connect(failedOwner, "partial", Tick.class, calls::incrementAndGet, p -> {
                proxy.set(p);
                throw expected;
            });
            throw new AssertionError("missing registrar failure");
        } catch (RuntimeException actual) { check(actual == expected, "registrar failure preserved"); }
        proxy.get().tick();
        check(calls.get() == 0, "partial registration clears listener");
        expect(IllegalStateException.class, () -> DetachableCallbacks.connect(failedOwner, "partial", Tick.class, () -> {}, p -> {}));
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
    }

    private static void subscriptionScope() {
        AtomicReference<Tick> startup = new AtomicReference<>();
        Tick original = () -> {};
        BetaSubscriptions.register(new Object(), "startup", Tick.class, original, startup::set);
        check(startup.get() == original, "ordinary startup retains original direct listener");
        BetaSubscriptions scope = BetaSubscriptions.open();
        AtomicReference<Tick> first = new AtomicReference<>();
        AtomicReference<Tick> second = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        BetaSubscriptions.register(new Object(), "scope_first", Tick.class, () -> {
            scope.close();
            check(!scope.detached().toCompletableFuture().isDone(), "scope close waits for enclosing callback return");
        }, first::set);
        BetaSubscriptions.register(new Object(), "scope_second", Tick.class, calls::incrementAndGet, second::set);
        check(Proxy.isProxyClass(first.get().getClass()), "late scope installs parent proxy");
        second.get().tick();
        check(calls.get() == 1, "active scoped callback runs normally");
        first.get().tick();
        check(scope.detached().toCompletableFuture().isDone(), "all scoped calls retired");
        second.get().tick();
        check(calls.get() == 1, "every closed scoped listener is detached");
        expect(IllegalStateException.class, () -> BetaSubscriptions.register(new Object(), "late_after_close",
            Tick.class, () -> {}, p -> { throw new AssertionError("must not fall back to direct registration"); }));
        expect(IllegalStateException.class, BetaSubscriptions::open);
        scope.close();
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError("Expected " + type.getSimpleName(), failure);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class EqualOwner {
        @Override public boolean equals(Object other) { return other instanceof EqualOwner; }
        @Override public int hashCode() { return 1; }
    }
}
