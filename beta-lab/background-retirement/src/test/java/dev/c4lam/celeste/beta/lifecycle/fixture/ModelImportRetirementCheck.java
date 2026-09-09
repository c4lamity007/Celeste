package dev.c4lam.celeste.beta.lifecycle.fixture;
import dev.c4lam.celeste.beta.lifecycle.BetaModelImports;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class ModelImportRetirementCheck {
    public static void main(String[] args) throws Exception {
        if (args.length>0) {
            BetaModelImports.submit(() -> "model", task -> { throw new RejectedExecutionException("fixture queue rejected"); }, (model,error) -> { throw new AssertionError("must not install"); });
            try { BetaModelImports.drained().toCompletableFuture().get(5, TimeUnit.SECONDS); throw new AssertionError("false stopped"); }
            catch (ExecutionException expected) { if (!(expected.getCause() instanceof RejectedExecutionException)) throw expected; }
            System.out.println("PASS model retirement scheduling failure retains failed cleanup"); return;
        }
        LinkedBlockingQueue<Runnable> client = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger installed = new AtomicInteger();
        BetaModelImports.submit(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
            catch (InterruptedException error) { throw new CompletionException(error); }
            return "model";
        }, client::add, (model,error) -> { if (error!=null || !model.equals("model")) throw new AssertionError("result lost"); installed.incrementAndGet(); });
        BetaModelImports.submit(() -> { throw new IllegalArgumentException("invalid model"); }, client::add,
            (model,error) -> { if (!(error instanceof IllegalArgumentException)) throw new AssertionError("source failure lost"); installed.incrementAndGet(); });
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker absent");
        CompletableFuture<Void> stopped=BetaModelImports.drained().toCompletableFuture();
        CompletableFuture<Void> observer=BetaModelImports.drained().toCompletableFuture(); observer.cancel(false);
        if (stopped.isDone() || installed.get()!=0) throw new AssertionError("stopped before worker/client drain");
        release.countDown();
        Runnable one=client.poll(5,TimeUnit.SECONDS), two=client.poll(5,TimeUnit.SECONDS);
        if (one==null || two==null || stopped.isDone()) throw new AssertionError("client delivery not owned");
        one.run(); if (stopped.isDone()) throw new AssertionError("second client delivery still pending");
        two.run(); stopped.get(5,TimeUnit.SECONDS);
        if (installed.get()!=2 || !client.isEmpty()) throw new AssertionError("callback count mismatch");
        BetaModelImports.drained().toCompletableFuture().get(5,TimeUnit.SECONDS);
        try { BetaModelImports.submit(() -> "late", client::add, (model,error) -> {}); throw new AssertionError("restart inside retired loader"); }
        catch (IllegalStateException expected) { }
        System.out.println("PASS model retirement: blocked worker, queued deliveries, handled import failure, observer cancellation, repeated drain, and new-work rejection");
    }
}
