package dev.c4lam.celeste.beta.lifecycle.fixture;

import dev.c4lam.celeste.beta.lifecycle.BetaRuntimeLifecycle;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeLifecycleCheck {
    private static int checks;
    private RuntimeLifecycleCheck() { }

    public static void main(String[] ignored) {
        BetaRuntimeLifecycle normal = new BetaRuntimeLifecycle();
        check(normal.beginStart() && !normal.beginStart(), "start once");
        check(!normal.ready().toCompletableFuture().isDone(), "queued/authentication work is not ready");
        normal.ready().toCompletableFuture().complete(null);
        check(!normal.ready().toCompletableFuture().isDone(), "caller cannot forge readiness through returned stage");
        check(normal.activate() && normal.ready().toCompletableFuture().isDone(), "authorized activation publishes readiness");
        normal.requestStop();
        check(!normal.stopped().toCompletableFuture().isDone(), "requested stop awaits cleanup");
        normal.finish();
        check(normal.snapshot().get("phase").equals("STOPPED"), "clean stop terminal state");
        normal.ready().toCompletableFuture().join();
        normal.stopped().toCompletableFuture().join();
        check(!normal.activate() && !normal.beginStart(), "retired lifecycle cannot restart");

        BetaRuntimeLifecycle cancelled = new BetaRuntimeLifecycle();
        cancelled.requestStop();
        check(!cancelled.beginStart() && !cancelled.activate(), "stop before queued start blocks activation");
        expectCause(CancellationException.class, () -> cancelled.ready().toCompletableFuture().join());
        cancelled.finish();
        cancelled.stopped().toCompletableFuture().join();

        BetaRuntimeLifecycle rejected = new BetaRuntimeLifecycle();
        rejected.beginStart();
        SecurityException refusal = new SecurityException("license revoked");
        rejected.fail(refusal);
        check(!rejected.activate(), "authorization rejection prevents readiness");
        check(cause(() -> rejected.ready().toCompletableFuture().join()) == refusal, "readiness preserves authorization failure");
        check(!rejected.stopped().toCompletableFuture().isDone(), "failure still waits for service cleanup");
        RuntimeException cleanup = new IllegalStateException("cleanup failed");
        rejected.fail(cleanup);
        rejected.finish();
        check(cause(() -> rejected.stopped().toCompletableFuture().join()) == refusal, "stop preserves primary failure");
        check(refusal.getSuppressed().length == 1 && refusal.getSuppressed()[0] == cleanup, "cleanup failure remains observable");
        check(rejected.snapshot().get("phase").equals("FAILED"), "failed terminal state");

        BetaRuntimeLifecycle revoked = new BetaRuntimeLifecycle();
        revoked.beginStart();
        revoked.activate();
        revoked.fail(new SecurityException("revoked after startup"));
        revoked.finish();
        revoked.ready().toCompletableFuture().join();
        expectCause(SecurityException.class, () -> revoked.stopped().toCompletableFuture().join());

        BetaRuntimeLifecycle reentrant = new BetaRuntimeLifecycle();
        reentrant.beginStart();
        AtomicInteger finished = new AtomicInteger();
        reentrant.ready().thenRun(() -> { reentrant.requestStop(); reentrant.finish(); finished.incrementAndGet(); });
        check(reentrant.activate(), "activation can notify a callback that immediately requests stop");
        check(finished.get() == 1 && reentrant.snapshot().get("phase").equals("STOPPED"), "reentrant stop finishes once");

        BetaRuntimeLifecycle scheduling = new BetaRuntimeLifecycle();
        scheduling.beginStart();
        scheduling.stopSchedulingFailed(new IllegalStateException("client executor stopped"));
        expectCause(IllegalStateException.class, () -> scheduling.stopped().toCompletableFuture().join());
        check(scheduling.snapshot().get("phase").equals("FAILED"), "failed scheduling is observable without claiming cleanup");
        System.out.println("Beta runtime lifecycle: " + checks + " checks passed");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    private static Throwable cause(Runnable action) {
        try { action.run(); }
        catch (CompletionException failure) { return failure.getCause(); }
        catch (CancellationException failure) { return failure; }
        throw new AssertionError("Expected exceptional completion");
    }

    private static void expectCause(Class<? extends Throwable> expected, Runnable action) {
        check(expected.isInstance(cause(action)), "expected " + expected.getSimpleName());
    }
}
