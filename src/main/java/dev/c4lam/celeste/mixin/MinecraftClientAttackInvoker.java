package dev.c4lam.celeste.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Minecraft's ordinary attack click and miss-time gate. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAttackInvoker {
    @Invoker("doAttack")
    boolean celeste$invokeDoAttack();

    @Accessor("attackCooldown")
    int celeste$getAttackCooldown();
}
