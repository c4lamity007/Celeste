package dev.c4lam.celeste.rotation;

import java.time.Duration;
import java.util.Objects;

/** Immutable rotation request submitted by one module/owner. */
public final class RotationTask {
    private final Object owner;
    private final Rotation target;
    private final int priority;
    private final int lifetimeTicks;
    private final long lifetimeNanos;
    private final float maximumYawStep;
    private final float maximumPitchStep;
    private final boolean sensitivityQuantized;
    private final MoveFixMode moveFixMode;
    private final boolean glidingPhysics;

    private RotationTask(Builder builder) {
        owner = Objects.requireNonNull(builder.owner, "owner");
        target = Objects.requireNonNull(builder.target, "target");
        priority = builder.priority;
        lifetimeTicks = builder.lifetimeTicks;
        lifetimeNanos = builder.lifetimeNanos;
        maximumYawStep = builder.maximumYawStep;
        maximumPitchStep = builder.maximumPitchStep;
        sensitivityQuantized = builder.sensitivityQuantized;
        moveFixMode = Objects.requireNonNull(builder.moveFixMode, "moveFixMode").packetRepresentable();
        glidingPhysics = builder.glidingPhysics;

        if (lifetimeTicks < 1) {
            throw new IllegalArgumentException("lifetimeTicks must be at least one");
        }
        if (lifetimeNanos <= 0L) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        if (!Float.isFinite(maximumYawStep) || maximumYawStep <= 0.0F
            || !Float.isFinite(maximumPitchStep) || maximumPitchStep <= 0.0F) {
            throw new IllegalArgumentException("rotation steps must be positive and finite");
        }
    }

    public static Builder builder(Object owner, Rotation target) {
        return new Builder(owner, target);
    }

    public Object owner() {
        return owner;
    }

    public Rotation target() {
        return target;
    }

    public int priority() {
        return priority;
    }

    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    public long lifetimeNanos() {
        return lifetimeNanos;
    }

    public float maximumYawStep() {
        return maximumYawStep;
    }

    public float maximumPitchStep() {
        return maximumPitchStep;
    }

    public boolean sensitivityQuantized() {
        return sensitivityQuantized;
    }

    public MoveFixMode moveFixMode() {
        return moveFixMode;
    }

    /** Align vanilla gliding with this request's packet look, independently of ground input correction. */
    public boolean glidingPhysics() {
        return glidingPhysics;
    }

    public static final class Builder {
        private final Object owner;
        private final Rotation target;
        private int priority;
        private int lifetimeTicks = 1;
        private long lifetimeNanos = Duration.ofMillis(250L).toNanos();
        private float maximumYawStep = 180.0F;
        private float maximumPitchStep = 180.0F;
        private boolean sensitivityQuantized = true;
        private MoveFixMode moveFixMode = MoveFixMode.STRICT;
        private boolean glidingPhysics;

        private Builder(Object owner, Rotation target) {
            this.owner = owner;
            this.target = target;
        }

        public Builder priority(int value) {
            priority = value;
            return this;
        }

        public Builder lifetimeTicks(int value) {
            lifetimeTicks = value;
            return this;
        }

        public Builder lifetime(Duration value) {
            lifetimeNanos = Objects.requireNonNull(value, "value").toNanos();
            return this;
        }

        public Builder smoothing(float yawStep, float pitchStep) {
            maximumYawStep = yawStep;
            maximumPitchStep = pitchStep;
            return this;
        }

        public Builder sensitivityQuantized(boolean value) {
            sensitivityQuantized = value;
            return this;
        }

        public Builder moveFix(MoveFixMode value) {
            moveFixMode = value;
            return this;
        }

        public Builder glidingPhysics(boolean value) {
            glidingPhysics = value;
            return this;
        }

        public RotationTask build() {
            return new RotationTask(this);
        }
    }
}
