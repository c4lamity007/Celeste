package dev.c4lam.celeste.media;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Opt-in, memory-only LRCLIB lookup. No network or JSON parsing runs on the media/render thread. */
final class LrclibLyricsSource implements AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 1_048_576;
    static final int MAX_CACHE_ENTRIES = 32;
    static final long POSITIVE_TTL_MS = 6 * 60 * 60_000L;
    static final long NEGATIVE_TTL_MS = 10 * 60_000L;
    static final long FAILURE_TTL_MS = 2 * 60_000L;
    private static final long MIN_REQUEST_INTERVAL_MS = 2_000L;
    private static final long TRACK_SETTLE_MS = 650L;

    interface Transport extends AutoCloseable {
        String fetch(URI uri) throws Exception;
        @Override default void close() { }
        default boolean awaitStopped(Duration timeout) throws InterruptedException { return true; }
    }

    /** Position/play state are deliberately absent: seek and pause do not change track identity. */
    record Track(String source, String title, String artist, long durationMs) {
        static Track of(String source, String title, String artist, long durationMs) {
            if (title == null || artist == null || title.length() > 256 || artist.length() > 256
                || durationMs < 1_000 || durationMs > 24 * 60 * 60_000L) return null;
            String cleanTitle = normalize(title), cleanArtist = normalize(artist);
            if (cleanTitle.isBlank() || cleanArtist.isBlank() || cleanTitle.equals("unknown track")) return null;
            return new Track(source == null ? "" : source, cleanTitle, cleanArtist, durationMs);
        }
    }

    private record Cached(TimedLyrics lyrics, long expiresAt) { }
    private final ScheduledThreadPoolExecutor worker;
    private final Transport transport;
    private final LongSupplier clockMs;
    private final long settleMs;
    private final LinkedHashMap<Track, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private Track track;
    private TimedLyrics current = TimedLyrics.EMPTY;
    private Future<?> pending;
    private long generation, nextRequestAt;
    private boolean enabled, closed;
    private CompletableFuture<Void> stopCompletion;

    LrclibLyricsSource() {
        this(new LrclibHttpTransport(), () -> System.nanoTime() / 1_000_000L, TRACK_SETTLE_MS);
    }

    /** Test seam: synthetic responses and a monotonic clock, never listening data or a real server. */
    LrclibLyricsSource(Transport transport, LongSupplier clockMs, long settleMs) {
        this.transport = transport;
        this.clockMs = clockMs;
        this.settleMs = Math.max(0, settleMs);
        nextRequestAt = clockMs.getAsLong();
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Celeste Lyrics Lookup");
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    synchronized void setEnabled(boolean enabled) {
        if (closed || this.enabled == enabled) return;
        this.enabled = enabled;
        reset();
        if (!enabled) cache.clear();
    }

    synchronized TimedLyrics update(String source, String title, String artist, long durationMs, TimedLyrics nativeLyrics) {
        TimedLyrics supplied = nativeLyrics == null ? TimedLyrics.EMPTY : nativeLyrics;
        if (closed || !enabled) return TimedLyrics.EMPTY;
        Track next = Track.of(source, title, artist, durationMs);
        if (!Objects.equals(track, next)) {
            reset();
            track = next;
        }
        if (!supplied.lines().isEmpty()) {
            cancelPending();
            current = supplied;
            return supplied;
        }
        if (next == null) return TimedLyrics.EMPTY;
        long now = clockMs.getAsLong();
        Cached cached = cache.get(next);
        if (cached != null && now < cached.expiresAt()) return current = cached.lyrics();
        if (cached != null) cache.remove(next);
        if (pending == null) {
            current = TimedLyrics.EMPTY;
            long requestGeneration = ++generation;
            URI uri = searchUri(title, artist);
            long delay = Math.max(settleMs, Math.max(0, nextRequestAt - now));
            try {
                pending = worker.schedule(() -> lookup(next, uri, requestGeneration), delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopped) {
                // Lifecycle shutdown is terminal; never recreate a worker from a late poll.
            }
        }
        return current;
    }

    synchronized void reset() {
        cancelPending();
        track = null;
        current = TimedLyrics.EMPTY;
    }

    private void cancelPending() {
        generation++;
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    private void lookup(Track requested, URI uri, long requestGeneration) {
        synchronized (this) {
            if (!isCurrent(requested, requestGeneration)) return;
            nextRequestAt = clockMs.getAsLong() + MIN_REQUEST_INTERVAL_MS;
        }
        TimedLyrics result = TimedLyrics.EMPTY;
        boolean failed = false;
        try {
            result = parseResponse(transport.fetch(uri), requested);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failed = true;
        } catch (Exception | LinkageError failure) {
            // No query, response body, or listening history is written to logs/disk.
            failed = true;
        }
        synchronized (this) {
            if (!isCurrent(requested, requestGeneration)) return;
            long ttl = failed ? FAILURE_TTL_MS : result.lines().isEmpty() ? NEGATIVE_TTL_MS : POSITIVE_TTL_MS;
            cache.put(requested, new Cached(result, clockMs.getAsLong() + ttl));
            while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
            current = result;
            pending = null;
        }
    }

    private boolean isCurrent(Track requested, long requestGeneration) {
        return enabled && !closed && generation == requestGeneration && requested.equals(track);
    }

    static URI searchUri(String title, String artist) {
        // The native session has no album, so /api/get's full signature is unavailable.
        // /api/search accepts these two fields; duration is validated locally, not guessed.
        return URI.create("https://lrclib.net/api/search?track_name=" + encode(title.strip())
            + "&artist_name=" + encode(artist.strip()));
    }

    private static String encode(String input) { return URLEncoder.encode(input, StandardCharsets.UTF_8); }

    static String normalize(String input) {
        // Keep punctuation, accents and version suffixes: fuzzy matching can show another recording's lyrics.
        return Normalizer.normalize(input, Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]+", " ").strip().toLowerCase(Locale.ROOT);
    }

    static TimedLyrics parseResponse(String body, Track requested) throws IOException {
        if (body == null || body.length() > MAX_RESPONSE_BYTES || requested == null) return TimedLyrics.EMPTY;
        TimedLyrics best = TimedLyrics.EMPTY;
        double bestDistance = Double.POSITIVE_INFINITY;
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.beginArray();
            int records = 0;
            while (reader.hasNext()) {
                if (++records > 64) throw new IOException("Too many lyric candidates");
                String title = "", artist = "", synced = "";
                double duration = Double.NaN;
                boolean instrumental = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "trackName" -> title = readString(reader);
                        case "artistName" -> artist = readString(reader);
                        case "syncedLyrics" -> synced = readString(reader);
                        case "duration" -> {
                            if (reader.peek() == JsonToken.NUMBER) duration = reader.nextDouble();
                            else skip(reader, 0);
                        }
                        case "instrumental" -> {
                            if (reader.peek() == JsonToken.BOOLEAN) instrumental = reader.nextBoolean();
                            else skip(reader, 0);
                        }
                        default -> skip(reader, 0);
                    }
                }
                reader.endObject();
                double distance = Math.abs(duration * 1000 - requested.durationMs());
                if (instrumental || !Double.isFinite(duration) || duration <= 0 || distance > 2_000
                    || distance >= bestDistance || synced.isBlank() || synced.length() > 512_000
                    || title.length() > 256 || artist.length() > 256
                    || !normalize(title).equals(requested.title()) || !normalize(artist).equals(requested.artist())) continue;
                TimedLyrics parsed = TimedLyrics.parse(synced);
                // Only server-supplied timestamps inside this recording are useful; never time plain lyrics.
                if (parsed.lines().stream().noneMatch(line -> !line.text().isBlank() && line.timeMs() < requested.durationMs())) continue;
                best = parsed;
                bestDistance = distance;
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing lyric response data");
        } catch (IllegalStateException | NumberFormatException malformed) {
            throw new IOException("Malformed lyric response", malformed);
        }
        return best;
    }

    private static String readString(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING) return reader.nextString();
        skip(reader, 0);
        return "";
    }

    private static void skip(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("Lyric response nesting limit");
        switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) skip(reader, depth + 1);
                reader.endArray();
            }
            case BEGIN_OBJECT -> {
                reader.beginObject();
                while (reader.hasNext()) { reader.nextName(); skip(reader, depth + 1); }
                reader.endObject();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("Invalid lyric response value");
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            enabled = false;
            reset();
            cache.clear();
        }
        worker.shutdownNow();
        transport.close();
    }

    synchronized CompletionStage<Void> stopped() {
        if (!closed) return CompletableFuture.failedFuture(new IllegalStateException("Lyrics source is not closed"));
        if (stopCompletion == null) stopCompletion = CompletableFuture.runAsync(() -> {
            try {
                if (!worker.awaitTermination(15, TimeUnit.SECONDS) || !transport.awaitStopped(Duration.ofSeconds(15)))
                    throw new IllegalStateException("Lyrics worker did not finish stopping");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lyrics shutdown observation was interrupted", interrupted);
            }
        });
        return stopCompletion.minimalCompletionStage();
    }
}
