package dev.c4lam.celeste.render.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Frame-time playback. A celebration never moves the real player or loops forever. */
public final class ModelAnimations {
    private final GlbModel model;
    private final int idle;
    private final int walk;
    private final List<Integer> celebrations;
    private int celebration = -1;
    private double started;
    private double previousFrame = Double.NaN;
    private double walkTime;
    private Playback previousPlayback;
    private Playback transitionSource;
    private double transitionStarted;

    public ModelAnimations(GlbModel model) {
        this.model = model;
        int standing = -1, moving = -1;
        List<Integer> choices = new ArrayList<>();
        for (int i = 0; i < model.animations().size(); i++) {
            var clip = model.animations().get(i);
            if (clip.duration() <= 0) continue;
            String name = clip.name().toLowerCase(Locale.ROOT);
            if (name.contains("idle") || name.contains("stand")) { if (standing < 0) standing = i; }
            else if (name.contains("dance") || name.contains("celebration")) choices.add(i);
            else if (name.contains("walk") || name.contains("run")) { if (moving < 0) moving = i; }
            else if (!name.contains("death") && !name.contains("die") && !name.contains("fall")
                && !name.contains("jump") && !name.contains("t-pose") && !name.contains("bind")) choices.add(i);
        }
        idle = standing;
        walk = moving;
        celebrations = List.copyOf(choices);
    }

    public void celebrate(double now, RandomGenerator random) {
        if (!Double.isFinite(now) || celebrations.isEmpty()) return;
        celebration = celebrations.get(random.nextInt(celebrations.size()));
        started = now;
    }

    /** Retain the existing caller signature; movement no longer gates kill playback. */
    public void celebrate(double now, boolean stationary, RandomGenerator random) {
        celebrate(now, random);
    }

    public Playback frame(double now, boolean stationary, boolean celebrationsEnabled) {
        return frame(now, stationary, celebrationsEnabled, 1, true);
    }

    public Playback frame(double now, boolean stationary, boolean celebrationsEnabled, double walkRate) {
        return frame(now, stationary, celebrationsEnabled, walkRate, false);
    }

    private Playback frame(double now, boolean stationary, boolean celebrationsEnabled, double walkRate, boolean absoluteTime) {
        if (!Double.isFinite(now)) { resetPlayback(); return new Playback(-1, 0, false); }
        // Entity render age is interpolated, up to one tick behind the tick which observed the kill.
        // That first frame is not a clock rewind and must not discard the new celebration.
        if (!celebrationsEnabled || now < started - .051 || now < previousFrame - .001) clear();
        double elapsed = Double.isFinite(previousFrame) ? Math.max(0, Math.min(.25, now - previousFrame)) : 0;
        if (now < previousFrame - .001) { previousPlayback = null; transitionSource = null; }
        double rate = Double.isFinite(walkRate) ? Math.max(-1.8, Math.min(1.8, walkRate)) : 0;
        if (!Double.isFinite(previousFrame)) walkTime = now;
        else walkTime += elapsed * rate;
        previousFrame = now;
        if (celebration >= 0) {
            double time = Math.max(0, now - started);
            if (time < Math.min(20, model.animations().get(celebration).duration()))
                return blend(now, new Playback(celebration, time, false));
            clear();
        }
        boolean walking = !stationary && walk >= 0 && Math.abs(rate) > .001;
        // The original three-argument frame API retains its absolute-time playback contract.
        double seconds = absoluteTime ? now : walkTime;
        return blend(now, new Playback(walking ? walk : idle, walking ? seconds : now, true));
    }

    public void clear() { celebration = -1; }

    private void resetPlayback() {
        clear(); previousFrame = Double.NaN; previousPlayback = null; transitionSource = null;
    }

    private Playback blend(double now, Playback target) {
        if (previousPlayback != null && previousPlayback.clip != target.clip) {
            transitionSource = previousPlayback;
            transitionStarted = now;
        }
        Playback output = target;
        if (transitionSource != null) {
            double amount = Math.max(0, Math.min(1, (now - transitionStarted) / .18));
            if (amount < 1) output = new Playback(target.clip, target.seconds, target.loop,
                transitionSource.clip, transitionSource.seconds + Math.max(0, now - transitionStarted),
                transitionSource.loop, amount * amount * (3 - 2 * amount));
            else transitionSource = null;
        }
        previousPlayback = target;
        return output;
    }

    public record Playback(int clip, double seconds, boolean loop, int previousClip, double previousSeconds,
                           boolean previousLoop, double blend) {
        public Playback(int clip, double seconds, boolean loop) { this(clip, seconds, loop, -1, 0, false, 1); }
    }
}
