package dev.c4lam.celeste.mixin.module;

import com.llamalad7.mixinextras.sugar.Local;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogCompatibility;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Automatic Sodium/shared-data or vanilla/UBO path; never changes the NONE/GUI buffer. */
@Mixin(FogRenderer.class)
public abstract class CustomFogMixin {
    @Shadow private static boolean fogEnabled;

    // All environmental/render-distance fields are finalized here. This is before
    // either renderer reads them, unlike the UBO call where its float arguments
    // have already been loaded and Sodium independently snapshots the old locals.
    @Inject(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;", remap = false))
    private void celeste$sodiumAtmosphere(Camera camera, int viewDistance, RenderTickCounter tickCounter,
                                          float skyDarkness, ClientWorld world, CallbackInfoReturnable<Vector4f> callback,
                                          @Local FogData data, @Local Vector4f color) {
        if (!fogEnabled || !CustomFogCompatibility.usesSodium()) return;
        CustomFogMath.Fog adjusted = CustomFogRenderer.adjust(camera, world, color,
            data.environmentalStart, data.environmentalEnd, data.renderDistanceStart, data.renderDistanceEnd,
            data.skyEnd, data.cloudEnd);
        CustomFogCompatibility.applyShared(data, color, adjusted);
    }

    @ModifyArgs(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void celeste$customAtmosphere(Args args, Camera camera, int viewDistance, RenderTickCounter tickCounter,
                                         float skyDarkness, ClientWorld world) {
        // Sodium's path already applied the adjustment before BOTH consumers.
        // Applying it again here would double the tint and shorten fog twice.
        if (!fogEnabled || CustomFogCompatibility.usesSodium()) return;
        Vector4f color = args.get(2);
        CustomFogMath.Fog fog = CustomFogRenderer.adjust(camera, world, color, args.get(3), args.get(4),
            args.get(5), args.get(6), args.get(7), args.get(8));
        if (fog == null) return;
        // Vanilla returns this same vector for the clear/sky color after uploading
        // the buffer. Mutating it keeps the distant terrain and sky horizon matched.
        color.set((float) fog.color().r(), (float) fog.color().g(), (float) fog.color().b(), color.w);
        args.set(3, (float) fog.environmentalStart());
        args.set(4, (float) fog.environmentalEnd());
    }
}
