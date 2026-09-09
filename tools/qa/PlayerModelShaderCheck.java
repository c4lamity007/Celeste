import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real player_model shaders, cached Minecraft includes and hidden GL readback; not a game test. */
public final class PlayerModelShaderCheck extends ImpactCheck {
    private static final int SIZE = 64, STRIDE = 28;
    private static final float[] CLEAR = {.1f, .2f, .3f, .4f};
    private static final float[] WHITE = {1, 1, 1, 1};
    private static final byte[] RGB = {(byte)128, (byte)192, (byte)64};
    private static final Map<String, String> hashes = new LinkedHashMap<>();
    private static final List<String> results = new ArrayList<>();
    private static int playerProgram, transformsBuffer, fogBuffer, materialBuffer, indicesBuffer, sampledTexture;
    private static int passed, failed;
    private static String gpu = "unavailable", glVersion = "unavailable";
    private record Readback(float[] rgba, float[] depth, float clearDepth) {
        float center(int channel) { return rgba[(SIZE / 2 * SIZE + SIZE / 2) * 4 + channel]; }
        int changed() {
            int count = 0;
            for (int p = 0; p < SIZE * SIZE; p++) {
                boolean different = false;
                for (int c = 0; c < 4; c++) different |= Math.abs(rgba[p * 4 + c] - CLEAR[c]) > 1e-5f;
                if (different) count++;
            }
            return count;
        }
    }
    @FunctionalInterface private interface Test { String run() throws Exception; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void near(float actual, float expected, String label) {
        require(Float.isFinite(actual) && Math.abs(actual - expected) < .00003f,
            label + " expected=" + expected + " actual=" + actual);
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void record(String name, Test test) {
        try {
            String detail = test.run();
            passed++;
            results.add("{\"name\":" + json(name) + ",\"status\":\"passed\",\"detail\":" + json(detail) + "}");
            System.out.println("PASS " + name + ": " + detail);
        } catch (Exception | AssertionError failure) {
            failed++;
            results.add("{\"name\":" + json(name) + ",\"status\":\"failed\",\"detail\":" + json(failure.toString()) + "}");
            System.out.println("FAIL " + name + ": " + failure);
        }
    }
    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    private static void report() throws Exception {
        List<String> sources = new ArrayList<>();
        hashes.forEach((name, hash) -> sources.add(json(name) + ":" + json(hash)));
        String data = "{\n  \"schema\":\"celeste-player-model-shader-qa-1\",\n  \"status\":"
            + json(failed == 0 ? "passed" : "failed")
            + ",\n  \"verification_kind\":\"offscreen-gpu-not-minecraft\",\n  \"in_game_verified\":false,\n"
            + "  \"gpu\":" + json(gpu) + ",\n  \"opengl\":" + json(glVersion)
            + ",\n  \"vertex_stride\":28,\n  \"index_type\":\"GL_UNSIGNED_INT\",\n"
            + "  \"indices\":[4,1,3],\n  \"passed\":" + passed + ",\n  \"failed\":" + failed
            + ",\n  \"source_sha256\":{" + String.join(",", sources) + "},\n  \"checks\":[\n    "
            + String.join(",\n    ", results) + "\n  ]\n}\n";
        Files.writeString(output.resolve("player-model-shader-report.json"), data, StandardOpenOption.CREATE_NEW);
    }
    private static int uniformOffset(String name, String block) {
        int index = glGetUniformIndices(playerProgram, name);
        if (index == GL_INVALID_INDEX) index = glGetUniformIndices(playerProgram, block + "." + name);
        require(index != GL_INVALID_INDEX, "Missing active uniform " + name);
        return glGetActiveUniformsi(playerProgram, index, GL_UNIFORM_OFFSET);
    }
    private static String compilePlayer() throws Exception {
        Path core = resources.resolve("assets/celeste/shaders/core");
        int[] shaders = new int[2];
        for (int i = 0; i < 2; i++) {
            String name = "player_model." + (i == 0 ? "vsh" : "fsh");
            byte[] source = Files.readAllBytes(core.resolve(name));
            hashes.put(name, digest(source));
            shaders[i] = shader(i == 0 ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER,
                preprocess(new String(source, java.nio.charset.StandardCharsets.UTF_8)));
        }
        for (String name : new String[] {"dynamictransforms.glsl", "projection.glsl", "fog.glsl"}) {
            String path = "assets/minecraft/shaders/include/" + name;
            try (var stream = minecraft.getInputStream(minecraft.getEntry(path))) {
                hashes.put(path, digest(stream.readAllBytes()));
            }
        }
        playerProgram = glCreateProgram();
        for (int shader : shaders) glAttachShader(playerProgram, shader);
        String[] attributes = {"Position", "UV0", "Color", "Normal"};
        for (int i = 0; i < attributes.length; i++) glBindAttribLocation(playerProgram, i, attributes[i]);
        glLinkProgram(playerProgram);
        require(glGetProgrami(playerProgram, GL_LINK_STATUS) != 0, glGetProgramInfoLog(playerProgram));
        for (int shader : shaders) glDeleteShader(shader);
        String[] blocks = {"DynamicTransforms", "Projection", "Fog", "ModelMaterial"};
        int[] minimum = {160, 64, 40, 16}, maximum = {160, 64, 48, 16};
        for (int i = 0; i < blocks.length; i++) {
            int block = glGetUniformBlockIndex(playerProgram, blocks[i]);
            require(block != GL_INVALID_INDEX, "Missing required block " + blocks[i]);
            glUniformBlockBinding(playerProgram, block, i);
            int size = glGetActiveUniformBlocki(playerProgram, block, GL_UNIFORM_BLOCK_DATA_SIZE);
            require(size >= minimum[i] && size <= maximum[i], "Unexpected " + blocks[i] + " size=" + size);
        }
        String[] names = {"ModelViewMat", "ColorModulator", "TextureMat", "ProjMat", "FogColor",
            "FogEnvironmentalStart", "FogEnvironmentalEnd", "FogRenderDistanceStart", "FogRenderDistanceEnd", "Params"};
        String[] owners = {blocks[0], blocks[0], blocks[0], blocks[1], blocks[2], blocks[2], blocks[2], blocks[2], blocks[2], blocks[3]};
        int[] offsets = {0, 64, 96, 0, 0, 16, 20, 24, 28, 0};
        for (int i = 0; i < names.length; i++) require(uniformOffset(names[i], owners[i]) == offsets[i], "Wrong UBO offset " + names[i]);
        glUseProgram(playerProgram);
        glUniform1i(glGetUniformLocation(playerProgram, "Sampler0"), 0);
        return "source shaders compile/link with actual MC1.21.11 imports; four UBO blocks and ten field offsets checked";
    }
    private static void setup() {
        transformsBuffer = buffer(0, BufferUtils.createByteBuffer(160));
        ByteBuffer projection = BufferUtils.createByteBuffer(64); matrix(projection, 0); buffer(1, projection);
        fogBuffer = buffer(2, BufferUtils.createByteBuffer(48));
        materialBuffer = buffer(3, BufferUtils.createByteBuffer(16));
        glBindVertexArray(glGenVertexArrays());
        vertexBuffer = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        // MC1.21.11 POSITION_TEXTURE_COLOR_NORMAL: float3 + float2 + unorm8x4 + snorm8x3 + padding.
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0L);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 12L);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, STRIDE, 20L);
        glVertexAttribPointer(3, 3, GL_BYTE, true, STRIDE, 24L);
        for (int i = 0; i < 4; i++) glEnableVertexAttribArray(i);
        indicesBuffer = glGenBuffers();
        frame = framebuffer(SIZE, SIZE, true);
        sampledTexture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, sampledTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glDisable(GL_DITHER); glDisable(GL_MULTISAMPLE); glDisable(GL_FRAMEBUFFER_SRGB);
    }
    private static Readback renderTriangle(int mode, float cutoff, float textureAlpha, int vertexAlpha,
            float[] modulator, int normalY, boolean blended, boolean depthWrite,
            float initialDepth, boolean fogged, boolean cull, boolean reverse) {
        return renderTriangle(mode, cutoff, textureAlpha, vertexAlpha, modulator, normalY, blended,
            depthWrite, initialDepth, fogged, cull, reverse, false);
    }
    private static Readback renderTriangle(int mode, float cutoff, float textureAlpha, int vertexAlpha,
            float[] modulator, int normalY, boolean blended, boolean depthWrite,
            float initialDepth, boolean fogged, boolean cull, boolean reverse, boolean mirrored) {
        ByteBuffer transforms = BufferUtils.createByteBuffer(160); matrix(transforms, 0); matrix(transforms, 96);
        if (mirrored) { transforms.putFloat(0, -1); transforms.putFloat(96, -1); }
        for (int i = 0; i < 4; i++) transforms.putFloat(64 + i * 4, modulator[i]);
        glBindBuffer(GL_UNIFORM_BUFFER, transformsBuffer); glBufferSubData(GL_UNIFORM_BUFFER, 0, transforms);
        ByteBuffer fog = BufferUtils.createByteBuffer(48);
        fog.putFloat(0, .3f).putFloat(4, .4f).putFloat(8, .5f).putFloat(12, 1);
        fog.putFloat(16, fogged ? 0 : 100).putFloat(20, fogged ? .1f : 200).putFloat(24, 100).putFloat(28, 200);
        glBindBuffer(GL_UNIFORM_BUFFER, fogBuffer); glBufferSubData(GL_UNIFORM_BUFFER, 0, fog);
        ByteBuffer material = BufferUtils.createByteBuffer(16); material.putFloat(0, cutoff).putFloat(4, mode);
        glBindBuffer(GL_UNIFORM_BUFFER, materialBuffer); glBufferSubData(GL_UNIFORM_BUFFER, 0, material);
        FloatBuffer texel = BufferUtils.createFloatBuffer(4); texel.put(new float[] {1, 1, 1, textureAlpha}).flip();
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sampledTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, texel);
        float[][] positions = {{-2, -2}, {-.75f, -.75f}, {-2, -2}, {.75f, -.75f}, {0, .75f}};
        ByteBuffer vertices = BufferUtils.createByteBuffer(positions.length * STRIDE);
        for (float[] position : positions) {
            vertices.putFloat(position[0]).putFloat(position[1]).putFloat(-.3f).putFloat(.5f).putFloat(.5f);
            vertices.put(RGB).put((byte)vertexAlpha).put((byte)0).put((byte)normalY).put((byte)0).put((byte)0);
        }
        vertices.flip(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer); glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        IntBuffer indices = BufferUtils.createIntBuffer(3); indices.put(reverse ? new int[] {4, 3, 1} : new int[] {4, 1, 3}).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indicesBuffer); glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STREAM_DRAW);
        glBindFramebuffer(GL_FRAMEBUFFER, frame); glViewport(0, 0, SIZE, SIZE);
        glDepthMask(true); glClearDepth(initialDepth); glClearColor(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3]);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(depthWrite);
        if (cull) { glEnable(GL_CULL_FACE); glCullFace(GL_BACK); glFrontFace(GL_CCW); } else glDisable(GL_CULL_FACE);
        if (blended) {
            glEnable(GL_BLEND); glBlendEquation(GL_FUNC_ADD);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else glDisable(GL_BLEND);
        glUseProgram(playerProgram); glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_INT, 0L);
        FloatBuffer rgba = BufferUtils.createFloatBuffer(SIZE * SIZE * 4), depth = BufferUtils.createFloatBuffer(SIZE * SIZE);
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_FLOAT, rgba);
        glReadPixels(0, 0, SIZE, SIZE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        require(glGetError() == GL_NO_ERROR, "OpenGL draw/readback error");
        float[] colors = new float[rgba.remaining()], depths = new float[depth.remaining()];
        rgba.get(colors); depth.get(depths);
        for (float value : colors) require(Float.isFinite(value) && value >= -.000001f && value <= 1.000001f, "Non-finite/unbounded RGBA " + value);
        for (float value : depths) require(Float.isFinite(value), "Non-finite depth");
        return new Readback(colors, depths, initialDepth);
    }
    private static Readback draw(int mode, float cutoff, float alpha, int vertexAlpha) {
        return renderTriangle(mode, cutoff, alpha, vertexAlpha, WHITE, 0, false, mode != 2, .8f, false, false, false);
    }
    private static void visible(Readback sample, float alpha, float diffuse) {
        require(sample.changed() >= 1000 && sample.changed() <= 1250, "Wrong indexed triangle coverage=" + sample.changed());
        for (int c = 0; c < 3; c++) near(sample.center(c), (RGB[c] & 255) / 255f * diffuse, "center RGB" + c);
        near(sample.center(3), alpha, "center alpha");
    }
    private static String discarded(Readback sample) {
        require(sample.changed() == 0, "Discard/depth-rejected triangle emitted " + sample.changed() + " pixels");
        for (float value : sample.depth) near(value, sample.clearDepth, "untouched depth");
        return "all 4096 color/depth pixels unchanged";
    }
    private static void tests() {
        record("indexed-opaque-zero-texture-alpha", () -> {
            Readback sample = draw(0, .5f, 0, 255); visible(sample, 1, .65f);
            near(sample.depth[SIZE / 2 * SIZE + SIZE / 2], .35f, "solid depth write");
            return "U32 indices [4,1,3], packed 28-byte vertices, coverage=" + sample.changed() + "; forced alpha=1; depth=.35";
        });
        record("opaque-ignores-vertex-alpha", () -> { visible(draw(0, .5f, .2f, 0), 1, .65f); return "vertex alpha=0 still opaque"; });
        record("mask-below-cutoff", () -> discarded(draw(1, .5f, .499f, 255)));
        record("mask-at-cutoff", () -> { visible(draw(1, .5f, .5f, 255), 1, .65f); return "alpha=.5 accepted and forced opaque"; });
        record("mask-zero-cutoff", () -> { visible(draw(1, 0, 0, 255), 1, .65f); return "glTF cutoff0 accepts zero alpha and forces opaque"; });
        record("mask-multiplies-packed-vertex-alpha", () -> {
            discarded(draw(1, .5f, 1, 127)); visible(draw(1, .5f, 1, 128), 1, .65f);
            return "127/255 discarded; 128/255 accepted";
        });
        record("mask-multiplies-color-modulator-alpha", () -> discarded(renderTriangle(1, .5f, .8f, 255,
            new float[] {1, 1, 1, .5f}, 0, false, true, .8f, false, false, false)));
        record("blend-raw-alpha-and-preserved-depth", () -> {
            float[] modulator = {1, 1, 1, .8f};
            Readback sample = renderTriangle(2, .5f, .5f, 128, modulator, 0, false, false, .8f, false, false, false);
            visible(sample, .5f * (128 / 255f) * .8f, .65f);
            for (float depth : sample.depth) near(depth, .8f, "BLEND depth write");
            return "texture*vertex*modulator alpha=" + sample.center(3) + "; depth unchanged";
        });
        record("blend-near-zero-discard", () -> discarded(draw(2, .5f, .0029f, 255)));
        record("blend-visible-above-discard", () -> { visible(draw(2, .5f, .004f, 255), .004f, .65f); return "alpha=.004 retained"; });
        record("translucent-pipeline-composition", () -> {
            Readback sample = renderTriangle(2, .5f, .5f, 255, WHITE, 0, true, false, .8f, false, false, false);
            for (int c = 0; c < 3; c++) near(sample.center(c), (RGB[c] & 255) / 255f * .65f * .5f + CLEAR[c] * .5f, "blended RGB" + c);
            near(sample.center(3), .5f + CLEAR[3] * .5f, "blended alpha");
            for (float depth : sample.depth) near(depth, .8f, "blended depth");
            return "SRC_ALPHA/ONE_MINUS_SRC_ALPHA RGB; ONE/ONE_MINUS_SRC_ALPHA alpha; no depth writes";
        });
        record("zero-and-opposite-normals-finite", () -> {
            visible(draw(0, .5f, 1, 255), 1, .65f);
            Readback up = renderTriangle(0, .5f, 1, 255, WHITE, 127, false, true, .8f, false, false, false);
            float diffuse = .65f + .35f * .85f / (float)Math.sqrt(.2f * .2f + .85f * .85f + .4f * .4f);
            visible(up, 1, diffuse);
            visible(renderTriangle(0, .5f, 1, 255, WHITE, -127, false, true, .8f, false, false, false), 1, .65f);
            return "zero normal finite at ambient=.65; +Y diffuse=" + diffuse + "; -Y finite ambient";
        });
        record("actual-minecraft-fog-preserves-alpha", () -> {
            Readback sample = renderTriangle(2, .5f, .25f, 255, WHITE, 0, false, false, .8f, true, false, false);
            for (int c = 0; c < 3; c++) near(sample.center(c), .3f + .1f * c, "fog RGB" + c);
            near(sample.center(3), .25f, "fog alpha");
            return "full environmental fog RGB=(.3,.4,.5), input alpha=.25 preserved";
        });
        record("scene-depth-rejects-occluded-triangle", () -> discarded(renderTriangle(0, .5f, 1, 255,
            WHITE, 0, false, true, .2f, false, false, false)));
        record("culling-and-double-sided-state", () -> {
            visible(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, true, false), 1, .65f);
            discarded(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, true, true));
            visible(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, false, true), 1, .65f);
            return "CCW visible, reversed culled, reversed visible when culling disabled";
        });
        record("negative-scale-winding-correction", () -> {
            discarded(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, true, false, true));
            visible(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, true, true, true), 1, .65f);
            visible(renderTriangle(0, .5f, 1, 255, WHITE, 0, false, true, .8f, false, false, false, true), 1, .65f);
            return "actual ModelView scaleX=-1: original indices culled; reversed indices visible; no-cull fallback visible";
        });
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Usage: resources minecraftJar freshOutputDirectory");
        resources = Path.of(args[0]); output = Path.of(args[2]);
        Files.createDirectories(output);
        require(!Files.exists(output.resolve("player-model-shader-report.json")), "Use a fresh output directory");
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try (ZipFile input = new ZipFile(args[1])) {
            minecraft = input;
            require(glfwInit(), "GLFW unavailable");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3); glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(SIZE, SIZE, "Player model offscreen GPU QA", 0, 0);
            require(window != 0, "OpenGL context unavailable");
            glfwMakeContextCurrent(window); GL.createCapabilities();
            gpu = glGetString(GL_RENDERER); glVersion = glGetString(GL_VERSION);
            System.out.println("GPU: " + gpu + "; OpenGL " + glVersion + "; OFFSCREEN, NOT MINECRAFT");
            record("actual-shader-driver-compile-and-ubo-layout", PlayerModelShaderCheck::compilePlayer);
            if (failed == 0) { setup(); tests(); }
            record("shader-inputs-unchanged-during-check", () -> {
                for (String name : new String[] {"player_model.vsh", "player_model.fsh"})
                    require(Objects.equals(hashes.get(name), digest(Files.readAllBytes(resources.resolve("assets/celeste/shaders/core/" + name)))), "Source changed during QA: " + name);
                return "compiled source hashes still match current shader files";
            });
        } catch (Exception | AssertionError failure) {
            failed++;
            results.add("{\"name\":\"harness\",\"status\":\"failed\",\"detail\":" + json(failure.toString()) + "}");
            System.out.println("FAIL harness: " + failure);
        } finally {
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate(); callback.free(); report();
        }
        System.out.println("RESULT passed=" + passed + " failed=" + failed + "; report=" + output.resolve("player-model-shader-report.json"));
        System.out.println("No Minecraft integration, game screenshot, renderer lifecycle or live scene was tested.");
        if (failed != 0) throw new AssertionError("Player-model shader GPU checks failed");
    }
}
