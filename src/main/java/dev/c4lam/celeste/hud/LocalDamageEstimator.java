package dev.c4lam.celeste.hud;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Pure, presentation-only melee estimates. No received health or damage values
 * enter this model. Twenty HP is an assumption, never a claim about the target.
 */
public final class LocalDamageEstimator {
    public static final float BASELINE_HEALTH = 20.0f;
    static final int MAX_TARGETS = 32;
    static final long FORGET_AFTER_TICKS = 1_200L;
    private static final long HURT_WINDOW_TICKS = 10L;

    private LocalDamageEstimator() {
    }

    public record Defense(double armor, double toughness, double protection, double armorBypass) {
        public static final Defense NONE = new Defense(0.0, 0.0, 0.0, 0.0);
    }

    public static double sharpnessBonus(int level) {
        return level > 0 ? 0.5 * level + 0.5 : 0.0;
    }

    /** Caller determines whether the held mace can smash from local movement state. */
    public static double maceBonus(double fallDistance, int densityLevel) {
        double height = positive(fallDistance);
        if (height <= 1.5) {
            return 0.0;
        }
        double base = height <= 3.0 ? 4.0 * height
            : height <= 8.0 ? 12.0 + 2.0 * (height - 3.0) : 22.0 + height - 8.0;
        return base + 0.5 * Math.max(0, densityLevel) * height;
    }

    /** Vanilla melee order: cooldown scales base quadratically, enchants linearly. */
    public static double outgoing(double weaponDamage, double enchantmentDamage, double itemBonus,
                                  double cooldown, boolean critical) {
        double charge = clamp(cooldown, 0.0, 1.0);
        double base = positive(weaponDamage) * (0.2 + 0.8 * charge * charge) + positive(itemBonus);
        if (critical && charge > 0.9) {
            base *= 1.5;
        }
        return positive(base + positive(enchantmentDamage) * charge);
    }

    /** Armor/toughness and general Protection are estimated from worn items only. */
    public static double mitigate(double damage, Defense defense) {
        double incoming = positive(damage);
        double armor = clamp(defense.armor(), 0.0, 30.0);
        double toughness = clamp(defense.toughness(), 0.0, 20.0);
        double effectiveArmor = clamp(armor - incoming / (2.0 + toughness / 4.0), armor * 0.2, 20.0);
        double armorReduction = clamp(effectiveArmor / 25.0 - positive(defense.armorBypass()), 0.0, 1.0);
        double afterArmor = incoming * (1.0 - armorReduction);
        return positive(afterArmor * (1.0 - clamp(defense.protection(), 0.0, 20.0) / 25.0));
    }

    private static double positive(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : minimum;
    }

    /** Bounded UUID ledger; retaining an estimate never retains a game entity. */
    public static final class Ledger {
        private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

        public float health(UUID uuid, int entityId, long tick) {
            return entry(uuid, entityId, tick).health;
        }

        /**
         * Only an eligible entity-targeted outgoing attack reaches the model.
         * Repeated equal hooks cannot charge twice. In the first ten ticks,
         * only a stronger raw hit's excess can apply, including same-tick swaps.
         */
        public float attack(UUID uuid, int entityId, long tick, boolean eligible,
                            double outgoingDamage, Defense defense) {
            if (!eligible || positive(outgoingDamage) == 0.0) {
                return 0.0f;
            }
            Entry entry = entry(uuid, entityId, tick);
            double raw = positive(outgoingDamage);
            double accepted = raw;
            if (entry.windowStart != Long.MIN_VALUE && tick - entry.windowStart < HURT_WINDOW_TICKS) {
                accepted = Math.max(0.0, raw - entry.windowDamage);
                entry.windowDamage = Math.max(entry.windowDamage, raw);
            } else {
                entry.windowStart = tick;
                entry.windowDamage = raw;
            }
            float applied = (float) Math.min(entry.health, mitigate(accepted, defense));
            entry.health = Math.max(0.0f, entry.health - applied);
            return applied;
        }

        /** Remove departed entities without consulting their reported health/alive state. */
        public void retainPresent(BiPredicate<UUID, Integer> entityPresent) {
            entries.entrySet().removeIf(entry -> !entityPresent.test(entry.getKey(), entry.getValue().entityId));
        }

        public void clear() {
            entries.clear();
        }

        int size() {
            return entries.size();
        }

        private Entry entry(UUID uuid, int entityId, long tick) {
            entries.values().removeIf(entry -> tick < entry.lastSeenTick
                || tick - entry.lastSeenTick > FORGET_AFTER_TICKS);
            Entry result = entries.get(uuid);
            if (result == null || result.entityId != entityId) {
                result = new Entry(entityId, tick);
                entries.put(uuid, result);
            }
            result.lastSeenTick = tick;
            while (entries.size() > MAX_TARGETS) {
                Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
            return result;
        }
    }

    private static final class Entry {
        private final int entityId;
        private float health = BASELINE_HEALTH;
        private long lastSeenTick;
        private long windowStart = Long.MIN_VALUE;
        private double windowDamage;

        private Entry(int entityId, long tick) {
            this.entityId = entityId;
            this.lastSeenTick = tick;
        }
    }
}
