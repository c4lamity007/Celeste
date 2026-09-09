package dev.c4lam.celeste.module.impl.combat;
import dev.c4lam.celeste.beta.lifecycle.BetaNetworkRetirement;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.*;
public final class NetworkRetirementCheck {
    public static void main(String[] args) throws Exception {
        EmbeddedChannel channel=new EmbeddedChannel();
        FakeLagPacketBuffer buffer=new FakeLagPacketBuffer(channel);
        channel.pipeline().addLast("celeste-fixture",buffer);
        buffer.stop(false);
        CompletableFuture<Void> stopped=BetaNetworkRetirement.drained().toCompletableFuture();
        if (stopped.isDone()) throw new AssertionError("retired inside handler removal callback");
        if (channel.pipeline().context(buffer)!=null) throw new AssertionError("handler not removed");
        CompletableFuture<Void> observer=BetaNetworkRetirement.drained().toCompletableFuture(); observer.cancel(false);
        buffer.stop(true);
        channel.runPendingTasks(); stopped.get(5,TimeUnit.SECONDS);
        if (!channel.isActive()) throw new AssertionError("Stop closed Minecraft connection");
        if (!channel.writeOutbound("unchanged") || !"unchanged".equals(channel.readOutbound())) throw new AssertionError("outbound route changed");
        BetaNetworkRetirement.drained().toCompletableFuture().get(5,TimeUnit.SECONDS);
        channel.finishAndReleaseAll();
        CompletableFuture<Void> failed=new CompletableFuture<>(); BetaNetworkRetirement.track(failed);
        failed.completeExceptionally(new RejectedExecutionException("fixture event loop rejected"));
        try { BetaNetworkRetirement.drained().toCompletableFuture().get(5,TimeUnit.SECONDS); throw new AssertionError("cleanup failure ignored"); }
        catch (ExecutionException expected) { if (!(expected.getCause() instanceof RejectedExecutionException)) throw expected; }
        System.out.println("PASS network retirement: real Netty removal, event-loop boundary, observer cancellation, repeated Stop, connection/outbound preservation, failed cleanup");
    }
}
