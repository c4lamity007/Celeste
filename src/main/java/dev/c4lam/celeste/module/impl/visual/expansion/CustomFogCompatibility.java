package dev.c4lam.celeste.module.impl.visual.expansion;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.fog.FogData;
import org.joml.Vector4f;

/** Optional renderer detection; no Sodium classes are required to load Celeste. */
public final class CustomFogCompatibility {
    private static final boolean SODIUM_PRESENT = FabricLoader.getInstance().isModLoaded("sodium");

    private CustomFogCompatibility() { }

    public static boolean usesSodium() { return SODIUM_PRESENT; }

    /** Update the source of both Sodium's terrain snapshot and Minecraft's world fog upload. */
    public static void applyShared(FogData data, Vector4f color, CustomFogMath.Fog adjusted) {
        if (adjusted == null) return;
        data.environmentalStart = (float) adjusted.environmentalStart();
        data.environmentalEnd = (float) adjusted.environmentalEnd();
        color.set((float) adjusted.color().r(), (float) adjusted.color().g(), (float) adjusted.color().b(), color.w);
    }
}
