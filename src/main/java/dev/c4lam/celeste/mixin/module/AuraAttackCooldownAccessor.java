package dev.c4lam.celeste.mixin.module;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only elapsed local attack time; does not change cooldown or attacks. */
@Mixin(LivingEntity.class)
public interface AuraAttackCooldownAccessor {
    @Accessor("ticksSinceLastAttack")
    int celeste$auraTicksSinceLastAttack();
}
