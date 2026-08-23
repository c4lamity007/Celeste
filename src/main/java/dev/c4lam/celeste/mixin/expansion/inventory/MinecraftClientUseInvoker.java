package dev.c4lam.celeste.mixin.expansion.inventory;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes exactly Minecraft's normal use-click path to held-use repeaters. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientUseInvoker {
    @Invoker("doItemUse")
    void celeste$invokeDoItemUse();
}
