package dev.c4lam.celeste.render.model;

import java.util.List;
import java.util.random.RandomGenerator;

/** Pure controller fixtures: a kill event starts playback, frames only advance it. */
public final class ModelAnimationsCheck {
    private static int checks;
    private ModelAnimationsCheck() {}

    public static void main(String[] args) {
        stationaryKillStartsOnce();
        movementCancelsAndDoesNotQueue();
        endAndSafetyCapDoNotLoop();
        namesAndBindFallback();
        disabledAndExplicitReset();
        invalidTimeAndClockRewind();
        newKillMayStartANewClip();
        runningManIsADance();
        idleFallbackWhenWalkingClipIsAbsent();
        tickEventPrecedesInterpolatedFrame();
        rateChangesPreservePhase();
        transitionsAreBounded();
        System.out.println("PASS model animation controller: " + checks + " checks; stationary kill, cancellation, expiry, fallback and clock guards");
    }

    private static void stationaryKillStartsOnce() {
        ModelAnimations controller = standard();
        CountingRandom random = new CountingRandom(0);
        playback(controller.frame(9, true, true), 0, 9, true, "idle before event");
        controller.celebrate(10, true, random);
        equal(random.calls, 1, "single stationary kill chooses once");
        equal(random.bound, 2, "only celebration choices supplied to RNG");
        playback(controller.frame(10, true, true), 2, 0, false, "kill starts at clip zero");
        playback(controller.frame(10.25, true, true), 2, .25, false, "next frame advances from event time");
        playback(controller.frame(11.5, true, true), 2, 1.5, false, "clip progresses without restart");
        playback(controller.frame(11.5, true, true), 2, 1.5, false, "same timestamp is stable");
        equal(random.calls, 1, "frame calls never reroll the clip");
    }

    private static void movementCancelsAndDoesNotQueue() {
        ModelAnimations controller = standard();
        CountingRandom random = new CountingRandom(0);
        controller.celebrate(3, false, random);
        equal(random.calls, 0, "moving kill does not consume random or queue celebration");
        playback(controller.frame(3, false, true), 1, 3, true, "moving chooses walk");
        playback(controller.frame(4, true, true), 0, 4, true, "stopping later does not replay moving kill");
        controller.celebrate(5, true, random);
        playback(controller.frame(5.5, true, true), 2, .5, false, "stationary event active");
        playback(controller.frame(6, false, true), 1, 6, true, "movement immediately cancels active clip");
        playback(controller.frame(6.25, true, true), 0, 6.25, true, "standing again remains idle");
        equal(random.calls, 1, "no delayed random selection after movement");
    }

    private static void endAndSafetyCapDoNotLoop() {
        ModelAnimations controller = standard();
        CountingRandom random = new CountingRandom(0);
        controller.celebrate(10, true, random);
        playback(controller.frame(12.999, true, true), 2, 2.999, false, "short clip plays before exact end");
        playback(controller.frame(13, true, true), 0, 13, true, "exact clip end restores idle");
        playback(controller.frame(14, true, true), 0, 14, true, "later frame does not loop celebration");
        equal(random.calls, 1, "expiry does not choose another random clip");
        ModelAnimations longClip = new ModelAnimations(model(clip("Idle", 2), clip("Long dance", 45)));
        longClip.celebrate(5, true, new CountingRandom(0));
        playback(longClip.frame(24.99, true, true), 1, 19.99, false, "long clip plays within safety window");
        playback(longClip.frame(25, true, true), 0, 25, true, "20-second safety cap ends long clip");
        playback(longClip.frame(1000, true, true), 0, 1000, true, "large elapsed jump remains normal idle");
    }

    private static void namesAndBindFallback() {
        ModelAnimations unknown = new ModelAnimations(model(clip("Mystery action", 2)));
        playback(unknown.frame(2, true, true), -1, 2, true, "unknown clip name does not auto-play while stationary");
        playback(unknown.frame(3, false, true), -1, 3, true, "unknown name does not substitute for walk");
        ModelAnimations empty = new ModelAnimations(model());
        CountingRandom none = new CountingRandom(0);
        empty.celebrate(0, true, none);
        playback(empty.frame(1, true, true), -1, 1, true, "no clips means bind pose");
        equal(none.calls, 0, "empty candidate list does not ask RNG for zero bound");
        ModelAnimations names = new ModelAnimations(model(
                clip("IDLE normal", 2), clip("Standing variation", 2), clip("RUN cycle", 1), clip("walk alternate", 1),
                clip("Death", 1), clip("die", 1), clip("Fall", 1), clip("Jump", 1), clip("T-Pose", 1), clip("Bind Pose", 1),
                clip("Zero dance", 0), clip("VICTORY", 4)));
        CountingRandom candidate = new CountingRandom(0);
        playback(names.frame(0, true, true), 0, 0, true, "first case-insensitive idle clip wins");
        playback(names.frame(1, false, true), 2, 1, true, "first case-insensitive run/walk clip wins");
        names.celebrate(2, true, candidate);
        equal(candidate.bound, 1, "death/movement/bind/zero-duration clips excluded from celebrations");
        playback(names.frame(2, true, true), 11, 0, false, "eligible victory clip selected");
        ModelAnimations noCelebration = new ModelAnimations(model(clip("Idle", 2), clip("Walk", 2), clip("Death", 3)));
        noCelebration.celebrate(5, true, none);
        playback(noCelebration.frame(5, true, true), 0, 5, true, "no valid celebration leaves idle unchanged");
        equal(none.calls, 0, "no celebration candidates means no RNG call");
    }

    private static void disabledAndExplicitReset() {
        ModelAnimations controller = standard();
        controller.celebrate(10, true, new CountingRandom(0));
        playback(controller.frame(10.5, true, false), 0, 10.5, true, "disabled celebration setting cancels clip immediately");
        playback(controller.frame(11, true, true), 0, 11, true, "re-enabling setting does not resurrect canceled clip");
        controller.celebrate(12, true, new CountingRandom(0));
        controller.clear();
        playback(controller.frame(12.5, true, true), 0, 12.5, true, "explicit clear restores baseline");
        controller.clear();
        playback(controller.frame(13, false, false), 1, 13, true, "clear is idempotent and disabling celebrations does not disable walk");
    }

    private static void invalidTimeAndClockRewind() {
        ModelAnimations controller = standard();
        CountingRandom random = new CountingRandom(0);
        controller.celebrate(Double.NaN, true, random);
        controller.celebrate(Double.POSITIVE_INFINITY, true, random);
        equal(random.calls, 0, "invalid event timestamps never select a clip");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            controller.celebrate(10, true, random);
            playback(controller.frame(invalid, true, true), -1, 0, false, "invalid frame timestamp produces safe bind pose");
            playback(controller.frame(11, true, true), 0, 11, true, "invalid timestamp clears active clip");
        }
        controller.celebrate(10, true, random);
        playback(controller.frame(9, true, true), 0, 9, true, "rewind before event start cancels clip");
        playback(controller.frame(10.5, true, true), 0, 10.5, true, "reaching old start time cannot resurrect clip");
        controller.celebrate(20, true, random);
        playback(controller.frame(22, true, true), 2, 2, false, "clip active before in-range rewind");
        playback(controller.frame(21, true, true), 0, 21, true, "rewind relative to previous frame cancels even after event start");
        playback(controller.frame(22.5, true, true), 0, 22.5, true, "clock catches up without replaying canceled animation");
    }

    private static void newKillMayStartANewClip() {
        ModelAnimations controller = standard();
        CountingRandom first = new CountingRandom(0), second = new CountingRandom(1);
        controller.celebrate(10, true, first);
        playback(controller.frame(11, true, true), 2, 1, false, "first kill clip active");
        controller.celebrate(11, true, second);
        playback(controller.frame(11, true, true), 3, 0, false, "separate confirmed kill may start a newly selected clip");
        playback(controller.frame(12, true, true), 3, 1, false, "new event has its own time origin");
        equal(first.calls + second.calls, 2, "exactly one RNG selection per eligible kill event");
    }

    private static void runningManIsADance() {
        ModelAnimations controller = new ModelAnimations(model(clip("Idle", 2),
            clip("Dance Running Man", 4), clip("Walk", 1)));
        playback(controller.frame(0, false, true), 2, 0, true, "running-man dance must not replace locomotion");
        CountingRandom random = new CountingRandom(0);
        controller.celebrate(1, true, random);
        equal(random.bound, 1, "running-man dance stays in the celebration pool");
        playback(controller.frame(1.5, true, true), 1, .5, false, "running-man dance plays after kill");
    }

    private static ModelAnimations standard() {
        return new ModelAnimations(model(clip("Idle", 2), clip("Walk", 1), clip("Victory", 3), clip("Dance", 5)));
    }
    private static void tickEventPrecedesInterpolatedFrame() {
        ModelAnimations controller = standard();
        controller.frame(9.94, true, true);
        controller.celebrate(10, true, new CountingRandom(0));
        playback(controller.frame(9.95, true, true), 2, 0, false, "one-tick-old interpolated age does not cancel newly observed kill");
        playback(controller.frame(9.99, true, true), 2, 0, false, "pre-event frames safely hold first dance frame");
        playback(controller.frame(10.2, true, true), 2, .2, false, "dance then advances normally");
        controller.frame(10.21, false, true);
        playback(controller.frame(10.22, true, true), 0, 10.22, true, "moving out of celebration does not resume it");
    }
    private static void rateChangesPreservePhase() {
        ModelAnimations controller = standard();
        double first = controller.frame(10, false, true, .5).seconds();
        double slow = controller.frame(10.1, false, true, .5).seconds();
        double normal = controller.frame(10.2, false, true, 1).seconds();
        double reverse = controller.frame(10.3, false, true, -1).seconds();
        check(Math.abs(slow - first - .05) < 1e-8, "half-speed phase advances smoothly");
        check(Math.abs(normal - slow - .1) < 1e-8, "crossing exact rate one does not reset phase");
        check(Math.abs(reverse - normal + .1) < 1e-8, "backward walking reverses from current phase");
        check(controller.frame(10.4, false, true, 0).clip() == 0, "airborne/no walk rate uses idle instead of frozen walk");
        check(controller.frame(10.5, false, true, Double.NaN).clip() == 0, "invalid walk rate is safe");
    }
    private static void transitionsAreBounded() {
        ModelAnimations controller = standard();
        controller.frame(0, true, true);
        var begin = controller.frame(.05, false, true);
        check(begin.clip() == 1 && begin.previousClip() == 0 && begin.blend() == 0, "walking begins from standing pose");
        var middle = controller.frame(.14, false, true);
        check(Math.abs(middle.blend() - .5) < 1e-8, "halfway transition is smoothly half blended");
        check(controller.frame(.24, false, true).blend() == 1, "transition completes without persistent extra pose sampling");
        controller.celebrate(.3, true, new CountingRandom(0));
        var dance = controller.frame(.3, true, true);
        check(dance.previousClip() == 1 && dance.clip() == 2 && dance.blend() == 0, "celebration crossfades from last visible pose");
    }
    private static void idleFallbackWhenWalkingClipIsAbsent() {
        ModelAnimations controller = new ModelAnimations(model(clip("Stand", 2), clip("Breakdance", 4)));
        playback(controller.frame(3, false, true), 0, 3, true, "missing walking clip uses idle instead of a T-pose");
        controller.celebrate(4, true, new CountingRandom(0));
        playback(controller.frame(4.5, false, true), 0, 4.5, true, "moving cancels dance into idle fallback");
    }
    private static GlbModel.Animation clip(String name, float duration) { return new GlbModel.Animation(name, duration, List.of()); }
    private static GlbModel model(GlbModel.Animation... animations) {
        return new GlbModel(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(animations), new int[0], new int[0], new int[0]);
    }
    private static void playback(ModelAnimations.Playback actual, int clip, double seconds, boolean loop, String message) {
        check(actual.clip() == clip && Math.abs(actual.seconds() - seconds) < 1e-8 && actual.loop() == loop,
                message + ": expected=" + new ModelAnimations.Playback(clip, seconds, loop) + " actual=" + actual);
    }
    private static void equal(int actual, int expected, String message) { check(actual == expected, message + ": " + actual + " != " + expected); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static final class CountingRandom implements RandomGenerator {
        private final int choice;
        private int calls;
        private int bound;
        CountingRandom(int choice) { this.choice = choice; }
        @Override public int nextInt(int upper) {
            check(upper > 0 && choice >= 0 && choice < upper, "bounded deterministic random selection");
            calls++; bound = upper; return choice;
        }
        @Override public long nextLong() { throw new AssertionError("Unexpected unbounded random selection"); }
    }
}
