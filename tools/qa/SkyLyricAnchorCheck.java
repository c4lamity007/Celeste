package dev.c4lam.celeste.module.impl.visual.expansion;

/** Offline behavioral checks of the actual world-anchor implementation. */
public final class SkyLyricAnchorCheck {
    private static int checks;
    public static void main(String[] args) {
        var anchor = new SkyLyricAnchor();
        Object world = new Object();
        var first = anchor.sample(world, "song", 1000, 1100, 10, 70, 20, 0, 0, 0, 1);
        close(first.x(), 10); close(first.y(), 70); close(first.z(), 2);
        for (int i = 0; i < 1000; i++) {
            var fixed = anchor.sample(world, "song", 1000, 1100 + i, i, 100, -i, 0, 1, 0, 0);
            check(first == fixed, "moving/rotating camera cannot drag or rotate line");
        }
        var next = anchor.sample(world, "song", 2200, 2200, 5, 64, 6, 0, 1, 0, 0);
        close(next.z(), 24); close(next.x(), 5); close(next.y(), 64);
        check(next != first, "new cue uses current view even with identical words");
        var paused = anchor.sample(world, "song", 2200, 2200, 80, 64, 80, 0, 0, 0, 1);
        check(paused == next, "pause does not respawn anchor");
        var track = anchor.sample(world, "new song", 2200, 2200, 0, 64, 0, 0, 0, 0, 1);
        check(track != next, "same timestamp in different song respawns");
        var dimension = anchor.sample(new Object(), "new song", 2200, 2300, 0, 64, 0, 0, 0, 0, 1);
        check(dimension != track, "dimension/world identity reset");
        anchor.reset();
        var replay = anchor.sample(world, "song", 1000, 1900, 0, 0, 0, 0, 0, 0, 1);
        var seek = anchor.sample(world, "song", 1000, 1050, 10, 0, 0, 0, 0, 0, 1);
        check(replay == seek, "backward seek within the same phrase must not respawn it");
        var jitter = anchor.sample(world, "song", 1000, 1000, 0, 0, 0, 0, 0, 0, 1);
        check(jitter == seek, "small media-poll correction does not drift anchor");
        anchor.reset();
        var near = anchor.sample(world, "song", 1000, 1000, 0, 64, 0, 0, 0, 0, 1, 5);
        close(near.z(), -5);
        check(near == anchor.sample(world, "song", 1000, 1500, 90, 100, 90, 0, 1, 0, 0, 40),
            "distance setting is latched until a new phrase");
        var far = anchor.sample(world, "song", 2000, 2000, 0, 64, 0, 0, 0, 0, 1, 40);
        close(far.z(), -40);
        for (double requested : new double[] {2, 5, 18, 40, 64, -1, 1000, Double.NaN, Double.POSITIVE_INFINITY}) {
            anchor.reset();
            var p = anchor.sample(world, "song", 0, 0, 10, 20, 30, 0, 0, 0, 1, requested);
            double expected = Double.isFinite(requested) ? Math.clamp(requested, 2, 64) : 18;
            close(p.x(), 10); close(p.y(), 20); close(p.z(), 30 - expected);
        }
        for (int degrees = -180; degrees <= 180; degrees++) {
            anchor.reset();
            double half = Math.toRadians(degrees) / 2;
            var p = anchor.sample(world, "song", 0, 0, 500, 70, -300,
                (float)Math.sin(half), 0, 0, (float)Math.cos(half));
            double distance = Math.sqrt(Math.pow(p.x() - 500, 2) + Math.pow(p.y() - 70, 2) + Math.pow(p.z() + 300, 2));
            close(distance, 18);
            check(p.y() >= 52 - 1e-6 && p.y() <= 88 + 1e-6, "vertical look uses bounded distance");
        }
        anchor.reset();
        var unit = anchor.sample(world, "song", 0, 0, 0, 0, 0, 0, 0, 0, 2);
        close(unit.z(), -18); close(unit.qw(), 1);
        check(anchor.sample(world, "song", 0, 0, 0, 0, 0, Float.NaN, 0, 0, 1) == null, "invalid rotation rejected");
        check(anchor.sample(world, "song", 0, 0, Double.NaN, 0, 0, 0, 0, 0, 1) == null, "invalid position rejected");
        check(anchor.sample(null, "song", 0, 0, 0, 0, 0, 0, 0, 0, 1) == null, "disconnect rejected");
        anchor.reset();
        check(anchor.sample(world, "song", 0, 0, 0, 0, 0, 0, 0, 0, 1) != unit, "disable/blank resets pose");
        obstructionChecks();
        System.out.println("SkyLyricAnchorCheck PASS: " + checks + " assertions");
    }
    private static void obstructionChecks() {
        var anchor = new SkyLyricAnchor();
        Object world = new Object();
        int[] rays = {0};
        double[] obstruction = {7};
        SkyLyricAnchor.Raycast raycast = (x, y, z, dx, dy, dz, limit) -> {
            rays[0]++;
            close(x, 10); close(y, 70); close(z, 20);
            close(dx, 0); close(dy, 0); close(dz, -1); close(limit, 18);
            return obstruction[0];
        };
        var blocked = anchor.sample(world, "song", 0, 0, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        close(blocked.x(), 10); close(blocked.y(), 70); close(blocked.z(), 13.2);
        obstruction[0] = .1;
        for (int i = 0; i < 1000; i++) {
            var same = anchor.sample(world, "song", 0, i, i, 80, i, 0, 1, 0, 0, 64, raycast);
            check(same == blocked, "camera, blocks and distance changes cannot re-anchor an existing cue");
        }
        check(rays[0] == 1, "one raycast per phrase, not per frame");
        var hidden = anchor.sample(world, "song", 2000, 2000, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        check(hidden == null, "wall against the camera hides cue instead of placing it through the wall");
        obstruction[0] = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 1000; i++) {
            check(anchor.sample(world, "song", 2000, 2000 + i, i, 80, i, 0, 1, 0, 0, 64, raycast) == null,
                "a hidden cue remains latched when the camera turns to open space");
        }
        check(rays[0] == 2, "hidden cues do not repeatedly raycast");
        var clear = anchor.sample(world, "song", 4000, 4000, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        close(clear.z(), 2);
        check(rays[0] == 3, "next phrase tries the now-unobstructed direction");
        anchor.sample(world, "new track", 4000, 4000, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        check(rays[0] == 4, "new track gets a fresh raycast");
        anchor.sample(new Object(), "new track", 4000, 4000, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        check(rays[0] == 5, "world change gets a fresh raycast");
        anchor.reset();
        anchor.sample(world, "new track", 4000, 4000, 10, 70, 20, 0, 0, 0, 1, 18, raycast);
        check(rays[0] == 6, "disable/reset clears the raycast latch");
        for (double hit : new double[] {0, .01, .69, -.1, Double.NaN, Double.NEGATIVE_INFINITY}) {
            anchor.reset();
            check(anchor.sample(world, "song", 0, 0, 0, 0, 0, 0, 0, 0, 1, 18,
                (x, y, z, dx, dy, dz, max) -> hit) == null, "invalid or insufficient clear space is hidden");
        }
        for (double hit : new double[] {.71, 1, 2, 8, 18, 25, Double.POSITIVE_INFINITY}) {
            anchor.reset();
            var p = anchor.sample(world, "song", 0, 0, 0, 0, 0, 0, 0, 0, 1, 18,
                (x, y, z, dx, dy, dz, max) -> hit);
            close(-p.z(), Math.min(18, hit - .2));
            check(-p.z() <= 18 && -p.z() < hit, "closest blocking surface bounds maximum placement");
        }
        // Intersect every ray with a synthetic spherical wall; verify pitched and
        // diagonal rays remain collinear and back off along the ray, not world Z.
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                anchor.reset();
                double a = Math.toRadians(pitch) / 2, b = Math.toRadians(yaw) / 2;
                double[] direction = new double[3];
                var p = anchor.sample(world, "song", 0, 0, 10, 70, 20,
                    (float)(Math.sin(a) * Math.cos(b)), (float)(Math.cos(a) * Math.sin(b)),
                    (float)(-Math.sin(a) * Math.sin(b)), (float)(Math.cos(a) * Math.cos(b)), 64,
                    (x, y, z, dx, dy, dz, max) -> {
                        close(dx * dx + dy * dy + dz * dz, 1); close(max, 64);
                        direction[0] = dx; direction[1] = dy; direction[2] = dz;
                        return 4;
                    });
                close(p.x(), 10 + direction[0] * 3.8);
                close(p.y(), 70 + direction[1] * 3.8);
                close(p.z(), 20 + direction[2] * 3.8);
            }
        }
    }
    private static void close(double actual, double expected) { check(Math.abs(actual - expected) < 1e-6, "coordinate/distance " + actual + " != " + expected); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
