package dev.c4lam.celeste.rotation;

/** Focused checks of the production render math, independent of Minecraft/rendering. */
public final class ElytraRotationCheck {
    private static int assertions;

    public static void main(String[] args) {
        near(ElytraRotationMath.yaw(179, -179, 0), 179, 0, "yaw starts at the previous frame");
        near(ElytraRotationMath.yaw(179, -179, .5F), 180, 0, "yaw crosses seam by the short route");
        near(ElytraRotationMath.yaw(179, -179, 1), 181, 0, "yaw retains continuous phase at completion");
        near(ElytraRotationMath.yaw(-179, 179, .5F), -180, 0, "reverse seam follows the short route");
        near(ElytraRotationMath.yaw(720, 90, .5F), 765, 0, "multiple turns retain the previous yaw phase");
        near(ElytraRotationMath.yaw(0, 180, .5F), -90, 0, "exact half turn follows vanilla negative wrap");
        near(ElytraRotationMath.yaw(20, 100, -1), 20, 0, "negative frame fraction bounded");
        near(ElytraRotationMath.yaw(20, 100, 2), 100, 0, "large frame fraction bounded");
        near(ElytraRotationMath.pitch(10, -70, .25F), -10, 0, "pitch interpolates between ticks");
        near(ElytraRotationMath.pitch(-180, 180, 0), -90, 0, "previous pitch bounded");
        near(ElytraRotationMath.pitch(-180, 180, 1), 90, 0, "current pitch bounded");
        near(ElytraRotationMath.pitch(-180, 180, .5F), 0, 0, "pitch bounded before interpolation");
        near(ElytraRotationMath.pitch(-20, 40, -1), -20, 0, "pitch frame lower bound");
        near(ElytraRotationMath.pitch(-20, 40, 2), 40, 0, "pitch frame upper bound");
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            near(ElytraRotationMath.yaw(invalid, 25, .5F), 25, 0, "invalid previous yaw uses current");
            near(ElytraRotationMath.yaw(25, invalid, .5F), 25, 0, "invalid current yaw uses previous");
            near(ElytraRotationMath.yaw(invalid, invalid, .5F), 0, 0, "invalid yaw pair has finite fallback");
            near(ElytraRotationMath.yaw(25, 75, invalid), 25, 0, "invalid yaw fraction retains previous");
            near(ElytraRotationMath.pitch(invalid, 25, .5F), 25, 0, "invalid previous pitch uses current");
            near(ElytraRotationMath.pitch(25, invalid, .5F), 25, 0, "invalid current pitch uses previous");
            near(ElytraRotationMath.pitch(invalid, invalid, .5F), 0, 0, "invalid pitch pair has finite fallback");
            near(ElytraRotationMath.pitch(25, 75, invalid), 25, 0, "invalid pitch fraction retains previous");
        }
        check(Float.isFinite(ElytraRotationMath.yaw(Float.MAX_VALUE, -Float.MAX_VALUE, .5F)),
            "extreme finite yaw endpoints cannot overflow");

        bank(false, 0, 0, 0, 0, 1, "zero horizontal movement disables banking");
        bank(false, 0, 0, 1, 0, 0, "vertical look disables banking");
        bank(true, 0, 0, 1, 0, 1, "forward look has no bank");
        bank(true, 0, 0, 1, 0, -1, "backward look preserves vanilla absolute-dot rule");
        bank(true, (float) (Math.PI / 2), 1, 0, 0, 1, "positive perpendicular bank");
        bank(true, (float) (-Math.PI / 2), 0, 1, 1, 0, "negative perpendicular bank");
        bank(true, (float) (Math.PI / 4), 1, 0, 1, 1, "diagonal acute bank");
        bank(true, (float) (Math.PI / 4), 1, 0, -1, 1, "obtuse heading folds with absolute dot");

        double threshold = (double) 1.0E-5F;
        double below = Math.nextDown(Math.sqrt(threshold));
        double above = Math.nextUp(Math.sqrt(threshold));
        bank(false, 0, below, 0, 0, 1, "movement below float threshold is disabled");
        bank(false, 0, 1, 0, 0, below, "look below float threshold is disabled");
        check(above * above < 1.0E-5D, "threshold fixture distinguishes float from double literal");
        bank(true, (float) (Math.PI / 2), above, 0, 0, 1, "movement above float threshold is enabled");
        bank(true, (float) (Math.PI / 2), 1, 0, 0, above, "look above float threshold is enabled");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            bank(false, 0, invalid, 1, 1, 0, "invalid velocity X rejected");
            bank(false, 0, 0, invalid, 1, 0, "invalid velocity Z rejected");
            bank(false, 0, 0, 1, invalid, 0, "invalid look X rejected");
            bank(false, 0, 0, 1, 1, invalid, "invalid look Z rejected");
        }
        bank(false, 0, Double.MAX_VALUE, 1, 1, 0, "overflowing velocity magnitude rejected");
        bank(false, 0, 0, 1, Double.MAX_VALUE, 1, "overflowing look magnitude rejected");

        var camera = ElytraRotationMath.bank(0, 1, 0, 1);
        var silentSide = ElytraRotationMath.bank(0, 1, 1, 0);
        check(camera.apply() && camera.radians() == 0, "forward camera has zero vanilla bank");
        check(silentSide.apply() && Math.abs(silentSide.radians()) > 1,
            "same flight needs a different bank when the displayed silent look turns sideways");

        int frames = 0;
        for (float previous : new float[] {-540, -179, -80, 0, 73, 179, 540}) {
            for (float current : new float[] {-179, -90, 0, 90, 179}) {
                float wrapped = (current - previous) % 360.0F;
                if (wrapped >= 180) wrapped -= 360;
                if (wrapped < -180) wrapped += 360;
                for (int frame = 0; frame <= 8; frame++) {
                    float fraction = frame / 8.0F;
                    near(ElytraRotationMath.yaw(previous, current, fraction), previous + fraction * wrapped, 0,
                        "frame yaw matches independent literal shortest-turn fixture");
                    frames++;
                }
            }
        }
        int headings = 0;
        for (int velocityDegrees = -180; velocityDegrees <= 180; velocityDegrees += 30) {
            for (int lookDegrees = -180; lookDegrees <= 180; lookDegrees += 30) {
                // Independent unit-heading oracle: separation magnitude folds into [0, pi/2].
                double v = Math.toRadians(velocityDegrees), l = Math.toRadians(lookDegrees);
                double x = Math.cos(v), z = Math.sin(v), lx = Math.cos(l), lz = Math.sin(l);
                double dot = x * lx + z * lz;
                double expected = Math.signum(x * lz - z * lx) * Math.acos(Math.min(1, Math.abs(dot)));
                var actual = ElytraRotationMath.bank(2 * x, 2 * z, 3 * lx, 3 * lz);
                check(actual.apply(), "nonzero heading pair enables banking");
                near(actual.radians(), expected, 1.0E-6, "bank matches independent unit-heading fixture");
                headings++;
            }
        }
        System.out.println("Elytra render rotations: " + assertions + " assertions passed; " + frames
            + " interpolation frames and " + headings + " heading pairs checked.");
    }

    private static void bank(boolean apply, float radians, double vx, double vz, double lx, double lz, String label) {
        var actual = ElytraRotationMath.bank(vx, vz, lx, lz);
        check(actual.apply() == apply, label + " (enabled)");
        near(actual.radians(), radians, 1.0E-7, label + " (angle)");
    }

    private static void near(double actual, double expected, double epsilon, String label) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= epsilon,
            label + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
