package dev.c4lam.celeste.hud;

import java.util.UUID;

/** Pure Java 21 regression checks; no Minecraft client, server or health fixture. */
public final class LocalDamageEstimatorCheck {
    private static int checks;
    private static final UUID A = new UUID(0L, 1L);
    private static final UUID B = new UUID(0L, 2L);
    private static final LocalDamageEstimator.Defense NONE = LocalDamageEstimator.Defense.NONE;

    public static void main(String[] args) {
        outgoingDamage();
        armorAndEnchantments();
        duplicateAndInvulnerabilityWindow();
        targetLifecycleAndBounds();
        invalidInputsAndMisses();
        System.out.println("PASS LocalDamageEstimatorCheck: " + checks + " deterministic checks");
    }

    private static void outgoingDamage() {
        close(7.0, LocalDamageEstimator.outgoing(7, 0, 0, 1, false), "charged sword");
        close(2.8, LocalDamageEstimator.outgoing(7, 0, 0, 0.5, false), "half cooldown quadratic");
        close(1.4, LocalDamageEstimator.outgoing(7, 0, 0, 0, false), "uncharged sword");
        close(10.5, LocalDamageEstimator.outgoing(7, 0, 0, 1, true), "charged critical");
        close(2.8, LocalDamageEstimator.outgoing(7, 0, 0, 0.5, true), "weak attack cannot crit");
        close(13.5, LocalDamageEstimator.outgoing(7, 3, 0, 1, true), "enchantment not crit multiplied");
        close(4.3, LocalDamageEstimator.outgoing(7, 3, 0, 0.5, false), "enchantment linear cooldown");
        close(14.4, LocalDamageEstimator.outgoing(6, 0, 12, 0.5, false), "mace bonus not cooldown scaled");
        close(27.0, LocalDamageEstimator.outgoing(6, 0, 12, 1, true), "mace bonus before crit");
        close(0.0, LocalDamageEstimator.sharpnessBonus(0), "no sharpness");
        close(1.0, LocalDamageEstimator.sharpnessBonus(1), "sharpness one");
        close(3.0, LocalDamageEstimator.sharpnessBonus(5), "sharpness five");
        close(0.0, LocalDamageEstimator.maceBonus(1.5, 5), "mace threshold");
        close(12.0, LocalDamageEstimator.maceBonus(3, 0), "mace first fall tier");
        close(22.0, LocalDamageEstimator.maceBonus(8, 0), "mace second fall tier");
        close(24.0, LocalDamageEstimator.maceBonus(10, 0), "mace third fall tier");
        close(49.0, LocalDamageEstimator.maceBonus(10, 5), "mace density");
    }

    private static void armorAndEnchantments() {
        close(7.0, LocalDamageEstimator.mitigate(7, NONE), "no armor");
        close(1.89, LocalDamageEstimator.mitigate(7, defense(20, 8, 0, 0)), "diamond armor/toughness");
        close(0.6804, LocalDamageEstimator.mitigate(7, defense(20, 8, 16, 0)), "four protection four pieces");
        close(6.09, LocalDamageEstimator.mitigate(7, defense(20, 8, 0, 0.6)), "breach subtracts armor effectiveness");
        close(7.0, LocalDamageEstimator.mitigate(7, defense(20, 8, 0, 10)), "breach clamp");
        close(1.4, LocalDamageEstimator.mitigate(7, defense(0, 0, 99, 0)), "protection cap twenty");
        close(4.0, LocalDamageEstimator.mitigate(20, defense(30, 20, 0, 0)), "armor reduction cap eighty percent");
        close(84.0, LocalDamageEstimator.mitigate(100, defense(20, 0, 0, 0)), "large hit minimum armor contribution");
    }

    private static void duplicateAndInvulnerabilityWindow() {
        var ledger = new LocalDamageEstimator.Ledger();
        close(20, ledger.health(A, 1, 0), "fixed baseline");
        close(6, ledger.attack(A, 1, 0, true, 6, NONE), "first outgoing hit");
        close(14, ledger.health(A, 1, 0), "local subtraction");
        close(0, ledger.attack(A, 1, 0, true, 6, NONE), "duplicate hook in same tick");
        close(0, ledger.attack(A, 1, 1, true, 6, NONE), "equal hit inside iframe");
        close(2, ledger.attack(A, 1, 5, true, 8, NONE), "stronger hit applies only excess");
        close(0, ledger.attack(A, 1, 9, true, 7, NONE), "weaker hit inside original iframe");
        close(6, ledger.attack(A, 1, 10, true, 6, NONE), "first hit window expires exactly ten ticks");
        close(6, ledger.health(A, 1, 10), "stronger hit did not extend iframe");
        close(0, ledger.attack(A, 1, 11, true, 5, NONE), "new iframe weaker hit");
        close(6, ledger.attack(A, 1, 20, true, 100, NONE), "estimated health clamps at zero");
        close(0, ledger.health(A, 1, 21), "zero is an estimate, not a removal signal");
        close(0, ledger.attack(A, 1, 30, true, 100, NONE), "no health underflow");
        close(20, ledger.health(B, 2, 30), "independent target");

        ledger.clear();
        ledger.attack(A, 1, 0, true, 6, NONE);
        close(2, ledger.attack(A, 1, 0, true, 8, NONE), "real stronger same-tick swap applies excess");
        close(0, ledger.attack(A, 1, 0, true, 8, NONE), "duplicate stronger same-tick hook ignored");

        ledger.clear();
        var armor = defense(20, 0, 0, 0);
        close(2.88, ledger.attack(A, 1, 0, true, 8, armor), "first armored hit");
        close(0.48, ledger.attack(A, 1, 1, true, 10, armor), "iframe excess mitigated as damage, not final totals");
        close(16.64, ledger.health(A, 1, 2), "armored iframe ledger");
    }

    private static void targetLifecycleAndBounds() {
        var ledger = new LocalDamageEstimator.Ledger();
        ledger.attack(A, 1, 10, true, 6, NONE);
        close(20, ledger.health(A, 2, 11), "same UUID/new entity resets");
        ledger.attack(A, 2, 11, true, 6, NONE);
        ledger.clear();
        close(20, ledger.health(A, 2, 11), "world or player lifecycle clear");
        close(6, ledger.attack(A, 2, 11, true, 6, NONE), "clear resets duplicate state");
        close(20, ledger.health(A, 2, 5), "local tick rewind resets");
        ledger.attack(A, 2, 5, true, 6, NONE);
        close(20, ledger.health(A, 2, 1_206), "unobserved target expires after sixty seconds");
        ledger.attack(A, 2, 1_206, true, 6, NONE);
        ledger.retainPresent((uuid, id) -> false);
        close(20, ledger.health(A, 2, 1_207), "entity removal resets");
        ledger.attack(A, 2, 1_207, true, 6, NONE);
        ledger.retainPresent((uuid, id) -> uuid.equals(B) && id == 2);
        close(20, ledger.health(A, 2, 1_208), "reused numeric ID cannot retain prior UUID");

        ledger.clear();
        ledger.attack(A, 1, 0, true, 6, NONE);
        ledger.health(B, 2, 0);
        for (int i = 3; i <= 32; i++) {
            ledger.health(new UUID(0, i), i, 0);
        }
        ledger.health(A, 1, 0);
        ledger.health(new UUID(0, 33), 33, 0);
        close(LocalDamageEstimator.MAX_TARGETS, ledger.size(), "bounded cache");
        close(14, ledger.health(A, 1, 0), "most recent target survives eviction");
        close(20, ledger.health(B, 2, 0), "evicted target returns baseline");
    }

    private static void invalidInputsAndMisses() {
        var ledger = new LocalDamageEstimator.Ledger();
        close(0, ledger.attack(A, 1, 0, false, 20, NONE), "miss or blocked/reach-ineligible attack ignored");
        close(0, ledger.size(), "ineligible attack does not create state");
        close(0, ledger.attack(A, 1, 0, true, Double.NaN, NONE), "NaN damage ignored");
        close(0, ledger.attack(A, 1, 0, true, Double.POSITIVE_INFINITY, NONE), "infinite damage ignored");
        close(0, ledger.attack(A, 1, 0, true, -7, NONE), "negative damage ignored");
        close(20, ledger.health(A, 1, 0), "invalid input leaves baseline intact");
        close(7, ledger.attack(A, 1, 0, true, 7, NONE), "invalid input does not consume attack tick");
        close(13, ledger.health(A, 1, 0), "valid hit still applies");
        close(7, LocalDamageEstimator.mitigate(7, defense(Double.NaN, -4, Double.NaN, -1)), "invalid armor sanitized");
        close(1.4, LocalDamageEstimator.outgoing(7, 0, 0, Double.NaN, true), "invalid cooldown sanitized");
        close(7, LocalDamageEstimator.outgoing(7, 0, 0, 99, false), "cooldown cap");
    }

    private static LocalDamageEstimator.Defense defense(double armor, double toughness, double protection, double bypass) {
        return new LocalDamageEstimator.Defense(armor, toughness, protection, bypass);
    }

    private static void close(double expected, double actual, String description) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.00002) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}
