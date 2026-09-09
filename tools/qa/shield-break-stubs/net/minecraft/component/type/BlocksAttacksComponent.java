package net.minecraft.component.type;
import java.util.Optional;
import java.util.function.DoublePredicate;
import net.minecraft.entity.damage.DamageSource;
/** Engine-result authority adapter; the fixture supplies the reduction decision. */
public final class BlocksAttacksComponent {
    public float scale = 1;
    public Optional<String> bypass = Optional.empty();
    public DoublePredicate accepts;
    public double queriedAngle = Double.NaN;
    public float queriedDamage;
    public DamageSource queriedSource;
    public int queries;
    public BlocksAttacksComponent(DoublePredicate accepts) { this.accepts = accepts; }
    public float disableCooldownScale() { return scale; }
    public Optional<String> bypassedBy() { return bypass; }
    public float getDamageReductionAmount(DamageSource source, float damage, double angle) {
        queries++;
        queriedSource = source;
        queriedDamage = damage;
        queriedAngle = angle;
        return accepts.test(angle) ? damage : 0;
    }
}
