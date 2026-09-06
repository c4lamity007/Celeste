package dev.c4lam.celeste.module.impl.combat;

/** Pure decisions shared by the explosive auras and their deterministic tests. */
public final class AuraRules {
    private AuraRules() { }

    public enum CrystalAction { NONE, SWORD, PLACE, BREAK }

    public static CrystalAction crystalAction(double selfFeet, double targetFeet, boolean crystal,
                                               boolean swordReady) {
        if (!Double.isFinite(selfFeet) || !Double.isFinite(targetFeet)) return CrystalAction.NONE;
        if (targetFeet > selfFeet + 0.18D) return crystal ? CrystalAction.BREAK : CrystalAction.PLACE;
        return Math.abs(targetFeet - selfFeet) <= 0.18D && swordReady
            ? CrystalAction.SWORD : CrystalAction.NONE;
    }

    /** Vanilla-style distance/exposure and armor/toughness estimate; no enchantment credit. */
    public static double damage(double distance, double power, double exposure, double armor,
                                double toughness, int difficulty) {
        if (!Double.isFinite(distance) || !Double.isFinite(power) || !Double.isFinite(exposure)
            || !Double.isFinite(armor) || !Double.isFinite(toughness) || distance < 0 || power <= 0
            || power > 16 || difficulty <= 0 || distance > power * 2 || exposure < 0) return 0;
        double impact = (1 - distance / (power * 2)) * clamp(exposure, 0, 1);
        // 1.21.11 ExplosionBehavior.calculateDamage returns a float, not an integer.
        double raw = (float) ((impact * impact + impact) * 0.5D * 7 * power * 2 + 1);
        if (difficulty == 1) raw = Math.min(raw, raw * 0.5D + 1);
        else if (difficulty >= 3) raw *= 1.5D;
        double boundedArmor = clamp(armor, 0, 30);
        double mitigation = clamp(boundedArmor - raw / (2 + clamp(toughness, 0, 20) / 4),
            boundedArmor * 0.2D, 20);
        return Math.max(0, raw * (1 - mitigation / 25));
    }

    public static boolean safe(double targetDamage, double selfDamage, double health,
                               double minimumDamage, double maximumSelfDamage, boolean friendInBlast) {
        return Double.isFinite(targetDamage) && Double.isFinite(selfDamage) && Double.isFinite(health)
            && Double.isFinite(minimumDamage) && Double.isFinite(maximumSelfDamage)
            && !friendInBlast && targetDamage >= Math.max(0, minimumDamage) && targetDamage > 0
            && selfDamage >= 0 && selfDamage <= Math.max(0, maximumSelfDamage)
            && selfDamage + 2.0D < health;
    }

    public static double protectedDamage(double damage, int protection) {
        return Double.isFinite(damage) ? Math.max(0, damage) * (1 - clamp(protection, 0, 20) / 25) : 0;
    }

    public static boolean swordReady(int elapsedTicks, double attackSpeed) {
        return elapsedTicks >= 0 && Double.isFinite(attackSpeed) && attackSpeed > 0
            && elapsedTicks * attackSpeed / 20.0D >= 0.92D;
    }

    public enum ConfirmationResult { WAITING, CONFIRMED, TIMED_OUT }

    /** Shared by both auras: only one server-unconfirmed sequence may be active. */
    public static final class PendingLease {
        private Object owner;
        public boolean available(Object requester) { return owner == null || owner == requester; }
        public boolean acquire(Object requester) {
            if (requester == null || !available(requester)) return false;
            owner = requester;
            return true;
        }
        public void release(Object requester) { if (owner == requester) owner = null; }
    }

    /** A predicted state alone cannot satisfy this gate: it needs a newer observed revision. */
    public static final class Confirmation {
        private long revision;
        private long sentTick;
        private boolean active;

        public void begin(long currentRevision, long tick) {
            revision = currentRevision;
            sentTick = tick;
            active = true;
        }

        public boolean active() { return active; }

        public ConfirmationResult observe(long observedRevision, long tick, boolean expectedState) {
            if (!active) return ConfirmationResult.CONFIRMED;
            if (tick < sentTick || tick - sentTick >= 24) {
                clear();
                return ConfirmationResult.TIMED_OUT;
            }
            if (tick > sentTick && observedRevision > revision && expectedState) {
                clear();
                return ConfirmationResult.CONFIRMED;
            }
            return ConfirmationResult.WAITING;
        }

        public void clear() { active = false; revision = 0; sentTick = 0; }
    }

    /** Requires a whole prior vanilla tick of owned silent look before interaction. */
    public static final class ActionArm {
        private long observedTick = Long.MIN_VALUE;
        public void observeEnd(long tick, boolean rotationOwned) { observedTick = rotationOwned ? tick : Long.MIN_VALUE; }
        public boolean ready(long tick, boolean rotationOwned, boolean manualConflict, boolean slotContended) {
            return observedTick != Long.MIN_VALUE && tick == observedTick + 1
                && rotationOwned && !manualConflict && !slotContended;
        }
        public void clear() { observedTick = Long.MIN_VALUE; }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
