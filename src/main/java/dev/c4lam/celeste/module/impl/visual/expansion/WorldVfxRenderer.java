package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Three cached, frame-animated GPU materials shared by all world VFX. */
public final class WorldVfxRenderer {
    private static final RenderLayer RIBBON = material("ribbon", false, "textures/visual/energy_ribbon.png");
    private static final RenderLayer PULSE = material("pulse", false, "textures/visual/fake_lag/halo.png");
    private static final RenderLayer ECHO = material("echo", true, "textures/visual/energy_ribbon.png");

    private WorldVfxRenderer() { }

    public static RenderLayer ribbonLayer() { return RIBBON; }
    public static RenderLayer pulseLayer() { return PULSE; }
    public static RenderLayer echoLayer() { return ECHO; }

    private static RenderLayer material(String name, boolean normals, String texture) {
        RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(
                RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/world_vfx_" + name))
            .withVertexShader(Identifier.of(CelesteClient.MOD_ID, "core/world_vfx"))
            .withFragmentShader(Identifier.of(CelesteClient.MOD_ID, "core/world_" + name))
            .withShaderDefine("ECHO_MESH", normals ? 1 : 0)
            .withSampler("Sampler0")
            .withVertexFormat(normals ? VertexFormats.POSITION_TEXTURE_COLOR_NORMAL
                : VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            // Emissive RGB with real alpha: no solid texture rectangles or white-out.
            .withBlend(BlendFunction.LIGHTNING)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withCull(false).build());
        return RenderLayer.of("celeste_world_vfx_" + name, RenderSetup.builder(pipeline)
            .texture("Sampler0", Identifier.of(CelesteClient.MOD_ID, texture),
                () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true))
            .translucent().expectedBufferSize(normals ? 262144 : 65536).build());
    }
}
