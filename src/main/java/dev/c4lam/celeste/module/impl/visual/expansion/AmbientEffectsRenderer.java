package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Two cached depth-tested batches: closed convex 3D cores, then soft supporting bloom. */
public final class AmbientEffectsRenderer {
    public record Particle(Vec3d position, double radius, double yaw, double pitch, float opacity, int color) { }
    public record Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up) {
        public static final Frame EMPTY = new Frame(List.of(), AmbientField.Style.FIREFLIES, Vec3d.ZERO, Vec3d.ZERO);
    }
    private static final RenderLayer MESH = layer(false);
    private static final RenderLayer GLOW = layer(true);
    private static final int[][] FACES = {{0,3,2},{0,4,3},{0,5,4},{0,2,5},
        {1,2,3},{1,3,4},{1,4,5},{1,5,2}};
    private AmbientEffectsRenderer() { }
    public static void initialize() { }

    private static RenderLayer layer(boolean glow) {
        String name = glow ? "ambient_glow" : "ambient_mesh";
        RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/" + name))
            .withVertexShader(Identifier.of(CelesteClient.MOD_ID, "core/ambient_mesh"))
            .withFragmentShader(Identifier.of(CelesteClient.MOD_ID, "core/" + name))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL,
                glow ? VertexFormat.DrawMode.QUADS : VertexFormat.DrawMode.TRIANGLES)
            .withBlend(glow ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)
            .withCull(!glow).withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build());
        return RenderLayer.of("celeste_" + name, RenderSetup.builder(pipeline).translucent().expectedBufferSize(131072).build());
    }

    public static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame.particles().isEmpty() || context.matrices() == null || context.consumers() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer mesh = context.consumers().getBuffer(MESH);
        for (Particle p : frame.particles()) {
            double stretch = frame.style() == AmbientField.Style.CRYSTALS ? 2.2 : frame.style() == AmbientField.Style.SNOW ? .35 : 1;
            double radius = p.radius(), sy = Math.sin(p.yaw()), cy = Math.cos(p.yaw());
            double sp = Math.sin(p.pitch()), cp = Math.cos(p.pitch());
            Vec3d[] vertices = {new Vec3d(0, radius * stretch, 0), new Vec3d(0, -radius * stretch, 0),
                new Vec3d(radius,0,0), new Vec3d(0,0,radius), new Vec3d(-radius,0,0), new Vec3d(0,0,-radius)};
            for (int i = 0; i < vertices.length; i++) {
                Vec3d v = vertices[i];
                double y = v.y * cp - v.z * sp, z = v.y * sp + v.z * cp;
                vertices[i] = new Vec3d(v.x * cy - z * sy, y, v.x * sy + z * cy).add(p.position());
            }
            int color = VisualExpansionRenderer.argb(p.opacity() * .88F, p.color());
            for (int[] face : FACES) {
                Vec3d a = vertices[face[0]], b = vertices[face[1]], c = vertices[face[2]];
                Vec3d normal = b.subtract(a).crossProduct(c.subtract(a)).normalize();
                vertex(mesh, matrix, a, camera, 0, 0, normal, color);
                vertex(mesh, matrix, b, camera, 1, 0, normal, color);
                vertex(mesh, matrix, c, camera, .5F, 1, normal, color);
            }
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(MESH);
        if (frame.style() == AmbientField.Style.SNOW) return;
        VertexConsumer glow = context.consumers().getBuffer(GLOW);
        for (Particle p : frame.particles()) {
            double radius = p.radius() * (frame.style() == AmbientField.Style.CRYSTALS ? 2.8 : 5.5);
            Vec3d u = frame.right().multiply(radius), v = frame.up().multiply(radius), center = p.position();
            int color = VisualExpansionRenderer.argb(p.opacity() * (frame.style() == AmbientField.Style.CRYSTALS ? .15F : .48F), p.color());
            vertex(glow,matrix,center.subtract(u).subtract(v),camera,0,0,Vec3d.ZERO,color);
            vertex(glow,matrix,center.add(u).subtract(v),camera,1,0,Vec3d.ZERO,color);
            vertex(glow,matrix,center.add(u).add(v),camera,1,1,Vec3d.ZERO,color);
            vertex(glow,matrix,center.subtract(u).add(v),camera,0,1,Vec3d.ZERO,color);
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(GLOW);
    }

    private static void vertex(VertexConsumer consumer, MatrixStack.Entry matrix, Vec3d point, Vec3d camera,
                               float u, float v, Vec3d normal, int color) {
        consumer.vertex(matrix, (float)(point.x-camera.x), (float)(point.y-camera.y), (float)(point.z-camera.z))
            .texture(u,v).color(color).normal((float)normal.x,(float)normal.y,(float)normal.z);
    }
}
