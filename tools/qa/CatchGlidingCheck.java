package dev.c4lam.celeste.module.impl.combat;

import static dev.c4lam.celeste.module.impl.combat.HorizontalPearlTrajectory.*;

/** Exercises the Catch module's actual per-sequence gliding model without Minecraft stubs. */
public final class CatchGlidingCheck {
    private static int assertions;

    public static void main(String[] args) {
        Vec eyes = new Vec(0, 100, 0), movement = new Vec(.8, -.2, .35);
        CatchGlidingTrajectory trajectory = new CatchGlidingTrajectory();
        check(trajectory.solveCharge(100, eyes, movement) == null, "idle has no stale pearl");
        trajectory.capture(eyes, movement, 40, -90, 100);
        check(trajectory.solveCharge(99, eyes, movement) == null, "clock reversal rejected");
        check(trajectory.solveCharge(100, eyes, movement) == null, "same-tick second use rejected");
        check(trajectory.solveCharge(131, eyes, movement) == null, "late charge bounded to 30 ticks");
        check(trajectory.solveCharge(Long.MAX_VALUE, eyes, movement) == null, "long delay cannot wrap to a valid age");
        check(trajectory.solveCharge(105, new Vec(Double.NaN, 0, 0), movement) == null, "invalid current origin rejected");

        int solved = 0;
        for (Vec flight : new Vec[] {new Vec(.8, -.2, .35), new Vec(2.4, -.5, 0),
                new Vec(-.7, .4, 1.2), new Vec(.2, -1.4, -.5)}) {
            for (int delay : new int[] {1, 4, 5, 6, 10, 15}) {
                for (Vec correction : new Vec[] {new Vec(0, 0, 0), new Vec(.13, -.08, -.1)}) {
                    trajectory.capture(eyes, flight, 73, -90, 200);
                    Vec origin = eyes.add(flight.scale(delay));
                    Vec currentMovement = flight.add(correction);
                    Solution aim = trajectory.solveCharge(200 + delay, origin, currentMovement);
                    if (aim == null) continue; // A glider may move beyond the available intercept cone.
                    solved++;
                    check(Math.abs(aim.pitch() + 90) > .01, "moving charge gets its own nonvertical pitch");
                    near(aim.windVelocity().subtract(currentMovement).length(), SPEED, 1e-10,
                        "charge inherits current three-dimensional flight movement");
                    State launched = launch(eyes, flight, false, 73, -90);
                    replay(launched, delay, origin, aim);
                    check(aim.equals(trajectory.solveCharge(200 + delay, origin, currentMovement)),
                        "same captured/current state stays deterministic");
                }
            }
        }
        check(solved >= 35, "fast/descending/climbing flight and configured-delay coverage: " + solved);

        trajectory.capture(eyes, movement, 40, -90, 100);
        Vec laterEyes = eyes.add(movement.scale(5));
        Solution straight = trajectory.solveCharge(105, laterEyes, movement);
        Solution turned = trajectory.solveCharge(105, laterEyes, new Vec(-.15, -.3, .7));
        check(straight != null && turned != null, "changed velocity fixture has two valid intercepts");
        check(Math.abs(straight.yaw() - turned.yaw()) > 1 || Math.abs(straight.pitch() - turned.pitch()) > 1,
            "current movement changes charge rotation instead of reusing the standing angle");
        check(trajectory.solveCharge(105, new Vec(1e6, 100, 0), movement) == null,
            "unreachable updated player position never produces a blind charge");
        trajectory.clear();
        check(trajectory.solveCharge(105, laterEyes, movement) == null, "disable/world reset releases captured launch");
        Vec nextEyes = new Vec(20, 130, -30), nextMotion = new Vec(-.5, -.1, .8);
        trajectory.capture(nextEyes, nextMotion, -120, -90, 300);
        Solution next = trajectory.solveCharge(306, nextEyes.add(nextMotion.scale(6)), nextMotion);
        check(next != null, "new trigger after reset uses a fresh launch");
        replay(launch(nextEyes, nextMotion, false, -120, -90), 6, nextEyes.add(nextMotion.scale(6)), next);
        trajectory.clear();
        check(trajectory.solveCharge(306, nextEyes, nextMotion) == null, "repeated cleanup is safe");
        System.out.println("Gliding Catch: " + assertions + " assertions passed; " + solved + " moving delayed intercepts replayed.");
    }

    private static void replay(State initial, int delay, Vec origin, Solution aim) {
        // Independent scalar replay of the older pearl's gravity/drag/movement ordering.
        double x = initial.position().x(), y = initial.position().y(), z = initial.position().z();
        double vx = initial.velocity().x(), vy = initial.velocity().y(), vz = initial.velocity().z();
        for (int age = 0; age <= delay + aim.windTicks(); age++) {
            vx *= (double) .99F;
            vy = (vy - .03) * (double) .99F;
            vz *= (double) .99F;
            double fraction = age == delay + aim.windTicks() ? aim.pearlFraction() : 1;
            x += vx * fraction; y += vy * fraction; z += vz * fraction;
        }
        Vec center = origin.add(aim.windVelocity().scale(aim.windTicks()))
            .add(new Vec(0, .15625 - (double) .15F, 0));
        double half = .15625 + Math.max(0, Math.min(.3F, (float) (delay + aim.windTicks() - 2) / 20F));
        check(Math.abs(x - center.x()) <= half + 1e-9 && Math.abs(y - center.y()) <= half + 1e-9
            && Math.abs(z - center.z()) <= half + 1e-9, "modeled pearl intersects charge box before its movement");
        near(new Vec(x, y, z).subtract(aim.catchPosition()).length(), 0, 1e-9, "catch point matches independent replay");
    }

    private static void near(double actual, double expected, double epsilon, String message) {
        check(Math.abs(actual - expected) <= epsilon, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
}
