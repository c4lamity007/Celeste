package dev.c4lam.celeste.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.DynamicUniformStorage;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.ProjectionMatrix2;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Small retained command batch for Celeste's custom GUI pipelines.
 *
 * <p>The matching {@code GameRendererMixin} flushes this batch immediately
 * after Minecraft's GUI renderer, keeping custom widgets inside the vanilla
 * GUI render phase.</p>
 */
public final class GuiRenderer implements AutoCloseable {
    private static final GuiRenderer INSTANCE = new GuiRenderer();
    private static final int INITIAL_ALLOCATOR_SIZE = 786_432;
    private static final int MIN_VERTEX_BUFFER_SIZE = 4_096;

    private final BufferAllocator allocator = new BufferAllocator(INITIAL_ALLOCATOR_SIZE);
    private final List<Command> commands = new ArrayList<>();
    private final Map<VertexFormat, VertexStorage> storages = new HashMap<>();
    private final Deque<ScissorBox> scissors = new ArrayDeque<>();
    private final MatrixStack matrices = new MatrixStack();

    private DynamicUniformStorage<GuiUniform> uniforms;
    private ProjectionMatrix2 projection;
    private boolean closed;

    private GuiRenderer() {
    }

    public static GuiRenderer getInstance() {
        return INSTANCE;
    }

    public MatrixStack matrices() {
        return matrices;
    }

    public BufferBuilder begin(RenderPipeline pipeline) {
        checkOpen();
        return new BufferBuilder(allocator, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
    }

    public void submit(BufferBuilder builder, RenderPipeline pipeline, TextureSetup texture, GuiUniform uniform) {
        checkOpen();
        BuiltBuffer built = builder.endNullable();
        if (built == null) {
            return;
        }

        commands.add(new Command(built, pipeline, texture, uniform, scissors.peek()));
    }

    public void submit(BufferBuilder builder, RenderPipeline pipeline, GuiUniform uniform) {
        submit(builder, pipeline, TextureSetup.empty(), uniform);
    }

    public GpuBufferSlice writeUniform(GuiUniform uniform) {
        checkOpen();
        return uniforms().write(uniform);
    }

    public void fullscreen(
            RenderPipeline pipeline,
            Framebuffer target,
            GpuTextureView source,
            GpuSampler sampler,
            GuiUniform uniform
    ) {
        checkOpen();
        GpuBufferSlice slice = writeUniform(uniform);

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Celeste post process",
                target.getColorAttachmentView(),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform(GuiPipelines.UBO, slice);
            pass.bindTexture("InSampler", source, sampler);
            pass.draw(0, 3);
        }
    }

    /**
     * Pushes a logical GUI-space scissor. Nested scissors are intersected so a
     * child clip can never escape its parent.
     */
    public void pushScissor(float x, float y, float width, float height) {
        checkOpen();
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();

        float left = Math.min(x, x + width);
        float right = Math.max(x, x + width);
        float top = Math.min(y, y + height);
        float bottom = Math.max(y, y + height);

        float scaleX = window.getScaledWidth() == 0
                ? 1.0f
                : (float) window.getFramebufferWidth() / window.getScaledWidth();
        float scaleY = window.getScaledHeight() == 0
                ? 1.0f
                : (float) window.getFramebufferHeight() / window.getScaledHeight();

        int pixelLeft = (int) Math.floor(left * scaleX);
        int pixelRight = (int) Math.ceil(right * scaleX);
        int pixelBottom = (int) Math.floor((window.getScaledHeight() - bottom) * scaleY);
        int pixelTop = (int) Math.ceil((window.getScaledHeight() - top) * scaleY);

        ScissorBox box = new ScissorBox(
                pixelLeft,
                pixelBottom,
                Math.max(0, pixelRight - pixelLeft),
                Math.max(0, pixelTop - pixelBottom)
        ).intersect(new ScissorBox(0, 0, window.getFramebufferWidth(), window.getFramebufferHeight()));

        ScissorBox parent = scissors.peek();
        scissors.push(parent == null ? box : parent.intersect(box));
    }

    public void popScissor() {
        if (!scissors.isEmpty()) {
            scissors.pop();
        }
    }

    public void clearScissors() {
        scissors.clear();
    }

    /** Flushes all queued GUI geometry into Minecraft's current framebuffer. */
    public void flush() {
        if (closed) {
            return;
        }

        if (commands.isEmpty()) {
            if (uniforms != null) {
                uniforms.clear();
            }
            allocator.clear();
            scissors.clear();
            return;
        }

        boolean projectionBackedUp = false;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            Window window = client.getWindow();
            Framebuffer framebuffer = client.getFramebuffer();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

            if (projection == null) {
                projection = new ProjectionMatrix2("celeste", 1_000.0f, 11_000.0f, true);
            }

            int[] baseVertices = uploadVertices(encoder);
            int maxIndices = 0;
            for (Command command : commands) {
                maxIndices = Math.max(maxIndices, command.built().getDrawParameters().indexCount());
            }

            RenderSystem.ShapeIndexBuffer shape = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
            GpuBuffer indexBuffer = shape.getIndexBuffer(maxIndices);
            VertexFormat.IndexType indexType = shape.getIndexType();

            RenderSystem.backupProjectionMatrix();
            projectionBackedUp = true;
            RenderSystem.setProjectionMatrix(
                    projection.set(
                            (float) window.getFramebufferWidth() / window.getScaleFactor(),
                            (float) window.getFramebufferHeight() / window.getScaleFactor()
                    ),
                    ProjectionType.ORTHOGRAPHIC
            );

            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                    new Matrix4f().setTranslation(0.0f, 0.0f, -11_000.0f),
                    new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                    new Vector3f(),
                    new Matrix4f()
            );

            GpuBufferSlice[] slices = new GpuBufferSlice[commands.size()];
            for (int i = 0; i < commands.size(); i++) {
                slices[i] = uniforms().write(commands.get(i).uniform());
            }

            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste GUI",
                    framebuffer.getColorAttachmentView(),
                    OptionalInt.empty(),
                    framebuffer.useDepthAttachment ? framebuffer.getDepthAttachmentView() : null,
                    OptionalDouble.empty()
            )) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);

                for (int i = 0; i < commands.size(); i++) {
                    Command command = commands.get(i);
                    pass.setPipeline(command.pipeline());
                    pass.setVertexBuffer(0, storages.get(command.pipeline().getVertexFormat()).buffer);
                    pass.setUniform(GuiPipelines.UBO, slices[i]);

                    ScissorBox scissor = command.scissor();
                    if (scissor == null) {
                        pass.disableScissor();
                    } else {
                        pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                    }

                    TextureSetup texture = command.texture();
                    if (texture.texure0() != null) {
                        pass.bindTexture("Sampler0", texture.texure0(), texture.sampler0());
                    }

                    pass.setIndexBuffer(indexBuffer, indexType);
                    pass.drawIndexed(baseVertices[i], 0, command.built().getDrawParameters().indexCount(), 1);
                }
            }
        } finally {
            if (projectionBackedUp) {
                RenderSystem.restoreProjectionMatrix();
            }
            discardCommands();
            if (uniforms != null) {
                uniforms.clear();
            }
            allocator.clear();
            scissors.clear();
        }
    }

    /** Releases all persistent GPU and native resources owned by this renderer. */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        discardCommands();
        scissors.clear();
        for (VertexStorage storage : storages.values()) {
            storage.close();
        }
        storages.clear();

        if (uniforms != null) {
            uniforms.close();
            uniforms = null;
        }
        allocator.close();
        closed = true;
    }

    private DynamicUniformStorage<GuiUniform> uniforms() {
        if (uniforms == null) {
            uniforms = new DynamicUniformStorage<>("Celeste GUI params", GuiUniform.SIZE, 512);
        }
        return uniforms;
    }

    private int[] uploadVertices(CommandEncoder encoder) {
        Map<VertexFormat, Integer> sizes = new HashMap<>();
        int[] offsets = new int[commands.size()];
        int[] baseVertices = new int[commands.size()];

        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            VertexFormat format = command.pipeline().getVertexFormat();
            int offset = sizes.getOrDefault(format, 0);

            offsets[i] = offset;
            baseVertices[i] = offset / format.getVertexSize();
            sizes.put(format, offset + command.built().getDrawParameters().vertexCount() * format.getVertexSize());
        }

        sizes.forEach((format, size) -> storages.computeIfAbsent(format, VertexStorage::new).ensureCapacity(size));

        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            VertexFormat format = command.pipeline().getVertexFormat();
            int size = command.built().getDrawParameters().vertexCount() * format.getVertexSize();
            encoder.writeToBuffer(storages.get(format).buffer.slice(offsets[i], size), command.built().getBuffer());
        }

        return baseVertices;
    }

    private void discardCommands() {
        for (Command command : commands) {
            command.built().close();
        }
        commands.clear();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Celeste GUI renderer has been closed");
        }
    }

    private static final class VertexStorage implements AutoCloseable {
        private final VertexFormat format;
        private GpuBuffer buffer;
        private int capacity;

        private VertexStorage(VertexFormat format) {
            this.format = format;
        }

        private void ensureCapacity(int size) {
            if (buffer != null && capacity >= size) {
                return;
            }
            if (buffer != null) {
                buffer.close();
            }

            capacity = Math.max(size, MIN_VERTEX_BUFFER_SIZE);
            buffer = RenderSystem.getDevice().createBuffer(
                    () -> "Celeste GUI vertices " + format,
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    capacity
            );
        }

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
                capacity = 0;
            }
        }
    }

    private record Command(
            BuiltBuffer built,
            RenderPipeline pipeline,
            TextureSetup texture,
            GuiUniform uniform,
            ScissorBox scissor
    ) {
    }

    private record ScissorBox(int x, int y, int width, int height) {
        private ScissorBox intersect(ScissorBox other) {
            int left = Math.max(x, other.x);
            int bottom = Math.max(y, other.y);
            int right = Math.min(x + width, other.x + other.width);
            int top = Math.min(y + height, other.y + other.height);
            return new ScissorBox(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
        }
    }
}
