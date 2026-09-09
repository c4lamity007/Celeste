package dev.c4lam.celeste.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;

/** Deterministic synthetic fixtures. Never contacts LRCLIB or reads the user's listening data. */
public final class LrclibLyricsSourceCheck {
    private static int checks;
    private static final String LRC = "[00:01.00]Synthetic first line\n[00:03.50]Synthetic second line\n[00:06.00]";
    private static final String BODY = response("Fixture Orbit", "Fixture Artist", 120, LRC, false);

    public static void main(String[] args) throws Exception {
        parsing();
        bodyLimits();
        lifecycleAndCache();
        staleAndCancellation();
        cacheBound();
        System.out.println("LrclibLyricsSourceCheck PASS: " + checks
            + " offline matching/cache/cancellation/timeline/byte-limit assertions; no live media, network or child process");
    }

    private static void parsing() throws Exception {
        var track = LrclibLyricsSource.Track.of("spotify", "Fixture Orbit", "Fixture Artist", 120_000);
        TimedLyrics parsed = LrclibLyricsSource.parseResponse(BODY, track);
        check(parsed.cue(1000, 120000).current().equals("Synthetic first line"), "server-supplied timestamp selects first line");
        check(parsed.cue(3500, 120000).current().equals("Synthetic second line"), "fractional server timestamp retained");
        check(parsed.cue(2000, 120000).current().equals("Synthetic first line"), "backward seek uses timeline without refetch");
        check(parsed.cue(120000, 120000).current().isEmpty(), "track end hides timeline");
        var paused = new MediaPlaybackSnapshot(true, false, false, false, false, false, false,
            "Spotify", "spotify", "Fixture Orbit", "Fixture Artist", "", "", "", 2000, 120000, "", Instant.EPOCH, parsed);
        check(paused.estimatedProgressMs() == 2000, "paused lyric clock does not advance");
        check(LrclibLyricsSource.Track.of("spotify", "Fixture Orbit", "", 120000) == null, "missing artist never triggers fuzzy lookup");
        check(LrclibLyricsSource.Track.of("spotify", "Fixture Orbit", "Fixture Artist", 0) == null, "unknown duration prevents unsafe match");
        check(LrclibLyricsSource.Track.of("spotify", "x".repeat(257), "Artist", 120000) == null, "query title is bounded");
        check(LrclibLyricsSource.parseResponse(response(" fixture\u00a0ORBIT ", "FIXTURE ARTIST", 122, LRC, false), track).lines().size() == 3,
            "case/unicode spaces normalize; exact two-second duration tolerance accepted");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit (Live)", "Fixture Artist", 120, LRC, false), track).lines().isEmpty(),
            "version suffix is not stripped");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Other Artist", 120, LRC, false), track).lines().isEmpty(),
            "artist mismatch rejected");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Fixture Artist", 122.001, LRC, false), track).lines().isEmpty(),
            "duration just outside tolerance rejected");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Fixture Artist", -120, LRC, false), track).lines().isEmpty(),
            "invalid duration rejected");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Fixture Artist", 120, null, false), track).lines().isEmpty(),
            "plain lyrics never get generated timings");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Fixture Artist", 120, LRC, true), track).lines().isEmpty(),
            "instrumental record is not displayed");
        check(LrclibLyricsSource.parseResponse(response("Fixture Orbit", "Fixture Artist", 120, "[03:01]Past the end", false), track).lines().isEmpty(),
            "timeline only beyond recording end rejected");
        JsonArray choices = new JsonArray();
        choices.add(com.google.gson.JsonParser.parseString(response("Fixture Orbit", "Fixture Artist", 122, "[00:01]Farther", false)).getAsJsonArray().get(0));
        choices.add(com.google.gson.JsonParser.parseString(response("Fixture Orbit", "Fixture Artist", 120, "[00:01]Closest", false)).getAsJsonArray().get(0));
        check(LrclibLyricsSource.parseResponse(choices.toString(), track).cue(1000, 120000).current().equals("Closest"),
            "closest duration wins rather than first search result");
        expectMalformed("{}", track, "non-array response rejected");
        expectMalformed("[", track, "truncated JSON rejected");
        expectMalformed(BODY + " true", track, "trailing JSON rejected");
        expectMalformed("[{\"unknown\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}]", track, "deep unknown fields bounded");
        check(LrclibLyricsSource.parseResponse(" ".repeat(LrclibLyricsSource.MAX_RESPONSE_BYTES + 1), track).lines().isEmpty(),
            "oversized injected body rejected before parsing");
        URI uri = LrclibLyricsSource.searchUri("Fixture & Orbit?", "Artist / Test");
        check(uri.getHost().equals("lrclib.net") && uri.getPath().equals("/api/search"), "fixed HTTPS provider endpoint");
        check(uri.getRawQuery().equals("track_name=Fixture+%26+Orbit%3F&artist_name=Artist+%2F+Test"), "metadata safely URL encoded");
    }

    private static void bodyLimits() throws Exception {
        var valid = new LrclibHttpTransport.LimitedBodySubscriber(4, -1);
        var validSubscription = new Subscription();
        valid.onSubscribe(validSubscription);
        valid.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        valid.onComplete();
        check(valid.getBody().toCompletableFuture().get().length == 4, "exact byte limit accepted across buffers");
        var overflow = new LrclibHttpTransport.LimitedBodySubscriber(4, -1);
        var overflowSubscription = new Subscription();
        overflow.onSubscribe(overflowSubscription);
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3}), ByteBuffer.wrap(new byte[]{4, 5})));
        check(overflowSubscription.cancelled && overflow.getBody().toCompletableFuture().isCompletedExceptionally(),
            "chunked oversized body cancels subscription before storing excess");
        var declared = new LrclibHttpTransport.LimitedBodySubscriber(4, 5);
        var declaredSubscription = new Subscription();
        declared.onSubscribe(declaredSubscription);
        check(declaredSubscription.cancelled && declared.getBody().toCompletableFuture().isCompletedExceptionally(),
            "oversized declared body rejected immediately");
        try (var transport = new LrclibHttpTransport()) {
            try { transport.fetch(URI.create("https://example.invalid/api/search")); throw new AssertionError("foreign endpoint accepted"); }
            catch (IOException expected) { checks++; }
        }
    }

    private static void lifecycleAndCache() throws Exception {
        AtomicLong clock = new AtomicLong(-100_000); // nanoTime may have a negative origin.
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> workerName = new AtomicReference<>();
        AtomicReference<ClassLoader> contextLoader = new AtomicReference<>();
        AtomicReference<String> response = new AtomicReference<>(BODY);
        AtomicBoolean fail = new AtomicBoolean();
        try (var source = new LrclibLyricsSource(uri -> {
            requests.incrementAndGet();
            workerName.set(Thread.currentThread().getName());
            contextLoader.set(Thread.currentThread().getContextClassLoader());
            if (fail.get()) throw new IOException("Synthetic network outage");
            return response.get();
        }, clock::get, 0)) {
            check(update(source).lines().isEmpty() && requests.get() == 0, "disabled state makes no request");
            source.setEnabled(true);
            TimedLyrics first = awaitLyrics(source);
            check(requests.get() == 1 && workerName.get().equals("Celeste Lyrics Lookup"), "lookup and parse use dedicated worker");
            check(contextLoader.get() == ClassLoader.getPlatformClassLoader(), "lookup worker cannot retain hot-loaded TCCL");
            check(update(source) == first && requests.get() == 1, "unchanged track reuses immutable cache");
            source.reset();
            check(update(source) == first && requests.get() == 1, "session reset may reuse valid same-track cache");
            clock.addAndGet(LrclibLyricsSource.POSITIVE_TTL_MS + 1);
            awaitLyricsAfter(source, 2, requests);
            check(requests.get() == 2, "expired positive cache refreshes");
            source.setEnabled(false);
            check(update(source).lines().isEmpty(), "disable clears current lyrics");
            clock.addAndGet(5000);
            source.setEnabled(true);
            response.set("[]");
            update(source);
            await(() -> requests.get() == 3, "negative lookup begins");
            awaitIdle(source);
            for (int i = 0; i < 20; i++) check(update(source).lines().isEmpty(), "no-result state stays empty");
            check(requests.get() == 3, "negative cache prevents per-poll retries");
            clock.addAndGet(LrclibLyricsSource.NEGATIVE_TTL_MS + 1);
            fail.set(true);
            update(source);
            await(() -> requests.get() == 4, "expired miss retries");
            awaitIdle(source);
            clock.addAndGet(LrclibLyricsSource.FAILURE_TTL_MS - 1);
            update(source);
            check(requests.get() == 4, "failure backoff suppresses repeat requests");
            clock.addAndGet(2);
            fail.set(false);
            response.set(BODY);
            awaitLyricsAfter(source, 5, requests);
            check(requests.get() == 5, "outage recovers after bounded backoff");
            TimedLyrics supplied = TimedLyrics.parse("[00:01]Native synthetic cue");
            check(source.update("spotify", "Other", "Artist", 100000, supplied) == supplied, "native payload preferred without online request");
            check(requests.get() == 5, "native payload never sends lookup");
            source.close();
            source.stopped().toCompletableFuture().get(2, TimeUnit.SECONDS);
            source.setEnabled(true);
            check(update(source).lines().isEmpty() && requests.get() == 5, "terminal shutdown cannot be revived by late tick");
        }
    }

    private static void staleAndCancellation() throws Exception {
        AtomicLong clock = new AtomicLong(10_000);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var source = new LrclibLyricsSource(uri -> {
            int index = requests.incrementAndGet();
            if (index == 1) {
                firstStarted.countDown();
                while (releaseFirst.getCount() > 0) {
                    try { releaseFirst.await(); }
                    catch (InterruptedException ignored) { interrupted.set(true); } // Deliberately late response fixture.
                }
                return BODY;
            }
            return response("Second Orbit", "Fixture Artist", 121, "[00:01]Second recording", false);
        }, clock::get, 0)) {
            source.setEnabled(true);
            check(update(source).lines().isEmpty(), "initial request returns immediately without blocking caller");
            check(firstStarted.await(2, TimeUnit.SECONDS), "synthetic request is in flight");
            clock.addAndGet(5000);
            check(source.update("spotify", "Second Orbit", "Fixture Artist", 121000, TimedLyrics.EMPTY).lines().isEmpty(),
                "track switch immediately clears previous lyrics");
            releaseFirst.countDown();
            await(() -> source.update("spotify", "Second Orbit", "Fixture Artist", 121000, TimedLyrics.EMPTY)
                .cue(1000, 121000).current().equals("Second recording"), "current track receives its own late-safe result");
            check(interrupted.get(), "track switch cancels old request");
            clock.addAndGet(5000);
            check(source.update("spotify", "Second Orbit", "Fixture Artist", 122000, TimedLyrics.EMPTY).lines().isEmpty(),
                "native duration change invalidates track identity");
            source.setEnabled(false);
            check(source.update("spotify", "Second Orbit", "Fixture Artist", 122000, TimedLyrics.EMPTY).lines().isEmpty(),
                "disable rejects queued or completed results");
        } finally { releaseFirst.countDown(); }
        AtomicInteger queuedRequests = new AtomicInteger();
        try (var source = new LrclibLyricsSource(uri -> { queuedRequests.incrementAndGet(); return BODY; }, clock::get, 60_000)) {
            source.setEnabled(true);
            update(source);
            source.setEnabled(false);
            check(queuedRequests.get() == 0, "disable cancels unsettled metadata before any network send");
        }
    }

    private static void cacheBound() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        AtomicInteger requests = new AtomicInteger();
        try (var source = new LrclibLyricsSource(uri -> { requests.incrementAndGet(); return "[]"; }, clock::get, 0)) {
            source.setEnabled(true);
            for (int i = 0; i < LrclibLyricsSource.MAX_CACHE_ENTRIES + 1; i++) {
                clock.addAndGet(5000);
                source.update("spotify", "Synthetic Track " + i, "Fixture Artist", 120000, TimedLyrics.EMPTY);
                int expected = i + 1;
                await(() -> requests.get() == expected, "bounded-cache fixture request");
                awaitIdle(source);
            }
            var cache = LrclibLyricsSource.class.getDeclaredField("cache");
            cache.setAccessible(true);
            check(((java.util.Map<?, ?>)cache.get(source)).size() == LrclibLyricsSource.MAX_CACHE_ENTRIES, "LRU entry count strictly bounded");
            clock.addAndGet(5000);
            source.update("spotify", "Synthetic Track 0", "Fixture Artist", 120000, TimedLyrics.EMPTY);
            await(() -> requests.get() == LrclibLyricsSource.MAX_CACHE_ENTRIES + 2, "evicted first entry fetches again");
            source.setEnabled(false);
            check(((java.util.Map<?, ?>)cache.get(source)).isEmpty(), "disable clears memory-only listening metadata");
        }
    }

    private static TimedLyrics update(LrclibLyricsSource source) {
        return source.update("spotify", "Fixture Orbit", "Fixture Artist", 120000, TimedLyrics.EMPTY);
    }
    private static TimedLyrics awaitLyrics(LrclibLyricsSource source) throws Exception {
        await(() -> !update(source).lines().isEmpty(), "synthetic lyrics arrive");
        return update(source);
    }
    private static void awaitLyricsAfter(LrclibLyricsSource source, int count, AtomicInteger requests) throws Exception {
        update(source);
        await(() -> requests.get() == count && !update(source).lines().isEmpty(), "refreshed synthetic lyrics arrive");
    }
    private static void awaitIdle(LrclibLyricsSource source) throws Exception {
        var pending = LrclibLyricsSource.class.getDeclaredField("pending");
        pending.setAccessible(true);
        await(() -> { synchronized (source) { try { return pending.get(source) == null; } catch (IllegalAccessException e) { throw new AssertionError(e); } } }, "lookup publication finished");
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        check(condition.getAsBoolean(), message);
    }
    private static void expectMalformed(String json, LrclibLyricsSource.Track track, String message) throws Exception {
        try { LrclibLyricsSource.parseResponse(json, track); throw new AssertionError(message); }
        catch (IOException expected) { checks++; }
    }
    private static String response(String title, String artist, double seconds, String synced, boolean instrumental) {
        JsonObject item = new JsonObject();
        item.addProperty("trackName", title);
        item.addProperty("artistName", artist);
        item.addProperty("duration", seconds);
        item.addProperty("syncedLyrics", synced);
        item.addProperty("plainLyrics", "Synthetic plain text must never be timed");
        item.addProperty("instrumental", instrumental);
        JsonArray array = new JsonArray(); array.add(item); return array.toString();
    }
    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) { }
        @Override public void cancel() { cancelled = true; }
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
}
