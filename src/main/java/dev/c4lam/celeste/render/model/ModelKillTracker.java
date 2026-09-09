package dev.c4lam.celeste.render.model;

import java.util.function.Predicate;

/** Bounded reference to the actual attacked entity, so a death followed by removal is not lost between ticks. */
public final class ModelKillTracker<T> {
    private T target;
    private int attackedAt;

    public void record(T attacked, int tick) { target = attacked; attackedAt = tick; }

    public boolean confirmedDeath(int tick, Predicate<T> sameWorld, Predicate<T> dead) {
        if (target == null) return false;
        if (tick < attackedAt || (long) tick - attackedAt > 100 || !sameWorld.test(target)) {
            clear(); return false;
        }
        // Removed/unloaded alone is deliberately not death confirmation.
        if (!dead.test(target)) return false;
        clear(); return true;
    }

    public void clear() { target = null; }
}
