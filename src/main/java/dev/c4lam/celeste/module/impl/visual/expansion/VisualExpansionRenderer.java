package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.module.expansion.inventory.ReBuffNotifierFeature;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Safe extraction/draw bridge shared only by the visual expansion modules. */
public final class VisualExpansionRenderer {
    private static final RenderLayer XRAY_QUADS = RenderLayer.of(
        "celeste_visual_expansion_quads",
        RenderSetup.builder(RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_quads"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )).translucent().build()
    );
    private static final RenderLayer XRAY_LINES = RenderLayer.of(
        "celeste_visual_expansion_lines",
        RenderSetup.builder(RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_lines"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )).translucent().build()
    );
    private static final RenderStateDataKey<Frame> FRAME_KEY =
        RenderStateDataKey.create(() -> "celeste_visual_expansion");
    private static boolean initialized;

    private VisualExpansionRenderer() {
    }

    static synchronized void initialize(
        TrajectoriesFeature trajectories,
        OreEspExpansionFeature ores,
        ArrowEspFeature arrows,
        ItemEspFeature items,
        TargetEspFeature targets
    ) {
        if (initialized) return;
        initialized = true;
        WorldGlowRenderer.initialize();
        UnifiedChamsRenderer.initialize();

        WorldRenderEvents.END_EXTRACTION.register(context -> {
            if (!AuthBootstrap.authorized()) {
                return;
            }
            var worldState = context.worldState();
            if (worldState == null) {
                return;
            }

            List<WorldBox> boxes = new ArrayList<>();
            List<WorldLineStrip> lines = new ArrayList<>();
            List<WorldGlowRenderer.Quad> glows = new ArrayList<>();
            trajectories.extract(context, lines, boxes, glows);
            ores.extract(context, boxes, lines, glows);
            items.extract(context, boxes, lines, glows);
            targets.extract(context, boxes, lines, glows);
            PlayerEchoRenderer.Frame echoes = FakeLagVisualRenderer.extract(context, boxes, lines, glows);
            FakeGambleFeature.extract(context, boxes, lines);
            worldState.setData(
                FRAME_KEY,
                boxes.isEmpty() && lines.isEmpty() && glows.isEmpty() && echoes.echoes().isEmpty()
                    ? Frame.EMPTY
                    : new Frame(List.copyOf(boxes), List.copyOf(lines), List.copyOf(glows), echoes)
            );
        });
        // AFTER_ENTITIES runs inside the prepared draw pass; BEFORE_DEBUG_RENDER
        // can expose an unprepared or stale WorldRenderContext in Fabric 16.2.x.
        WorldRenderEvents.AFTER_ENTITIES.register(VisualExpansionRenderer::draw);
        HudElementRegistry.addLast(Identifier.of(CelesteClient.MOD_ID, "arrow_esp"), (context, tickCounter) -> {
            if (!AuthBootstrap.authorized()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            arrows.render(client, context.getScaledWindowWidth(), context.getScaledWindowHeight(), tickCounter.getTickProgress(false));
            ReBuffNotifierFeature.renderHud(client, context.getScaledWindowWidth(), context.getScaledWindowHeight());
            FakeGambleFeature.renderHud(client, context.getScaledWindowWidth(), context.getScaledWindowHeight());
        });
    }

    static int argb(float alpha, int rgb) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);
        return a << 24 | rgb & 0x00FFFFFF;
    }

    private static void draw(WorldRenderContext context) {
        if (!AuthBootstrap.authorized()) {
            return;
        }
        var worldState = context.worldState();
        MatrixStack matrices = context.matrices();
        var consumers = context.consumers();
        if (worldState == null || matrices == null || consumers == null
            || worldState.cameraRenderState == null || !worldState.cameraRenderState.initialized) {
            return;
        }

        Frame frame = worldState.getData(FRAME_KEY);
        if (frame == null || frame == Frame.EMPTY) return;
        Vec3d camera = worldState.cameraRenderState.pos;
        if (camera == null) return;

        // These custom layers use the provider's shared fallback allocator. Requesting
        // a different layer ends the current BufferBuilder, so finish every quad before
        // acquiring the line consumer (otherwise `fills` is no longer building).
        VertexConsumer fills = consumers.getBuffer(XRAY_QUADS);
        for (WorldBox box : frame.boxes()) {
            if ((box.fillColor() >>> 24) != 0) drawFill(matrices, fills, box.box(), camera, box.fillColor());
        }

        VertexConsumer lines = consumers.getBuffer(XRAY_LINES);
        for (WorldBox box : frame.boxes()) {
            if ((box.lineColor() >>> 24) != 0) drawOutline(matrices, lines, box.box(), camera, box.lineColor(), box.lineWidth());
        }
        for (WorldLineStrip strip : frame.lineStrips()) {
            drawLineStrip(matrices, lines, strip, camera);
        }
        WorldGlowRenderer.draw(context, frame.glows());
        UnifiedChamsRenderer.draw(context, frame.echoes(), true);
    }

    private static void drawLineStrip(MatrixStack matrices, VertexConsumer consumer, WorldLineStrip strip, Vec3d camera) {
        List<Vec3d> points = strip.points();
        MatrixStack.Entry entry = matrices.peek();
        for (int index = 1; index < points.size(); index++) {
            Vec3d from = points.get(index - 1);
            Vec3d to = points.get(index);
            Vec3d normal = to.subtract(from);
            double length = normal.length();
            if (length <= 1.0E-6D) continue;
            normal = normal.multiply(1.0D / length);
            line(
                consumer, entry,
                (float) (from.x - camera.x), (float) (from.y - camera.y), (float) (from.z - camera.z),
                (float) (to.x - camera.x), (float) (to.y - camera.y), (float) (to.z - camera.z),
                (float) normal.x, (float) normal.y, (float) normal.z,
                strip.color(), strip.width()
            );
        }
    }

    private static void drawFill(MatrixStack matrices, VertexConsumer consumer, Box box, Vec3d camera, int color) {
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);
        quad(consumer, entry, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, color);
        quad(consumer, entry, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, color);
        quad(consumer, entry, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, color);
        quad(consumer, entry, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, color);
        quad(consumer, entry, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, color);
        quad(consumer, entry, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, color);
    }

    private static void quad(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float ax, float ay, float az, float bx, float by, float bz,
        float cx, float cy, float cz, float dx, float dy, float dz, int color
    ) {
        consumer.vertex(entry, ax, ay, az).color(color);
        consumer.vertex(entry, bx, by, bz).color(color);
        consumer.vertex(entry, cx, cy, cz).color(color);
        consumer.vertex(entry, dx, dy, dz).color(color);
    }

    private static void drawOutline(MatrixStack matrices, VertexConsumer consumer, Box box, Vec3d camera, int color, float width) {
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);
        line(consumer, entry, x1, y1, z1, x2, y1, z1, 1, 0, 0, color, width);
        line(consumer, entry, x1, y2, z1, x2, y2, z1, 1, 0, 0, color, width);
        line(consumer, entry, x1, y1, z2, x2, y1, z2, 1, 0, 0, color, width);
        line(consumer, entry, x1, y2, z2, x2, y2, z2, 1, 0, 0, color, width);
        line(consumer, entry, x1, y1, z1, x1, y2, z1, 0, 1, 0, color, width);
        line(consumer, entry, x2, y1, z1, x2, y2, z1, 0, 1, 0, color, width);
        line(consumer, entry, x1, y1, z2, x1, y2, z2, 0, 1, 0, color, width);
        line(consumer, entry, x2, y1, z2, x2, y2, z2, 0, 1, 0, color, width);
        line(consumer, entry, x1, y1, z1, x1, y1, z2, 0, 0, 1, color, width);
        line(consumer, entry, x2, y1, z1, x2, y1, z2, 0, 0, 1, color, width);
        line(consumer, entry, x1, y2, z1, x1, y2, z2, 0, 0, 1, color, width);
        line(consumer, entry, x2, y2, z1, x2, y2, z2, 0, 0, 1, color, width);
    }

    private static void line(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float ax, float ay, float az, float bx, float by, float bz,
        float nx, float ny, float nz, int color, float width
    ) {
        consumer.vertex(entry, ax, ay, az).color(color).normal(entry, nx, ny, nz).lineWidth(width);
        consumer.vertex(entry, bx, by, bz).color(color).normal(entry, nx, ny, nz).lineWidth(width);
    }

    record WorldBox(Box box, int fillColor, int lineColor, float lineWidth) {
    }

    record WorldLineStrip(List<Vec3d> points, int color, float width) {
    }

    private record Frame(List<WorldBox> boxes, List<WorldLineStrip> lineStrips,
                         List<WorldGlowRenderer.Quad> glows, PlayerEchoRenderer.Frame echoes) {
        private static final Frame EMPTY = new Frame(List.of(), List.of(), List.of(), PlayerEchoRenderer.Frame.EMPTY);
    }
}
