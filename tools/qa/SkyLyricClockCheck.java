package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.TimedLyrics;
import java.time.Instant;

/** Behavioral checks of the production lyric clock; synthetic time/media only, no sleeps or network. */
public final class SkyLyricClockCheck {
    private static final long WALL = 1_800_000_000_000L;
    private static final long NANOS = 6_000_000_000L;
    private static int checks;

    public static void main(String[] args) {
        quantizedPolls();
        stalledNativePosition();
        gentleCorrection();
        quantizationSeekBoundary();
        pauseResumeAndPausedSeek();
        hiddenGapThenPause();
        smallExplicitSeeks();
        trackResetAndDuration();
        unchangedSnapshotIgnoresWallClock();
        phraseAnchorSurvivesSeek();
        System.out.println("SkyLyricClockCheck PASS: " + checks
            + " synthetic clock, quantization, transport, and phrase-anchor assertions");
    }

    private static void quantizedPolls() {
        var clock = new SkyLyricClock();
        var lyrics = TimedLyrics.parse("[00:10]<00:10>One <00:13>small <00:16>orbit<00:19>\n[00:20]Next lantern");
        var anchor = new SkyLyricAnchor();
        Object world = new Object();
        var observation = snapshot(true, 10_000, 30_000, 0);
        long previous = at(clock, "song", observation, 0);
        var pose = anchor.sample(world, "song", 10_000, previous, 10, 70, 20, 0, 0, 0, 1, 38);
        double previousCueProgress = 0;
        double[] wordProgress = new double[3];
        for (int elapsed = 10; elapsed <= 4_200; elapsed += 10) {
            if (elapsed % 350 == 0) {
                observation = snapshot(true, 10_000 + (elapsed / 1_000) * 1_000, 30_000, elapsed);
            }
            long position = at(clock, "song", observation, elapsed);
            smooth(previous, position, 10, "350 ms whole-second polls cannot rewind or restart animation");
            var cue = lyrics.wordCue(position, 30_000);
            check(cue.startMs() == 10_000, "test remains in the same phrase");
            check(cue.progress(position) >= previousCueProgress, "phrase animation is monotonic across native polls");
            previousCueProgress = cue.progress(position);
            for (int word = 0; word < 3; word++) {
                double progress = cue.words().get(word).progress(position);
                check(progress >= wordProgress[word], "word fill never restarts on a same-second observation");
                wordProgress[word] = progress;
            }
            var fixed = anchor.sample(world, "song", cue.startMs(), position,
                elapsed, 70 + elapsed, -elapsed, 0, 1, 0, 0, 38);
            check(fixed == pose, "moving and rotating camera cannot drag the live phrase");
            previous = position;
        }
        check(previous >= 13_570 && previous <= 14_830, "timeline advances continuously through repeated polls");
        close(pose.z(), -18, "configured anchor distance is used at phrase creation");
    }

    private static void stalledNativePosition() {
        var clock = new SkyLyricClock();
        var observation = snapshot(true, 5_000, 60_000, 0);
        long previous = at(clock, "song", observation, 0);
        for (int elapsed = 10; elapsed <= 8_400; elapsed += 10) {
            if (elapsed % 350 == 0) observation = snapshot(true, 5_000, 60_000, elapsed);
            long position = at(clock, "song", observation, elapsed);
            smooth(previous, position, 10, "unchanged raw position is not a backward seek even after several seconds");
            previous = position;
        }
        check(previous >= 12_140, "stale native position does not stall a playing render clock");
    }

    private static void gentleCorrection() {
        for (boolean behind : new boolean[] { false, true }) {
            var clock = new SkyLyricClock();
            var initial = snapshot(true, 10_000, 60_000, 0);
            at(clock, "song", initial, 0);
            int correctionAt = behind ? 100 : 3_100;
            long before = at(clock, "song", initial, correctionAt);
            // Both raw observations advance. Each observed interval is 1,100 ms from
            // the prediction, so this is clock drift, not an explicit backward seek.
            var correction = snapshot(true, behind ? 11_200 : 11_000, 60_000, correctionAt);
            long first = at(clock, "song", correction, correctionAt);
            check(first == before, "modest native correction does not jump at an unchanged monotonic timestamp");
            long previous = first;
            for (int elapsed = correctionAt + 20; elapsed <= correctionAt + 1_000; elapsed += 20) {
                long position = at(clock, "song", correction, elapsed);
                smooth(previous, position, 20, "gentle correction remains inside 0.85 to 1.15 playback speed");
                previous = position;
            }
            check(behind ? previous > first + 1_000 : previous < first + 1_000,
                "bounded correction moves toward the observed interval");
        }
    }

    private static void quantizationSeekBoundary() {
        for (boolean forward : new boolean[] { false, true }) {
            var clock = new SkyLyricClock();
            var initial = snapshot(true, 5_000, 60_000, 0);
            at(clock, "song", initial, 0);
            long before = at(clock, "song", initial, 6_000);
            // Observation age is 350 ms. The quantization interval is [8,500,9,500]
            // or [12,500,13,500], exactly 1,500 ms outside the 11,000 ms prediction.
            // Both observations increase the raw position, isolating the margin rule
            // from strict decreases that independently signal explicit backward seeks.
            var boundary = snapshot(true, forward ? 12_150 : 8_150, 60_000, 5_650);
            check(at(clock, "song", boundary, 6_000) == before,
                "exact 1,500 ms distance outside age-adjusted quantization interval cannot snap");
            var seek = snapshot(true, forward ? 12_501 : 8_499, 60_000, 6_000);
            long sought = at(clock, "song", seek, 6_000);
            check(sought == seek.progressMs(), "distance above 1,500 ms switches to the real seek timestamp");
            smooth(sought, at(clock, "song", seek, 6_100), 100, "interpolation resumes from a real seek");
        }
    }

    private static void pauseResumeAndPausedSeek() {
        var clock = new SkyLyricClock();
        var playing = snapshot(true, 10_000, 60_000, 0);
        at(clock, "song", playing, 0);
        long beforePause = at(clock, "song", playing, 330);
        var paused = snapshot(false, 10_000, 60_000, 340);
        long frozen = at(clock, "song", paused, 340);
        check(frozen >= beforePause && frozen <= beforePause + 12,
            "pause transition can finish the current monotonic frame but cannot rewind");
        for (int elapsed = 350; elapsed <= 1_500; elapsed += 10) {
            if (elapsed % 350 == 0) paused = snapshot(false, 10_000, 60_000, elapsed);
            check(at(clock, "song", paused, elapsed) == frozen, "unchanged paused observations keep an exact frozen position");
        }
        var resumed = snapshot(true, 10_000, 60_000, 2_000);
        long restart = at(clock, "song", resumed, 2_000);
        check(restart == frozen, "resume is continuous and excludes elapsed pause time");
        smooth(restart, at(clock, "song", resumed, 2_100), 100, "resume restarts monotonic frame advancement");
        var pausedSeek = snapshot(false, 25_000, 60_000, 2_200);
        long sought = at(clock, "song", pausedSeek, 2_200);
        check(sought == 25_000, "a real seek while pausing selects the new timestamp");
        check(at(clock, "song", pausedSeek, 6_000) == sought, "seeked paused playback remains frozen");
        var backwardSeek = snapshot(false, 3_000, 60_000, 6_100);
        check(at(clock, "song", backwardSeek, 6_100) == 3_000, "a real backward seek works while already paused");
        check(at(clock, "song", backwardSeek, 7_000) == 3_000, "a paused backward seek cannot interpolate");
    }

    private static void hiddenGapThenPause() {
        var clock = new SkyLyricClock();
        var playing = snapshot(true, 10_000, 60_000, 0);
        check(at(clock, "song", playing, 0) == 10_000, "hidden-gap fixture begins at native position");
        // No sample calls for ten seconds, as when rendering was hidden. The new
        // transport state must override the old playing extrapolation even though
        // the native whole-second position is unchanged.
        var paused = snapshot(false, 10_000, 60_000, 10_000);
        check(at(clock, "song", paused, 10_000) == 10_000,
            "pause after a ten-second hidden gap discards stale playing extrapolation");
        check(at(clock, "song", paused, 10_350) == 10_000,
            "hidden-gap pause freezes at the actual paused timestamp");
        var samePausedPosition = snapshot(false, 10_000, 60_000, 10_700);
        check(at(clock, "song", samePausedPosition, 10_700) == 10_000,
            "fresh unchanged paused poll cannot restore the hidden-gap extrapolation");
        var resumed = snapshot(true, 10_000, 60_000, 11_000);
        check(at(clock, "song", resumed, 11_000) == 10_000,
            "resume after hidden-gap pause remains continuous");
        check(at(clock, "song", resumed, 11_100) == 10_100,
            "resume advances only the new playing time after a hidden gap");
    }

    private static void smallExplicitSeeks() {
        var clock = new SkyLyricClock();
        var playing = snapshot(true, 10_000, 60_000, 0);
        at(clock, "song", playing, 0);
        check(at(clock, "song", playing, 350) == 10_350, "small-seek fixture has normal playing interpolation");
        var oneSecondBack = snapshot(true, 9_000, 60_000, 350);
        check(at(clock, "song", oneSecondBack, 350) == 9_000,
            "one-second raw decrease is a backward seek despite being inside the 1,500 ms margin");
        check(at(clock, "song", oneSecondBack, 450) == 9_100,
            "playing interpolation starts from the small backward seek timestamp");
        var tinyBackward = snapshot(true, 8_999, 60_000, 450);
        check(at(clock, "song", tinyBackward, 450) == 8_999,
            "strict raw decrease signals an explicit seek even inside the quantization interval");

        clock.reset();
        var paused = snapshot(false, 10_000, 60_000, 1_000);
        check(at(clock, "song", paused, 1_000) == 10_000, "paused small-seek fixture begins frozen");
        var smallForward = snapshot(false, 10_250, 60_000, 1_350);
        check(at(clock, "song", smallForward, 1_350) == 10_250,
            "a small forward seek while already paused applies immediately instead of trying rate correction");
        check(at(clock, "song", smallForward, 1_700) == 10_250,
            "small paused forward seek remains exactly frozen");
        var smallBackward = snapshot(false, 10_150, 60_000, 2_050);
        check(at(clock, "song", smallBackward, 2_050) == 10_150,
            "a small backward seek while already paused applies immediately");
        check(at(clock, "song", smallBackward, 2_400) == 10_150,
            "small paused backward seek remains exactly frozen");
        var tinyForward = snapshot(false, 10_151, 60_000, 2_750);
        check(at(clock, "song", tinyForward, 2_750) == 10_151,
            "any changed raw position while both observations are paused is an explicit seek");
    }

    private static void trackResetAndDuration() {
        var clock = new SkyLyricClock();
        var first = snapshot(true, 10_000, 60_000, 0);
        at(clock, "first", first, 0);
        at(clock, "first", first, 500);
        var second = snapshot(true, 500, 60_000, 500);
        check(at(clock, "second", second, 500) == 500, "track change starts its own timeline immediately");
        check(at(clock, new String("second"), second, 750) == 750, "equal track identity preserves interpolation");
        clock.reset();
        var reset = snapshot(true, 2_000, 60_000, 800);
        check(at(clock, "second", reset, 800) == 2_000, "disable/reset removes previous track timing and correction");
        clock.reset();
        var ending = snapshot(true, 900, 1_000, 1_000);
        check(at(clock, "ending", ending, 1_000) == 900, "known-duration initial timestamp retained");
        check(at(clock, "ending", ending, 1_150) == 1_000, "duration end clamps interpolation");
        check(at(clock, "ending", ending, 10_000) == 1_000, "duration clamp remains stable after track end");
        clock.reset();
        var unknownEnd = snapshot(true, 900, 0, 10_000);
        check(at(clock, "unknown", unknownEnd, 10_000) == 900, "unknown duration retains progress");
        check(at(clock, "unknown", unknownEnd, 10_250) == 1_150, "unknown duration is not a zero-length track");
    }

    private static void unchangedSnapshotIgnoresWallClock() {
        var normal = new SkyLyricClock();
        var jumpingWall = new SkyLyricClock();
        var observation = snapshot(true, 4_000, 60_000, -500);
        long first = at(normal, "song", observation, 0);
        check(first == 4_500, "new snapshot incorporates its observation age once");
        check(at(jumpingWall, "song", observation, 0) == first, "comparison clocks start identically");
        for (int elapsed = 20; elapsed <= 2_000; elapsed += 20) {
            long expected = at(normal, "song", observation, elapsed);
            long clockJump = elapsed % 40 == 0 ? 31_536_000_000L : -31_536_000_000L;
            long actual = jumpingWall.sample("song", observation, nanos(elapsed), WALL + elapsed + clockJump);
            check(actual == expected, "wall-clock jumps cannot change an already accepted snapshot");
        }
        var sparse = new SkyLyricClock();
        at(sparse, "song", observation, 0);
        check(at(sparse, "song", observation, 2_000) == at(normal, "song", observation, 2_000),
            "unchanged playback advances by elapsed monotonic time, not the number of render frames");
    }

    private static void phraseAnchorSurvivesSeek() {
        var clock = new SkyLyricClock();
        var anchor = new SkyLyricAnchor();
        var lyrics = TimedLyrics.parse("[00:10]One small orbit\n[00:20]Next lantern");
        Object world = new Object();
        var initial = snapshot(true, 15_500, 60_000, 0);
        long playback = at(clock, "song", initial, 0);
        var cue = lyrics.wordCue(playback, 60_000);
        var first = anchor.sample(world, "song", cue.startMs(), playback, 10, 70, 20, 0, 0, 0, 1, 38);
        var rewind = snapshot(true, 11_000, 60_000, 350);
        playback = at(clock, "song", rewind, 350);
        check(playback == 11_000, "combined fixture performs a real playback rewind");
        var rewoundCue = lyrics.wordCue(playback, 60_000);
        check(rewoundCue.id() == cue.id(), "real seek stays inside the same lyric phrase");
        var fixed = anchor.sample(world, "song", rewoundCue.startMs(), playback,
            1_000, 120, -800, 0, 1, 0, 0, 64);
        check(fixed == first, "same phrase keeps its pose across playback rewind, camera motion and later distance changes");
        var next = anchor.sample(world, "song", 20_000, 20_000, 2, 70, 4, 0, 0, 0, 1, 64);
        check(next != first, "new phrase still creates a fresh pose");
        close(next.z(), -60, "new phrase uses its current placement distance");
        anchor.reset();
        check(anchor.sample(world, "song", 10_000, 11_000, 10, 70, 20, 0, 0, 0, 1, 38) != first,
            "disable/reset releases the locked phrase pose");
    }

    private static MediaPlaybackSnapshot snapshot(boolean playing, long progress, long duration, long updatedMs) {
        return new MediaPlaybackSnapshot(true, playing, false, false, false, false, false,
            "Synthetic media", "offline-fixture", "Original test phrase", "Test artist", "", "", "",
            progress, duration, "", Instant.ofEpochMilli(WALL + updatedMs));
    }

    private static long at(SkyLyricClock clock, Object track, MediaPlaybackSnapshot snapshot, long elapsedMs) {
        return clock.sample(track, snapshot, nanos(elapsedMs), WALL + elapsedMs);
    }

    private static long nanos(long elapsedMs) { return NANOS + elapsedMs * 1_000_000L; }

    private static void smooth(long previous, long current, long elapsedMs, String message) {
        long delta = current - previous;
        check(delta >= elapsedMs * .85 - 1 && delta <= elapsedMs * 1.15 + 1,
            message + ": delta=" + delta + ", elapsed=" + elapsedMs);
    }

    private static void close(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
