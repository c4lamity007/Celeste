package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only a successfully drawn, safe overworld sky; vanilla is otherwise untouched. */
@Mixin(SkyRendering.class)
public abstract class SkyboxMixin {
    @Unique private SkyboxRenderer.Frame celeste$skyboxFrame = SkyboxRenderer.Frame.EMPTY;
    @Unique private boolean celeste$skyboxDrawn;

    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void celeste$extractSkybox(ClientWorld world, float tickProgress, Camera camera,
                                        SkyRenderState state, CallbackInfo callback) {
        celeste$skyboxDrawn = false;
        celeste$skyboxFrame = SkyboxFeature.extract(world, camera, state);
    }

    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true)
    private void celeste$drawSkybox(int color, CallbackInfo callback) {
        celeste$skyboxDrawn = SkyboxRenderer.draw(celeste$skyboxFrame);
        if (celeste$skyboxDrawn) callback.cancel();
    }

    @Inject(method = "renderGlowingSky", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnHorizon(MatrixStack matrices, float angle, int color, CallbackInfo callback) {
        if (celeste$skyboxDrawn && SkyboxFeature.isCurrent(celeste$skyboxFrame)) callback.cancel();
    }

    @Inject(method = "renderCelestialBodies", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnCelestials(MatrixStack matrices, float sun, float moon, float stars,
                                              MoonPhase phase, float rain, float brightness, CallbackInfo callback) {
        if (celeste$skyboxDrawn && SkyboxFeature.isCurrent(celeste$skyboxFrame)) callback.cancel();
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnLowerHemisphere(CallbackInfo callback) {
        if (celeste$skyboxDrawn && SkyboxFeature.isCurrent(celeste$skyboxFrame)) callback.cancel();
    }
}
