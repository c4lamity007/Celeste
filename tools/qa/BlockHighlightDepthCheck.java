import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightMesh.Cuboid;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Actual block mesh/material/composite GPU test of private scene-depth copying.
 * The GL harness reproduces the renderer's attachment operations, not the live
 * Minecraft adapter. This is regression evidence, never an in-game screenshot.
 */
public final class BlockHighlightDepthCheck extends VolumeShowcase {
    private static final int SIZE = ImpactCheck.TILE;
    private static final int COLOR = 0x5980CAFF;
    private static final float ALPHA = 89f / 255;
    private static final float[] BACKGROUND = {.125f, .25f, .375f, .60f};
    private static int mainFbo, privateFbo, privateColor;
    private static long assertions, unchangedDepthPixels, visiblePixels, occludedPixels;

    static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static float[] read(int format, int channels) {
        FloatBuffer values = BufferUtils.createFloatBuffer(SIZE * SIZE * channels);
        glReadPixels(0, 0, SIZE, SIZE, format, GL_FLOAT, values);
        float[] result = new float[values.remaining()];
        values.get(result);
        return result;
    }

    private static void scene(boolean reversedDepth) {
        glBindFramebuffer(GL_FRAMEBUFFER, mainFbo);
        glViewport(0, 0, SIZE, SIZE);
        glDisable(GL_SCISSOR_TEST);
        glDepthMask(true);
        glClearColor(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], BACKGROUND[3]);
        glClearDepth(reversedDepth ? .2 : .85);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, 0, SIZE / 2, SIZE);
        glClearDepth(reversedDepth ? .85 : .2);
        glClear(GL_DEPTH_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
    }

    private static float[] draw(List<Face> faces, boolean copyDepth, boolean reverseFaces) {
        if (copyDepth) {
            // A depth-only GPU copy into the private target; neither target shares
            // the scene attachment. Equivalent to CommandEncoder's texture copy.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, mainFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, privateFbo);
            glBlitFramebuffer(0, 0, SIZE, SIZE, 0, 0, SIZE, SIZE, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, privateFbo);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glClearColor(0, 0, 0, 0);
        // The copied depth is deliberately retained. The non-occlusion control
        // uses the renderer's ordinary ESP path, which clears its private depth.
        glClearDepth(1);
        glClear(copyDepth ? GL_COLOR_BUFFER_BIT : GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        UnifiedVolumeCheck.drawSurface(faces, -1, reverseFaces, false);
        float[] surface = read(GL_RGBA, 4);
        glBindFramebuffer(GL_FRAMEBUFFER, mainFbo);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(volumeComposite);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, privateColor);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        require(glGetError() == GL_NO_ERROR, "Scene-depth surface/composite GL error");
        return surface;
    }

    private static void check(List<Cuboid> boxes, String label, boolean reversedDepth, boolean reverseFaces) {
        List<Face> local = BlockHighlightMesh.surface(boxes, 192);
        require(!local.isEmpty(), label + " production shape absent");
        List<Face> faces = BlockHighlightMesh.materialize(local, 0, 0, 0, COLOR, 192);
        scene(reversedDepth);
        float[] originalDepth = read(GL_DEPTH_COMPONENT, 1);
        float[] surface = draw(faces, true, reverseFaces);
        float[] composited = read(GL_RGBA, 4);
        float[] finalDepth = read(GL_DEPTH_COMPONENT, 1);
        int visible = 0, occluded = 0;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int pixel = y * SIZE + x, at = pixel * 4;
            require(Float.floatToIntBits(originalDepth[pixel]) == Float.floatToIntBits(finalDepth[pixel]),
                label + " mutated main terrain depth");
            double px = 2.0 * (x + .5) / SIZE - 1, py = 2.0 * (y + .5) / SIZE - 1;
            double front = Double.POSITIVE_INFINITY;
            // Raster ownership is half-open: include lower/left boundaries and
            // exclude upper/right boundaries. At odd sizes a pixel center can
            // land exactly on a shared stair edge; strict bounds invent a hole.
            for (Cuboid box : boxes) if (px >= box.minX() && px < box.maxX() && py >= box.minY() && py < box.maxY())
                front = Math.min(front, (box.minZ() + 1) * .5);
            boolean covered = Double.isFinite(front), shown = covered && front <= originalDepth[pixel];
            float expectedAlpha = shown ? ALPHA : 0;
            require(Math.abs(surface[at + 3] - expectedAlpha) < 2e-6,
                label + " wrong scene-depth coverage or stacked alpha at " + x + ", " + y);
            if (shown) visible++;
            else if (covered) occluded++;
            for (int channel = 0; channel < 4; channel++) {
                float value = surface[at + channel];
                require(Float.isFinite(value) && value >= 0 && value <= 1.000001f, label + " invalid shader output");
                if (!shown) require(value == 0, label + " stale/hidden surface color leaked");
                float expected = channel == 3 ? expectedAlpha + BACKGROUND[3] * (1 - expectedAlpha)
                    : value * expectedAlpha + BACKGROUND[channel] * (1 - expectedAlpha);
                require(Math.abs(composited[at + channel] - expected) < 2e-6,
                    label + " did not alpha-composite exactly once");
            }
        }
        require(visible > SIZE * SIZE / 25 && occluded > SIZE * SIZE / 25, label + " fixture missing visible/hidden control pixels");
        unchangedDepthPixels += finalDepth.length;
        visiblePixels += visible;
        occludedPixels += occluded;

        // Control proves this exact foreground fixture would shine through if the
        // renderer cleared its copied depth or accidentally used the ESP path.
        scene(reversedDepth);
        float[] unoccluded = draw(faces, false, reverseFaces);
        int regained = 0;
        for (int at = 3; at < surface.length; at += 4)
            if (surface[at] == 0 && unoccluded[at] > 0) regained++;
        require(regained == occluded, label + " no-depth-copy control failed to reveal exactly the hidden shape");

        // Empty following frame must not composite the previous private surface.
        // Clearing only private color after the new depth copy leaves no residue.
        scene(!reversedDepth);
        float[] emptySurface = draw(List.of(), true, false);
        for (float value : emptySurface) require(value == 0, label + " retained a stale block on an empty frame");
        float[] emptyComposite = read(GL_RGBA, 4);
        for (int at = 0; at < emptyComposite.length; at++)
            require(emptyComposite[at] == BACKGROUND[at % 4], label + " empty composite changed scene color");
    }

    public static void main(String[] args) throws Exception {
        resources = Path.of(args[0]);
        minecraft = new ZipFile(args[1]);
        output = Path.of(args[2]);
        changedResources = resources;
        Files.createDirectories(output);
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        require(glfwInit(), "GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(SIZE, SIZE, "Block highlight depth regression (hidden)", 0, 0);
        require(window != 0, "No GL context");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            volumeProgram = program("volume");
            volumeComposite = material("unified_volume_composite", null);
            prismTexture = texture("assets/celeste/textures/visual/plasma_flow.png");
            transformBuffer = buffer(0, BufferUtils.createByteBuffer(160));
            projectionBuffer = buffer(1, BufferUtils.createByteBuffer(64));
            globals = buffer(2, BufferUtils.createByteBuffer(64));
            UnifiedVolumeCheck.identityView();
            UnifiedVolumeCheck.materialTime(1.7f);
            glBindVertexArray(glGenVertexArrays());
            vertexBuffer = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            int[] sizes = {3, 2, 4, 3};
            int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                glEnableVertexAttribArray(i);
                glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, 48, offset);
                offset += sizes[i] * 4;
            }
            glDisable(GL_CULL_FACE);
            privateColor = attachment(false, SIZE, SIZE);
            privateFbo = fbo(privateColor, attachment(true, SIZE, SIZE));
            mainFbo = fbo(attachment(false, SIZE, SIZE), attachment(true, SIZE, SIZE));
            List<Cuboid> cube = List.of(new Cuboid(-.6, -.6, -.1, .6, .6, .7));
            List<Cuboid> slab = List.of(new Cuboid(-.6, -.6, -.1, .6, 0, .7));
            List<Cuboid> stair = List.of(new Cuboid(-.6, -.6, -.1, .6, 0, .7), new Cuboid(0, 0, -.1, .6, .6, .7));
            for (boolean reversed : new boolean[]{false, true}) for (boolean order : new boolean[]{false, true}) {
                check(cube, "Cube", reversed, order);
                check(slab, "Slab", reversed, order);
                check(stair, "Stairs", reversed, order);
            }
            require(glGetError() == GL_NO_ERROR, "Final GL error");
            System.out.println("PASS BlockHighlightDepth: " + assertions + " assertions; " + unchangedDepthPixels
                + " bit-identical main-depth pixels; " + visiblePixels + " visible and " + occludedPixels
                + " terrain-occluded shape pixels; production cube/slab/stairs + actual shader, depth-copy negative control,"
                + " alpha once, face order, reversed scene depth and empty-frame cleanup at " + SIZE + "x" + SIZE + ".");
            System.out.println("GPU regression only: reproduces attachment operations; live Minecraft adapter/missing-depth guards and game appearance require in-game QA.");
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
            callback.free();
            minecraft.close();
        }
    }
}
