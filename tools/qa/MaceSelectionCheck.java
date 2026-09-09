package dev.c4lam.celeste.module.impl.combat;

import java.util.Arrays;

/** Deterministic Java 21 behavior checks; no client, packets or world required. */
public final class MaceSelectionCheck {
    private static int checks;

    public static void main(String[] args) {
        heightAndArmor();
        enchantmentsAndMovement();
        slotsAndLifecycle();
        cooldownAndCritical();
        matrix();
        System.out.println("PASS MaceSelectionCheck: " + checks + " deterministic checks");
    }

    private static void heightAndArmor() {
        close(5.16, hit(0, 0, 4, 20, 8), "ground Breach IV against diamond");
        close(1.56, hit(0, 5, 0, 20, 8), "ground Density adds no damage");
        choose(0, 0, 20, 12, "ground prefers Breach");
        choose(0, 3, 20, 12, "short fall prefers Breach against netherite");
        choose(1, 12, 20, 12, "high fall prefers Density against netherite");
        choose(1, 2, 0, 0, "unarmored target prefers Density even on short smash");
        choose(1, 3, 8, 0, "light armor differs from netherite at same height");
        close(28.0, hit(8, 0, 4, 20, 12), "Breach eight-block raw cap");
        close(28.032, hit(8, 5, 0, 20, 12), "Density narrow eight-block advantage");
        int narrow = MaceSelection.bestSlot(new double[] {
            hit(8, 0, 4, 20, 12), hit(8, 5, 0, 20, 12)
        }, 0);
        equal(1, narrow, "held-slot preference must not overwhelm a real .032 damage advantage");
    }

    private static void enchantmentsAndMovement() {
        close(6, hit(1.5, 5, 0, 0, 0), "smash threshold is strictly above 1.5 blocks");
        close(18, hit(3, 0, 0, 0, 0), "first vanilla smash tier");
        close(28, hit(8, 0, 0, 0, 0), "second vanilla smash tier");
        close(30, hit(10, 0, 0, 0, 0), "third vanilla smash tier");
        close(55, hit(10, 5, 0, 0, 0), "Density five scales with actual fall distance");
        close(45, hit(10, 3, 0, 0, 0), "Density level is read rather than assuming five");
        close(1.56, MaceSelection.damage(6, 0, 100, false, 5, 0, 20, 8, 1, false),
            "gliding disables smash even with stale fall distance");
        close(hit(0, 0, 0, 20, 8), hit(-20, -2, -2, 20, 8), "negative levels and height sanitized");
        equal(1, MaceSelection.bestSlot(new double[] {
            hit(0, 0, 1, 20, 8), hit(0, 0, 4, 20, 8)
        }, 0), "higher Breach level beats first/held lower-level mace");
        equal(1, MaceSelection.bestSlot(new double[] {
            hit(10, 1, 0, 20, 8), hit(10, 5, 0, 20, 8)
        }, 0), "higher Density level beats first/held lower-level mace");
        close(13.5, MaceSelection.damage(6, 7.5, 0, true, 0, 0, 0, 0, 1, false),
            "target-specific damage enchantments contribute");
    }

    private static void slotsAndLifecycle() {
        double breach = hit(12, 0, 4, 20, 12);
        double density = hit(12, 5, 0, 20, 12);
        equal(1, MaceSelection.bestSlot(new double[] {breach, density}, 0), "Breach first does not win high fall");
        equal(0, MaceSelection.bestSlot(new double[] {density, breach}, 1), "reordering hotbar preserves winner");
        equal(1, MaceSelection.bestSlot(new double[] {6, 6}, 1), "equal damage keeps held mace");
        equal(0, MaceSelection.bestSlot(new double[] {6, 6}, 8), "equal damage without held mace keeps first");
        double[] empty = new double[9];
        Arrays.fill(empty, Double.NaN);
        equal(-1, MaceSelection.bestSlot(empty, 0), "no mace");
        empty[8] = 6;
        equal(8, MaceSelection.bestSlot(empty, 0), "single mace in last hotbar slot");
        equal(-1, MaceSelection.bestSlot(new double[0], 0), "empty input");
        equal(-1, MaceSelection.bestSlot(new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}, 0),
            "invalid candidates ignored");
        equal(0, MaceSelection.bestSlot(new double[] {0}, 0), "zero-damage mace still available");
        double[] outside = new double[10];
        Arrays.fill(outside, Double.NaN);
        outside[9] = 100;
        equal(-1, MaceSelection.bestSlot(outside, 9), "inventory outside hotbar excluded");
        choose(1, 12, 20, 12, "high-fall action");
        choose(0, 0, 20, 12, "next grounded action does not retain Density choice");
    }

    private static void cooldownAndCritical() {
        close(0.015, MaceSelection.cooldown(0, 0.6), "candidate mace cooldown after last attack");
        close(0.255, MaceSelection.cooldown(8, 0.6), "mace speed used, not held sword speed");
        close(1, MaceSelection.cooldown(100, 0.6), "cooldown capped");
        close(0, MaceSelection.cooldown(-1, 0.6), "invalid elapsed ticks");
        close(0, MaceSelection.cooldown(100, Double.NaN), "invalid attack speed");
        close(21.9, MaceSelection.damage(6, 0, 3, true, 5, 0, 0, 0, 0.5, true),
            "weak hit does not crit and smash bonus is not cooldown-scaled");
        close(38.25, MaceSelection.damage(6, 0, 3, true, 5, 0, 0, 0, 1, true),
            "charged critical scales base and smash");
        close(41.25, MaceSelection.damage(6, 3, 3, true, 5, 0, 0, 0, 1, true),
            "ordinary enchantment bonus is outside critical multiplier");
    }

    private static void matrix() {
        for (double height : new double[] {0, 1.5, 1.5001, 3, 5, 8, 12, 30}) {
            for (double armor : new double[] {0, 8, 20}) {
                for (double toughness : new double[] {0, 8, 12}) {
                    double breach = hit(height, 0, 4, armor, toughness);
                    double density = hit(height, 5, 0, armor, toughness);
                    int expected = density > breach + 1.0E-7 ? 1 : 0;
                    double[] scores = {breach, density};
                    equal(expected, MaceSelection.bestSlot(scores, 0), "height/armor/toughness matrix");
                    close(breach, scores[0], "selection leaves input unchanged");
                    close(density, scores[1], "selection leaves input unchanged");
                }
            }
        }
    }

    private static double hit(double height, int density, int breach, double armor, double toughness) {
        return MaceSelection.damage(6, 0, height, true, density, breach, armor, toughness, 1, false);
    }

    private static void choose(int expected, double height, double armor, double toughness, String reason) {
        equal(expected, MaceSelection.bestSlot(new double[] {
            hit(height, 0, 4, armor, toughness), hit(height, 5, 0, armor, toughness)
        }, -1), reason);
    }

    private static void equal(int expected, int actual, String reason) {
        checks++;
        if (expected != actual) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }

    private static void close(double expected, double actual, String reason) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.000001) {
            throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
        }
    }
}
