package dev.c4lam.celeste.module.impl.visual;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Fabric extraction/drawing bridge shared by Celeste world overlays. */
public final class VisualWorldRenderer {
    private static final RenderStateDataKey<Frame> FRAME_KEY =
        RenderStateDataKey.create(() -> "celeste_visual_boxes");
    private static boolean initialized;

    private VisualWorldRenderer() {
    }

    public static synchronized void initialize(EntityEspFeature entities, HoleEspFeature holes) {
        if (initialized) {
            return;
        }
        initialized = true;

        WorldRenderEvents.END_EXTRACTION.register(context -> {
            var worldState = context.worldState();
            if (worldState == null) {
                return;
            }

            List<WorldBox> boxes = new ArrayList<>();
            entities.extract(context, boxes);
            boxes.addAll(holes.renderBoxes());
            worldState.setData(FRAME_KEY, boxes.isEmpty() ? Frame.EMPTY : new Frame(List.copyOf(boxes)));
        });
        // Fabric 16.2.x invokes BEFORE_DEBUG_RENDER from the outer extraction method,
        // before WorldRenderContextImpl is prepared for the frame-graph draw pass.
        WorldRenderEvents.AFTER_ENTITIES.register(VisualWorldRenderer::draw);
    }

    static int argb(float alpha, int rgb) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);
        return a << 24 | rgb & 0x00FFFFFF;
    }

    private static void draw(WorldRenderContext context) {
        var worldState = context.worldState();
        MatrixStack matrices = context.matrices();
        var consumers = context.consumers();
        if (worldState == null || matrices == null || consumers == null
            || worldState.cameraRenderState == null || !worldState.cameraRenderState.initialized) {
            return;
        }

        Frame frame = worldState.getData(FRAME_KEY);
        if (frame == null || frame.boxes().isEmpty()) {
            return;
        }

        Vec3d camera = worldState.cameraRenderState.pos;
        if (camera == null) {
            return;
        }

        try {
            VertexConsumer fills = consumers.getBuffer(RenderLayers.debugQuads());
            VertexConsumer lines = consumers.getBuffer(RenderLayers.linesTranslucent());
            for (WorldBox box : frame.boxes()) {
                drawFill(matrices, fills, box.box(), camera, box.fillColor());
                drawOutline(matrices, lines, box.box(), camera, box.lineColor(), box.lineWidth());
            }
        } catch (Throwable t) {
            // Silently ignore rendering errors to prevent crashes
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

    private static void drawOutline(
        MatrixStack matrices, VertexConsumer consumer, Box box, Vec3d camera, int color, float width
    ) {
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

    public record WorldBox(Box box, int fillColor, int lineColor, float lineWidth) {
    }

    private record Frame(List<WorldBox> boxes) {
        private static final Frame EMPTY = new Frame(List.of());
    }
}
