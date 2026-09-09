package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.Objects;

/** One client-local pose per lyric cue. Camera motion never drags an existing line. */
public final class SkyLyricAnchor {
    public static final double DISTANCE = 18;
    private static final double BLOCK_CLEARANCE = .2;
    private static final double MIN_CLEAR_DISTANCE = .5;
    /** First block hit along this unit ray; positive infinity means unobstructed. */
    @FunctionalInterface
    public interface Raycast {
        double firstHitDistance(double x, double y, double z, double dx, double dy, double dz, double limit);
    }
    public record Pose(double x, double y, double z, float qx, float qy, float qz, float qw) { }
    private Object world, track;
    private long cueStart = Long.MIN_VALUE;
    private Pose pose;

    public Pose sample(Object nextWorld, Object nextTrack, long startMs, long playbackMs,
                       double eyeX, double eyeY, double eyeZ, float qx, float qy, float qz, float qw) {
        return sample(nextWorld, nextTrack, startMs, playbackMs, eyeX, eyeY, eyeZ, qx, qy, qz, qw, DISTANCE);
    }

    public Pose sample(Object nextWorld, Object nextTrack, long startMs, long playbackMs,
                       double eyeX, double eyeY, double eyeZ, float qx, float qy, float qz, float qw, double distance) {
        return sample(nextWorld, nextTrack, startMs, playbackMs, eyeX, eyeY, eyeZ, qx, qy, qz, qw, distance, null);
    }

    public Pose sample(Object nextWorld, Object nextTrack, long startMs, long playbackMs,
                       double eyeX, double eyeY, double eyeZ, float qx, float qy, float qz, float qw,
                       double distance, Raycast raycast) {
        double norm = (double)qx * qx + (double)qy * qy + (double)qz * qz + (double)qw * qw;
        if (nextWorld == null || nextTrack == null || startMs < 0 || playbackMs < startMs
            || !Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
            || !Double.isFinite(norm) || norm < 1e-12) {
            reset();
            return null;
        }
        if (world != nextWorld || !Objects.equals(track, nextTrack) || startMs != cueStart) {
            distance = Double.isFinite(distance) ? Math.clamp(distance, 2, 64) : DISTANCE;
            double inverse = 1 / Math.sqrt(norm);
            double x = qx * inverse, y = qy * inverse, z = qz * inverse, w = qw * inverse;
            // Minecraft's camera-local forward is -Z. Keep its billboard rotation too.
            double forwardX = -2 * (x * z + y * w);
            double forwardY = 2 * (x * w - y * z);
            double forwardZ = 2 * (x * x + y * y) - 1;
            if (raycast != null) {
                double hit = raycast.firstHitDistance(eyeX, eyeY, eyeZ, forwardX, forwardY, forwardZ, distance);
                distance = Math.min(distance, hit - BLOCK_CLEARANCE);
            }
            // Never push a blocked ray out to the configured minimum (which can be
            // behind a nearby wall). Latch a hidden cue too, instead of chasing a gap
            // every frame as the player looks around. The next phrase tries again.
            pose = Double.isFinite(distance) && distance >= MIN_CLEAR_DISTANCE
                ? new Pose(eyeX + forwardX * distance, eyeY + forwardY * distance,
                    eyeZ + forwardZ * distance, (float)x, (float)y, (float)z, (float)w)
                : null;
            world = nextWorld;
            track = nextTrack;
            cueStart = startMs;
        }
        return pose;
    }

    public void reset() {
        world = track = null;
        cueStart = Long.MIN_VALUE;
        pose = null;
    }
}
