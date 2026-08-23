package dev.c4lam.celeste.mixin.module;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("boundKey")
    InputUtil.Key celeste$getBoundKey();

    /** Read-only inspection; action arbitration must never consume this queue. */
    @Accessor("timesPressed")
    int celeste$getTimesPressed();
}
