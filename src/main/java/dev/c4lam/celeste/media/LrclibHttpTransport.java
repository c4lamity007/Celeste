package dev.c4lam.celeste.media;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Direct HTTPS only. No shell, child process, credentials, cookies, redirects or persistent cache. */
final class LrclibHttpTransport implements LrclibLyricsSource.Transport {
    private final ExecutorService httpWorker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Celeste Lyrics HTTPS");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        return thread;
    });
    private HttpClient client;
    private HttpClient closingClient;
    private boolean closed;

    private synchronized HttpClient client() throws IOException {
        if (closed) throw new IOException("Lyrics transport closed");
        // Lazy creation: enabling unrelated media consumers starts no HTTP resources.
        if (client == null) client = HttpClient.newBuilder().executor(httpWorker)
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        return client;
    }

    @Override public String fetch(URI uri) throws Exception {
        if (!"https".equals(uri.getScheme()) || !"lrclib.net".equals(uri.getHost())
            || uri.getPort() != -1 || !"/api/search".equals(uri.getPath())
            || uri.getUserInfo() != null || uri.getFragment() != null) throw new IOException("Invalid lyrics endpoint");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json").header("User-Agent", "Celeste-SkyLyrics/1.0")
            .GET().build();
        CompletableFuture<HttpResponse<byte[]>> response = client().sendAsync(request, info ->
            new LimitedBodySubscriber(LrclibLyricsSource.MAX_RESPONSE_BYTES,
                info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        try {
            // The wall deadline includes the response body, including a server sending very slowly.
            HttpResponse<byte[]> received = response.get(12, TimeUnit.SECONDS);
            if (received.statusCode() != 200) throw new IOException("Lyrics service unavailable");
            return new String(received.body(), StandardCharsets.UTF_8);
        } finally {
            // Cancellation propagates to the HTTP exchange on timeout, disable, track switch, or shutdown.
            if (!response.isDone()) response.cancel(true);
        }
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit, declaredSize;
        private Flow.Subscription subscription;
        private long count;
        private boolean done;

        LimitedBodySubscriber(long limit, long declaredSize) { this.limit = limit; this.declaredSize = declaredSize; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
            if (declaredSize > limit) fail();
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) {
                count += buffer.remaining();
                if (count > limit) { fail(); return; }
            }
            delegate.onNext(buffers);
        }
        private void fail() {
            if (done) return;
            done = true;
            subscription.cancel();
            delegate.onError(new IOException("Lyrics response exceeds byte limit"));
        }
        @Override public void onError(Throwable error) {
            if (!done) { done = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!done) { done = true; delegate.onComplete(); }
        }
    }

    @Override public void close() {
        HttpClient retired;
        synchronized (this) {
            if (closed) return;
            closed = true;
            retired = client;
            closingClient = retired;
            client = null;
        }
        if (retired != null) retired.shutdownNow();
        httpWorker.shutdownNow();
    }

    @Override public boolean awaitStopped(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpClient retired;
        synchronized (this) { retired = closingClient; }
        if (retired != null && !retired.awaitTermination(timeout)) return false;
        boolean stopped = httpWorker.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        if (stopped) synchronized (this) { closingClient = null; }
        return stopped;
    }
}
