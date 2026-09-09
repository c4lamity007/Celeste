package dev.c4lam.celeste.render.model;

public final class PlayerModelMotionCheck {
    private static int checks;
    private PlayerModelMotionCheck() {}

    public static void main(String[] args) {
        check(PlayerModelMotion.stationary(true, false, false, 0, 0, 0), "ordinary grounded still player");
        // Ground gravity (-.0784 velocity Y) is intentionally not an argument: displacement is zero.
        check(PlayerModelMotion.stationary(true, false, false, .001, -.001, 0), "small grounded correction remains still");
        check(!PlayerModelMotion.stationary(true, false, false, .05, 0, 0), "horizontal movement cancels dance");
        check(!PlayerModelMotion.stationary(false, false, false, 0, 0, 0), "jump apex is not stationary");
        check(!PlayerModelMotion.stationary(true, true, false, 0, 0, 0), "riding cannot celebrate");
        check(!PlayerModelMotion.stationary(true, false, true, 0, 0, 0), "gliding cannot celebrate");
        check(!PlayerModelMotion.stationary(true, false, false, 0, .1, 0), "vertical step prevents celebration");
        check(!PlayerModelMotion.stationary(true, false, false, Double.NaN, 0, 0), "invalid position is safe");
        close(rate(0, .21585, 0), 1, "forward walk speed");
        close(rate(0, -.21585, 0), -1, "backward walk reverses stride");
        close(rate(-.21585, 0, 90), 1, "turning body turns forward");
        close(rate(.21585, 0, 90), -1, "turned backwards stride");
        close(rate(.21585, 0, 0), 1, "strafing still animates");
        check(rate(0, .28, 0) > 1, "sprint speeds up stride");
        close(rate(0, .8, 0), 1.8, "bounded high-speed animation");
        close(rate(0, 3, 0), 0, "teleport does not sprint for one frame");
        close(rate(0, 0, 0), 0, "standing has no walk");
        close(PlayerModelMotion.walkRate(false, false, false, .2, 0, 0), 0, "airborne does not walk");
        close(PlayerModelMotion.walkRate(true, true, false, .2, 0, 0), 0, "riding does not walk");
        close(rate(0, Double.NaN, 0), 0, "invalid displacement does not walk");
        ModelKillTracker<Target> tracker = new ModelKillTracker<>();
        Target target = new Target();
        tracker.record(target, 10);
        check(!poll(tracker, 11), "attacked but alive is not a kill");
        target.removed = true;
        check(!poll(tracker, 12), "despawn alone is not a kill");
        target.dead = true;
        check(poll(tracker, 13), "confirmed death survives entity removal between ticks");
        check(!poll(tracker, 14), "confirmed kill fires once");
        tracker.record(target, 20); tracker.clear();
        check(!poll(tracker, 21), "disable/reset clears pending kill");
        tracker.record(target, 30); target.sameWorld = false;
        check(!poll(tracker, 31), "dimension/disconnect clears pending kill");
        target.sameWorld = true;
        check(!poll(tracker, 32), "old-world kill cannot replay");
        tracker.record(target, 40);
        check(!poll(tracker, 141), "old damage expires after five seconds");
        tracker.record(target, 200);
        check(!poll(tracker, 0), "respawn age reset cannot replay kill");
        Target other = new Target();
        tracker.record(target, 10); tracker.record(other, 11);
        check(!poll(tracker, 12), "another target replaces the previous reference, not an entity id lookup");
        other.dead = true;
        check(poll(tracker, 13), "replacement target death works");
        System.out.println("PASS player-model movement and kill tracking: " + checks + " checks");
    }

    private static double rate(double dx, double dz, float yaw) { return PlayerModelMotion.walkRate(true, false, false, dx, dz, yaw); }
    private static boolean poll(ModelKillTracker<Target> tracker, int tick) {
        return tracker.confirmedDeath(tick, target -> target.sameWorld, target -> target.dead);
    }
    private static void close(double actual, double expected, String message) { check(Math.abs(actual - expected) < 1e-8, message); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static final class Target { boolean sameWorld = true, dead, removed; }
}
