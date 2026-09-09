package dev.c4lam.celeste.render.model;

/** Visual locomotion uses actual horizontal displacement, not ground-gravity velocity or held keys. */
public final class PlayerModelMotion {
    private static final double STILL_DISTANCE_SQUARED = .0001;
    private PlayerModelMotion() {}

    public static boolean stationary(boolean grounded, boolean riding, boolean gliding,
                                     double dx, double dy, double dz) {
        return grounded && !riding && !gliding && Double.isFinite(dx) && Double.isFinite(dy)
            && Double.isFinite(dz) && dx * dx + dz * dz < STILL_DISTANCE_SQUARED && Math.abs(dy) < .04;
    }

    /** Signed playback rate: walking backward reverses the stride; strafing still steps normally. */
    public static double walkRate(boolean grounded, boolean riding, boolean gliding,
                                  double dx, double dz, float bodyYaw) {
        if (!grounded || riding || gliding || !Double.isFinite(dx) || !Double.isFinite(dz)
            || !Float.isFinite(bodyYaw)) return 0;
        double distance = Math.hypot(dx, dz);
        // A teleport is not a one-frame sprint animation.
        if (distance < .01 || distance > 1.5) return 0;
        double yaw = Math.toRadians(bodyYaw);
        double forward = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double rate = Math.max(.25, Math.min(1.8, distance / .21585));
        return forward < -distance * .25 ? -rate : rate;
    }
}
