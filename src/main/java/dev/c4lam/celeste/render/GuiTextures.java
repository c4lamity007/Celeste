package dev.c4lam.celeste.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.util.Identifier;

/** GPU texture helpers used by the custom GUI renderer. */
public final class GuiTextures {
    private GuiTextures() {
    }

    public static GpuTextureView view(Identifier texture) {
        return MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlTextureView();
    }

    public static GpuSampler linear() {
        return RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true);
    }

    /** Short alias used by textured GUI render calls. */
    public static GpuSampler sampler() {
        return linear();
    }

    public static GpuSampler nearest() {
        return RenderSystem.getSamplerCache().get(FilterMode.NEAREST, true);
    }
}
