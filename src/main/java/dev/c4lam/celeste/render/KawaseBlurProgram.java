package dev.c4lam.celeste.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.c4lam.celeste.config.InterfaceConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, on-demand Kawase capture used by frosted GUI panels.
 *
 * <p>No world or HUD mixin captures automatically. A screen that needs glass
 * calls {@link #capture()} after drawing its background and before queuing its
 * glass rectangles.</p>
 */
public final class KawaseBlurProgram implements AutoCloseable {
    private static final KawaseBlurProgram INSTANCE = new KawaseBlurProgram();

    private final List<SimpleFramebuffer> framebuffers = new ArrayList<>();
    private boolean captureAvailable;
    private boolean closed;

    private KawaseBlurProgram() {
    }

    public static KawaseBlurProgram getInstance() {
        return INSTANCE;
    }

    /** Captures the current framebuffer and applies the configured blur passes. */
    public boolean capture() {
        if (closed || !RenderSystemState.canCapture()) {
            captureAvailable = false;
            return false;
        }
        if (InterfaceConfig.glassy() <= 0.0f) {
            captureAvailable = false;
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer source = client.getFramebuffer();
        if (source == null || source.textureWidth <= 0 || source.textureHeight <= 0) {
            captureAvailable = false;
            return false;
        }

        ensureFramebuffers(source.textureWidth, source.textureHeight);
        int passes = InterfaceConfig.passes();
        if (framebuffers.size() != passes + 1) {
            captureAvailable = false;
            return false;
        }

        try {
            // Copy first, then build and collapse the Kawase pyramid. The
            // buffers remain full-size to keep GUI-space sampling exact.
            apply(GuiPipelines.BLUR_DOWNSCALE, source, framebuffers.get(0), 0);
            for (int pass = 0; pass < passes; pass++) {
                apply(GuiPipelines.BLUR_DOWNSCALE, framebuffers.get(pass), framebuffers.get(pass + 1), pass + 1);
            }
            for (int pass = passes; pass > 0; pass--) {
                apply(GuiPipelines.BLUR_UPSCALE, framebuffers.get(pass), framebuffers.get(pass - 1), pass);
            }

            captureAvailable = true;
            return true;
        } catch (RuntimeException exception) {
            captureAvailable = false;
            return false;
        }
    }

    public boolean isCaptureAvailable() {
        if (!captureAvailable || framebuffers.isEmpty()) {
            return false;
        }

        Framebuffer current = MinecraftClient.getInstance().getFramebuffer();
        SimpleFramebuffer captured = framebuffers.get(0);
        if (current == null
                || captured.textureWidth != current.textureWidth
                || captured.textureHeight != current.textureHeight) {
            captureAvailable = false;
            return false;
        }
        return true;
    }

    public Framebuffer capturedFramebuffer() {
        return isCaptureAvailable() ? framebuffers.get(0) : null;
    }

    /** Ensures every blur target exactly matches the active framebuffer. */
    public void ensureFramebuffers(int width, int height) {
        if (closed || width <= 0 || height <= 0) {
            return;
        }

        int passes = InterfaceConfig.passes();
        boolean matches = framebuffers.size() == passes + 1;
        if (matches) {
            for (SimpleFramebuffer framebuffer : framebuffers) {
                if (framebuffer.textureWidth != width || framebuffer.textureHeight != height) {
                    matches = false;
                    break;
                }
            }
        }
        if (matches) {
            return;
        }

        deleteFramebuffers();
        for (int i = 0; i <= passes; i++) {
            framebuffers.add(new SimpleFramebuffer("Celeste blur " + i, width, height, false));
        }
        captureAvailable = false;
    }

    /** Invalidates the current image without releasing reusable targets. */
    public void invalidate() {
        captureAvailable = false;
    }

    /** Releases blur FBOs; a later capture can lazily recreate them. */
    public void cleanup() {
        deleteFramebuffers();
        captureAvailable = false;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        cleanup();
        closed = true;
    }

    private void apply(RenderPipeline pipeline, Framebuffer source, Framebuffer destination, int pass) {
        GuiRenderer.getInstance().fullscreen(
                pipeline,
                destination,
                source.getColorAttachmentView(),
                GuiTextures.linear(),
                GuiUniform.of(
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.5f / source.textureWidth,
                        0.5f / source.textureHeight,
                        InterfaceConfig.offset() * (pass / (float) Math.max(1, InterfaceConfig.passes())),
                        0.0f
                )
        );
    }

    private void deleteFramebuffers() {
        for (SimpleFramebuffer framebuffer : framebuffers) {
            framebuffer.delete();
        }
        framebuffers.clear();
    }

    /** Keeps RenderSystem references isolated and easy to guard in tests. */
    private static final class RenderSystemState {
        private RenderSystemState() {
        }

        private static boolean canCapture() {
            return MinecraftClient.getInstance() != null && MinecraftClient.getInstance().getWindow() != null;
        }
    }
}
