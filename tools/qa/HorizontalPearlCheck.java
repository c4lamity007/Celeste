package dev.c4lam.celeste.module.impl.combat;

import static dev.c4lam.celeste.module.impl.combat.HorizontalPearlTrajectory.*;

/** Deterministic, no-spread physics checks independent of Minecraft/native rendering. */
public final class HorizontalPearlCheck {
    private static int assertions;
    public static void main(String[] args) {
        Vec origin = new Vec(0, 100, 0), zero = new Vec(0, 0, 0);
        State initial = launch(origin, zero, true, 0, 0);
        State first = step(initial);
        near(first.position().z(), 1.5 * (double) .99F, 1e-12, "drag before move");
        near(first.position().y(), 100 - (double) .1F - .03 * (double) .99F, 1e-12, "gravity before move");
        near(inherited(new Vec(.2, .42, -.3), true).y(), 0, 0, "grounded Y not inherited");
        near(inherited(new Vec(.2, .42, -.3), false).y(), .42, 0, "airborne Y inherited");
        near(direction(90, 0).x(), -1, 1e-12, "Minecraft yaw axis");
        near(direction(0, -90).y(), 1, 1e-12, "Minecraft pitch sign");
        check(!throwWindow(0) && throwWindow(1) && throwWindow(2) && !throwWindow(3) && !throwWindow(-1), "bounded throw window");
        check(solve(initial, 0, origin, zero) == null && solve(initial, 3, origin, zero) == null, "invalid delay rejected");
        check(solve(null, 1, origin, zero) == null, "missing pearl rejected");
        check(solve(new State(new Vec(Double.NaN, 0, 0), zero), 1, origin, zero) == null, "nonfinite pearl rejected");
        check(solve(initial, 1, new Vec(0, Double.POSITIVE_INFINITY, 0), zero) == null, "nonfinite origin rejected");
        check(solve(initial, 1, new Vec(1e6, 0, 0), zero) == null, "unreachable origin rejected");
        check(rayBox(zero, new Vec(2, 0, 0), zero, 1) < 0, "inside-start is not a vanilla ray entrance");
        check(rayBox(new Vec(-1, 0, 0), zero, zero, 1) < 0, "on-face start is not an entrance");
        check(rayBox(new Vec(-2, 0, 0), new Vec(-1, 0, 0), zero, 1) < 0, "endpoint-only entry is not a hit");
        near(rayBox(new Vec(-2, 0, 0), zero, zero, 1), .5, 0, "positive-axis entrance");
        near(rayBox(new Vec(0, 2, 0), zero, zero, 1), .5, 0, "negative-axis entrance");
        check(rayBox(new Vec(-2, 1.1, 0), new Vec(2, 1.1, 0), zero, 1) < 0, "parallel outside face");
        check(rayBox(new Vec(-1.00000001, 0, 0), new Vec(-.99999999, 0, 0), zero, 1) < 0, "vanilla near-zero direction threshold");
        int solved = 0;
        for (int yaw : new int[] {-179, -90, 0, 73, 179, 540}) {
            for (int pitch : new int[] {-65, -30, -10, 0, 20, 55}) {
                for (Vec movement : new Vec[] {zero, new Vec(.25, 0, .18), new Vec(-.3, .42, .2), new Vec(.1, -.6, -.2)}) {
                    for (int delay : new int[] {1, 2}) {
                        State thrown = launch(origin, movement, false, yaw, pitch);
                        Vec chargeOrigin = origin.add(movement.scale(delay));
                        Solution answer = solve(thrown, delay, chargeOrigin, movement);
                        if (answer == null) continue; // Some geometries simply have no catch at 1.5 launch speed.
                        solved++;
                        check(Float.isFinite(answer.yaw()) && Float.isFinite(answer.pitch()) && Math.abs(answer.pitch()) <= 90, "finite legal aim");
                        near(answer.windVelocity().subtract(movement).length(), SPEED, 1e-10, "no artificial charge speed");
                        check(answer.windTicks() >= 0 && answer.windTicks() <= MAX_FLIGHT_TICKS + 1, "bounded flight horizon");
                        check(answer.pearlFraction() > 0 && answer.pearlFraction() < 1, "strictly inside pearl movement segment");
                        replay(thrown, delay, chargeOrigin, answer);
                        Solution repeat = solve(thrown, delay, chargeOrigin, movement);
                        check(answer.equals(repeat), "deterministic no-random-spread result");
                    }
                }
            }
        }
        check(solved > 150, "wide moving/jumping/falling angle coverage: " + solved);
        Solution horizontal = solve(initial, 1, origin, zero);
        check(horizontal != null && horizontal.windTicks() > 2, "throw delay is not an impossible 1-2 tick impact guarantee");
        Solution shifted = solve(launch(origin, new Vec(.35, .2, 0), false, 0, -15), 2,
            origin.add(new Vec(.7, .4, 0)), new Vec(-.2, -.1, .1));
        check(shifted != null, "charge re-solve supports changed player movement");
        int glidingSolved = checkGliding();
        System.out.println("Horizontal pearl physics: " + assertions + " assertions passed; " + solved
            + " walking/airborne and " + glidingSolved + " elytra intercept scenarios replayed.");
    }

    private static int checkGliding() {
        Vec zero = new Vec(0, 0, 0), forward = new Vec(0, 0, 1);
        Vec level = glideVelocity(forward, 0, 0, .08, false);
        near(level.x(), 0, 0, "level gliding X");
        near(level.y(), -.018 * (double) .98F, 1e-14, "level gliding gravity and lift");
        near(level.z(), 1.0018 * (double) .99F, 1e-14, "level gliding steering and drag");
        Vec vertical = glideVelocity(new Vec(0, -.2, 1), 0, -90, .08, false);
        near(vertical.y(), -.28 * (double) .98F, 1e-14, "straight-up look skips horizontal-speed lift");
        near(vertical.z(), (double) .99F, 1e-14, "straight-up float look has zero horizontal steering");
        Vec gravityFree = glideVelocity(forward, 0, 0, 0, false);
        near(gravityFree.y(), 0, 0, "zero gravity is respected");
        near(gravityFree.z(), (double) .99F, 0, "drag still applies without gravity");
        Vec falling = new Vec(.1, -.3, .8), rising = new Vec(.1, .3, .8);
        check(glideVelocity(falling, 25, -20, .08, true).y()
            > glideVelocity(falling, 25, -20, .08, false).y(), "slow falling limits descending gravity");
        check(glideVelocity(rising, 25, -20, .08, true).equals(glideVelocity(rising, 25, -20, .08, false)),
            "slow falling does not limit rising gravity");
        check(glideVelocity(null, 0, 0, .08, false) == null, "missing gliding movement rejected");
        check(glideVelocity(new Vec(Double.NaN, 0, 0), 0, 0, .08, false) == null, "invalid gliding movement rejected");
        check(glideVelocity(zero, Float.NaN, 0, .08, false) == null, "invalid gliding yaw rejected");
        check(glideVelocity(zero, 0, Float.POSITIVE_INFINITY, .08, false) == null, "invalid gliding pitch rejected");
        check(glideVelocity(zero, 0, 0, Double.NaN, false) == null, "invalid gliding gravity rejected");

        // Test-only literal table, independent of the production allocation-free lookup.
        float[] sine = new float[65536];
        for (int i = 0; i < sine.length; i++) sine[i] = (float) Math.sin(i / 10430.378350470453);
        Vec eyes = new Vec(23, 120, -41);
        int solved = 0;
        for (int yaw : new int[] {-179, -90, 0, 73, 179, 540}) {
            for (int pitch : new int[] {-90, -65, -30, 0, 20, 55, 90}) {
                for (Vec launchMotion : new Vec[] {new Vec(0, -.05, 1.8), new Vec(2.4, -.7, .2),
                        new Vec(-.7, .6, 1.2), new Vec(.2, -1.4, -.5)}) {
                    State pearl = launch(eyes, launchMotion, false, yaw, pitch);
                    Vec origin = eyes, motion = launchMotion;
                    for (int delay = 1; delay <= 2; delay++) {
                        // A turn between the pearl and charge must not reuse the original motion.
                        float currentYaw = yaw + (delay == 2 ? 25 : 0);
                        boolean slowFalling = pitch > 0;
                        Vec expected = referenceGlide(motion, currentYaw, pitch, .08, slowFalling, sine);
                        motion = glideVelocity(motion, currentYaw, pitch, .08, slowFalling);
                        near(motion.subtract(expected).length(), 0, 1e-12, "vanilla gliding scalar/table parity");
                        origin = origin.add(motion);
                        check(origin.subtract(eyes.add(launchMotion.scale(delay))).length() > 1e-5,
                            "elytra origin follows accelerated travel, not standing linear forecast");
                        Solution aim = solve(pearl, delay, origin, motion);
                        if (aim == null) continue;
                        solved++;
                        check(Float.isFinite(aim.yaw()) && Float.isFinite(aim.pitch()) && Math.abs(aim.pitch()) <= 90,
                            "gliding has a finite legal charge aim");
                        near(aim.windVelocity().subtract(motion).length(), SPEED, 1e-10,
                            "gliding charge inherits actual XYZ movement without altering launch speed");
                        replay(pearl, delay, origin, aim);
                    }
                }
            }
        }
        check(solved >= 100, "fast/climbing/diving/turning elytra coverage: " + solved);
        return solved;
    }

    private static Vec referenceGlide(Vec v, float yaw, float pitch, double gravity, boolean slow, float[] sine) {
        float p = pitch * .017453292F, y = -yaw * .017453292F;
        double tableScale = 10430.378350470453;
        float cosPitch = sine[(int) ((long) (p * tableScale + 16384) & 65535L)];
        double lx = sine[(int) ((long) (y * tableScale) & 65535L)] * cosPitch;
        double lz = sine[(int) ((long) (y * tableScale + 16384) & 65535L)] * cosPitch;
        double horizontalLook = Math.sqrt(lx * lx + lz * lz);
        double horizontalSpeed = Math.sqrt(v.x() * v.x() + v.z() * v.z());
        double lift = Math.cos((double) p); lift *= lift;
        if (slow && v.y() <= 0) gravity = Math.min(gravity, .01);
        double x = v.x(), z = v.z(), vy = v.y() + gravity * (-1 + .75 * lift);
        if (vy < 0 && horizontalLook > 0) {
            double a = vy * -.1 * lift;
            x += lx / horizontalLook * a; vy += a; z += lz / horizontalLook * a;
        }
        if (p < 0 && horizontalLook > 0) {
            double a = horizontalSpeed * -sine[(int) ((long) (p * tableScale) & 65535L)] * .04;
            x -= lx / horizontalLook * a; vy += a * 3.2; z -= lz / horizontalLook * a;
        }
        if (horizontalLook > 0) {
            x += (lx / horizontalLook * horizontalSpeed - x) * .1;
            z += (lz / horizontalLook * horizontalSpeed - z) * .1;
        }
        return new Vec(x * (double) .99F, vy * (double) .98F, z * (double) .99F);
    }

    private static void replay(State initial, int delay, Vec origin, Solution answer) {
        // Independent scalar integrator, not the solver's step/firstCollision helpers.
        double x = initial.position().x(), y = initial.position().y(), z = initial.position().z();
        double vx = initial.velocity().x(), vy = initial.velocity().y(), vz = initial.velocity().z();
        for (int age = 0; age <= delay + answer.windTicks(); age++) {
            vx *= (double) .99F; vy = (vy - .03) * (double) .99F; vz *= (double) .99F;
            double portion = age == delay + answer.windTicks() ? answer.pearlFraction() : 1;
            x += vx * portion; y += vy * portion; z += vz * portion;
        }
        Vec center = origin.add(answer.windVelocity().scale(answer.windTicks())).add(new Vec(0, .15625 - (double) .15F, 0));
        double half = .15625 + Math.max(0, Math.min(.3F, (float) (delay + answer.windTicks() - 2) / 20F));
        check(Math.abs(x - center.x()) <= half + 1e-9 && Math.abs(y - center.y()) <= half + 1e-9
            && Math.abs(z - center.z()) <= half + 1e-9, "pearl ray actually intersects wind box in discrete older-pearl-first order");
        near(new Vec(x, y, z).subtract(answer.catchPosition()).length(), 0, 1e-9, "reported intercept position");
    }
    private static void near(double actual, double expected, double epsilon, String message) {
        check(Math.abs(actual - expected) <= epsilon, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
