package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import java.util.Objects;

/** Frame clock for the native bridge's whole-second observations, not a new media transport. */
public final class SkyLyricClock {
    private static final double QUANTUM_MS = 1_000;
    private static final double SEEK_MARGIN_MS = 1_500;
    private Object track;
    private MediaPlaybackSnapshot observation;
    private long lastNanos;
    private double position, rate = 1;
    private boolean playing;

    public long sample(Object nextTrack, MediaPlaybackSnapshot next, long nowNanos, long wallTimeMs) {
        if (nextTrack == null || next == null || !next.available()) return positionMs();
        boolean freshTrack = observation == null || !Objects.equals(track, nextTrack);
        if (freshTrack) {
            position = observed(next, wallTimeMs);
            rate = 1;
        } else {
            // Animation advances per rendered frame. A repeated native second never rewinds it.
            long elapsed = nowNanos - lastNanos;
            if (playing && elapsed > 0) position += elapsed / 1_000_000.0 * rate;
        }
        lastNanos = nowNanos;
        if (freshTrack || next != observation) {
            double low = observed(next, wallTimeMs);
            double high = low + QUANTUM_MS;
            double error = position < low ? low - position : position > high ? high - position : 0;
            boolean changedPosition = observation != null && next.progressMs() != observation.progressMs();
            boolean changedTransport = observation != null && next.playing() != playing;
            // Equal seconds are quantization; an actual decrease or editing a paused position is a seek.
            boolean explicitSeek = observation != null && (next.progressMs() < observation.progressMs()
                || changedPosition && !playing && !next.playing());
            if (!freshTrack && (explicitSeek || (changedPosition || changedTransport) && Math.abs(error) > SEEK_MARGIN_MS)) {
                // A genuine seek or long playback discontinuity still follows the song immediately.
                position = low;
                rate = 1;
            } else if (freshTrack || changedPosition) {
                // Correct only outside the quantized interval, with no poll-time position jump.
                rate = 1 + Math.clamp(error / 2_000.0, -.15, .15);
            }
            observation = next;
            track = nextTrack;
            playing = next.playing();
        }
        if (next.durationMs() > 0) position = Math.min(position, next.durationMs());
        position = Math.max(0, position);
        return positionMs();
    }

    private static double observed(MediaPlaybackSnapshot snapshot, long wallTimeMs) {
        double age = snapshot.playing() && snapshot.updatedAt() != null
            ? Math.clamp((double)wallTimeMs - snapshot.updatedAt().toEpochMilli(), 0, QUANTUM_MS) : 0;
        return Math.max(0, snapshot.progressMs()) + age;
    }

    private long positionMs() { return (long)position; }

    public void reset() {
        track = null; observation = null; position = 0; lastNanos = 0; rate = 1; playing = false;
    }
}
