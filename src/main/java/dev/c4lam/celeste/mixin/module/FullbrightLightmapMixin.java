package dev.c4lam.celeste.mixin.module;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.c4lam.celeste.module.impl.visual.FullbrightFeature;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LightmapTextureManager.class)
public abstract class FullbrightLightmapMixin {
    private static final String PUT_FLOAT =
        "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;";

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 3), index = 0)
    private float celeste$adjustNightVisionLightmap(float vanilla) {
        return FullbrightFeature.adjustNightVisionLightmap(vanilla);
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 6), index = 0)
    private float celeste$adjustGammaLightmap(float vanilla) {
        return FullbrightFeature.adjustGammaLightmap(vanilla);
    }
}
