package dev.c4lam.celeste.render;

import net.minecraft.client.util.math.MatrixStack;

/** Convenience wrapper around the renderer's intersecting scissor stack. */
public final class ScissorUtil {
    private ScissorUtil() {
    }

    public static void start(MatrixStack matrices, float x, float y, float width, float height) {
        matrices.push();
        GuiRenderer.getInstance().pushScissor(x, y, width, height);
    }

    public static void stop(MatrixStack matrices) {
        GuiRenderer.getInstance().popScissor();
        matrices.pop();
    }
}
