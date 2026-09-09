package net.minecraft.entity.damage;
import net.minecraft.client.network.ClientPlayerEntity;
public final class DamageSources {
    public final DamageSource source = new DamageSource();
    public ClientPlayerEntity queriedAttacker;
    public DamageSource playerAttack(ClientPlayerEntity attacker) {
        queriedAttacker = attacker;
        return source;
    }
}
