package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.render.RenderUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;

/** Custom pointer / grab glyphs used by Celeste screens in place of the OS cursor. */
public final class GuiCursors {
    private static final Identifier POINTER = Identifier.of("celeste", "textures/gui/icons/cursor.png");
    private static final Identifier GRAB = Identifier.of("celeste", "textures/gui/icons/grab.png");
    private static final float POINTER_SIZE_PX = 26.0f;
    private static final float GRAB_SIZE_PX = 38.0f;
    private static final float POINTER_HOT_X = 14.0f / 64.0f;
    private static final float POINTER_HOT_Y = 3.0f / 64.0f;
    private static final float GRAB_HOT_X = 26.0f / 64.0f;
    private static final float GRAB_HOT_Y = 8.0f / 64.0f;

    private GuiCursors() {
    }

    public static void hideNative(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
    }

    public static void restoreNative(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }

    public static void draw(MatrixStack matrices, float mouseX, float mouseY, boolean grab) {
        MinecraftClient client = MinecraftClient.getInstance();
        float guiScale = client != null && client.getWindow() != null
            ? Math.max(1.0f, (float) client.getWindow().getScaleFactor())
            : 1.0f;
        float size = (grab ? GRAB_SIZE_PX : POINTER_SIZE_PX) / guiScale;
        float hotX = size * (grab ? GRAB_HOT_X : POINTER_HOT_X);
        float hotY = size * (grab ? GRAB_HOT_Y : POINTER_HOT_Y);
        RenderUtil.TEXTURE_RECT.draw(
            matrices,
            mouseX - hotX,
            mouseY - hotY,
            size,
            size,
            0,
            Color.WHITE,
            0,
            0,
            1,
            1,
            grab ? GRAB : POINTER
        );
    }
}
