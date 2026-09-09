import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import dev.c4lam.celeste.module.impl.visual.expansion.ConnectedRibbon;
import dev.c4lam.celeste.module.impl.visual.expansion.GlowTrail;
import dev.c4lam.celeste.module.impl.visual.expansion.TrajectoryAppearance;
import dev.c4lam.celeste.module.impl.visual.expansion.WorldGlowRenderer;
import net.minecraft.util.math.Vec3d;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual static trajectory shaders on a hidden GPU; deliberately not Minecraft integration QA. */
public final class TrajectoryMaterialCheck extends ImpactCheck {
    private static final float[] PINK = {1f, .31f, .64f, .9f};
    private static final float[] ICE = {.30f, .76f, 1f, .9f};
    private static final float[] SUNSET = {1f, .61f, .35f, .9f};
    private static final float[] MONO = {.82f, .85f, .90f, .9f};
    private static final float[] CUSTOM = {.35f, 1f, .41f, .9f};
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static Material pathMaterial, impactMaterial, spriteMaterial;
    private static int softTexture;
    private static int projectionUniform;
    private static double viewCameraZ;
    private static BufferedImage softPixels;
    private static int assertions;

    private static void require(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }

    // ImpactCheck.program requires animated Globals; these intentionally static materials do not.
    private static Material compileStatic(String name) throws Exception {
        Path core = resources.resolve("assets/celeste/shaders/core");
        int vertex = shader(GL_VERTEX_SHADER, preprocess(Files.readString(core.resolve("world_vfx.vsh")))
            .replace("#version 330", "#version 330\n#define ECHO_MESH 0"));
        int fragment = shader(GL_FRAGMENT_SHADER, preprocess(Files.readString(core.resolve("world_" + name + ".fsh"))));
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, "Position");
        glBindAttribLocation(program, 1, "UV0");
        glBindAttribLocation(program, 2, "Color");
        glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, name + ": " + glGetProgramInfoLog(program));
        String[] blocks = {"DynamicTransforms", "Projection", "Globals"};
        for (int binding = 0; binding < blocks.length; binding++) {
            int index = glGetUniformBlockIndex(program, blocks[binding]);
            if (index != GL_INVALID_INDEX) glUniformBlockBinding(program, index, binding);
        }
        glUseProgram(program);
        int sampler = glGetUniformLocation(program, "Sampler0");
        if (sampler >= 0) glUniform1i(sampler, 0);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        System.out.println("PASS actual driver compile/link: world_" + name + "; production vertex shader and Minecraft includes");
        return new Material(name, program, softTexture, "assets/celeste/textures/visual/fake_lag/soft.png");
    }

    /** SOFT uses this exact vanilla program in WorldGlowRenderer, not world_vfx. */
    private static Material compileSoftSprite() throws Exception {
        int[] stages = new int[2];
        String[] extensions = {"vsh", "fsh"};
        for (int i = 0; i < stages.length; i++) {
            var entry = minecraft.getEntry("assets/minecraft/shaders/core/position_tex_color." + extensions[i]);
            require(entry != null, "Missing exact vanilla SOFT shader stage");
            try (var input = minecraft.getInputStream(entry)) {
                stages[i] = shader(i == 0 ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER,
                    preprocess(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        int program = glCreateProgram();
        for (int stage : stages) glAttachShader(program, stage);
        glBindAttribLocation(program, 0, "Position");
        glBindAttribLocation(program, 1, "UV0");
        glBindAttribLocation(program, 2, "Color");
        glLinkProgram(program);
        require(glGetProgrami(program, GL_LINK_STATUS) != 0, "SOFT sprite: " + glGetProgramInfoLog(program));
        String[] blocks = {"DynamicTransforms", "Projection"};
        for (int binding = 0; binding < blocks.length; binding++) {
            int index = glGetUniformBlockIndex(program, blocks[binding]);
            require(index != GL_INVALID_INDEX, "SOFT sprite transform UBO is missing");
            glUniformBlockBinding(program, index, binding);
        }
        glUseProgram(program);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        for (int stage : stages) glDeleteShader(stage);
        System.out.println("PASS actual driver compile/link: SOFT uses Minecraft position_tex_color and cached soft.png");
        return new Material("soft", program, softTexture, "assets/celeste/textures/visual/fake_lag/soft.png");
    }

    private static void time(float seconds, int width, int height) {
        ByteBuffer values = BufferUtils.createByteBuffer(64);
        values.putFloat(32, width).putFloat(36, height).putFloat(44, seconds / 1200f);
        glBindBuffer(GL_UNIFORM_BUFFER, globals);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, values);
    }

    private static void quad(Material material, float[][] xy, float[][] uv, float[] color) {
        quad(material, xy, uv, color, color);
    }

    private static void quad(Material material, float[][] xy, float[][] uv, float[] color, float[] endColor) {
        FloatBuffer vertices = BufferUtils.createFloatBuffer(72);
        for (int corner : TRIANGLES) {
            vertices.put(xy[corner][0]).put(xy[corner][1]).put(-.3f)
                .put(uv[corner][0]).put(uv[corner][1]).put(corner == 1 || corner == 2 ? endColor : color)
                .put(0).put(0).put(1);
        }
        vertices.flip();
        glUseProgram(material.program());
        glBindTexture(GL_TEXTURE_2D, material.texture());
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private static void strip(float glow, float[] color, int segments) {
        float[][] uv = {{glow, 0}, {glow, 0}, {glow, 1}, {glow, 1}};
        for (int segment = 0; segment < segments; segment++) {
            float start = -1 + 2f * segment / segments, end = -1 + 2f * (segment + 1) / segments;
            quad(pathMaterial, new float[][] {{start, -1}, {end, -1}, {end, 1}, {start, 1}}, uv, color);
        }
    }

    private static void impact(float centerX, float centerY, float radiusX, float radiusY, float[] color) {
        quad(impactMaterial, new float[][] {
            {centerX - radiusX, centerY - radiusY}, {centerX + radiusX, centerY - radiusY},
            {centerX + radiusX, centerY + radiusY}, {centerX - radiusX, centerY + radiusY}
        }, new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}}, color);
    }

    private static float[] render(boolean impact, float glow, float[] color, float seconds, int segments) {
        glBindFramebuffer(GL_FRAMEBUFFER, frame);
        glViewport(0, 0, TILE, TILE);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(true);
        glClearDepth(.63);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDepthMask(false);
        time(seconds, TILE, TILE);
        if (impact) impact(0, 0, 1, 1, color); else strip(glow, color, segments);
        FloatBuffer read = BufferUtils.createFloatBuffer(TILE * TILE * 4);
        glReadPixels(0, 0, TILE, TILE, GL_RGBA, GL_FLOAT, read);
        float[] pixels = new float[read.remaining()];
        read.get(pixels);
        boolean bounded = true;
        for (float value : pixels) bounded &= Float.isFinite(value) && value >= -1e-6f && value <= 1.00001f;
        require(bounded, "Non-finite or unbounded " + (impact ? "impact" : "path") + " output");
        FloatBuffer depth = BufferUtils.createFloatBuffer(TILE * TILE);
        glReadPixels(0, 0, TILE, TILE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        boolean unchanged = true;
        for (int i = 0; i < depth.limit(); i++) unchanged &= Math.abs(depth.get(i) - .63f) < 1e-6f;
        require(unchanged, "Trajectory fixture altered depth");
        require(glGetError() == GL_NO_ERROR, "GL render/readback error");
        return pixels;
    }

    private static float alpha(float[] pixels, int x, int y) {
        return pixels[(y * TILE + x) * 4 + 3];
    }

    private static double difference(float[] first, float[] second) {
        double max = 0;
        for (int i = 0; i < first.length; i++) max = Math.max(max, Math.abs(first[i] - second[i]));
        return max;
    }

    private static double energy(float[] pixels, int channel) {
        double total = 0;
        for (int i = 0; i < pixels.length; i += 4) total += pixels[i + channel] * pixels[i + 3];
        return total;
    }

    private static void contract(boolean impact) {
        String name = impact ? "impact" : "path";
        float[] first = render(impact, .55f, ICE, 3f, 1);
        float[] fractional = render(impact, .55f, ICE, 3f + 1f / 60f, 1);
        float[] later = render(impact, .55f, ICE, 30.5f, 1);
        require(difference(first, fractional) < 1e-6, name + ": per-frame crawl");
        require(difference(first, later) < 1e-6, name + ": time-dependent material");
        int visible = 0, softened = 0;
        float edge = 0, maxAlpha = 0;
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            float a = alpha(first, x, y);
            if (a > .003f) visible++;
            if (a > .003f && a < .4f) softened++;
            if (y == 0 || y == TILE - 1 || (impact && (x == 0 || x == TILE - 1))) edge = Math.max(edge, a);
            maxAlpha = Math.max(maxAlpha, a);
        }
        require(visible > 30 && softened > 10, name + ": missing visible anti-aliased silhouette");
        require(maxAlpha > .1f, name + ": aiming core is too faint");
        require(edge < .015f, name + ": visible rectangular quad boundary: " + edge);
        float[] transparent = ICE.clone();
        transparent[3] = 0;
        float[] hidden = render(impact, .55f, transparent, 4f, 1);
        require(difference(hidden, new float[hidden.length]) == 0, name + ": zero-opacity output leak");
        float[] lowerAlpha = ICE.clone();
        lowerAlpha[3] = .2f;
        require(energy(render(impact, .55f, lowerAlpha, 3f, 1), 3) < energy(first, 3) * .5,
            name + ": ignores reduced opacity");
        if (impact) {
            require(alpha(first, TILE / 2, TILE / 2) > .05f, "Impact is missing exact-center dot");
            float ringPeak = 0;
            for (int x = TILE * 7 / 10; x < TILE * 19 / 20; x++)
                ringPeak = Math.max(ringPeak, alpha(first, x, TILE / 2));
            require(ringPeak > .3f && ringPeak > alpha(first, TILE * 13 / 20, TILE / 2) * 3,
                "Impact lacks a distinct ring separated from its center dot");
        }

        float[][] themes = {PINK, ICE, SUNSET, MONO, CUSTOM};
        float[][] themed = new float[themes.length][];
        for (int i = 0; i < themes.length; i++) themed[i] = render(impact, .55f, themes[i], 3f, 1);
        require(energy(themed[0], 0) > energy(themed[0], 1) * 1.1, name + ": pink theme lost");
        require(energy(themed[1], 2) > energy(themed[1], 0) * 1.1, name + ": ice theme lost");
        require(energy(themed[2], 0) > energy(themed[2], 2) * 1.1, name + ": sunset theme lost");
        require(energy(themed[3], 2) < energy(themed[3], 0) * 1.2, name + ": mono theme acquired a strong color cast");
        require(energy(themed[4], 1) > energy(themed[4], 0) * 1.1, name + ": custom color lost");
        System.out.printf(Locale.ROOT,
            "PASS static %s: visible=%d soft=%d maxAlpha=%.4f edgeAlpha=%.6f; no frame crawl; five tints; zero-opacity transparent; depth preserved%n",
            name, visible, softened, maxAlpha, edge);
    }

    private static void glowAndSeams() {
        float[] zero = render(false, 0, PINK, 3, 1);
        float[] full = render(false, 1, PINK, 3, 1);
        require(alpha(zero, TILE / 2, TILE / 2) > .15f, "Glow=0 removed readable core");
        int zeroWidth = 0, fullWidth = 0;
        double zeroHalo = 0, fullHalo = 0;
        for (int y = 0; y < TILE; y++) {
            float a = alpha(zero, TILE / 2, y), b = alpha(full, TILE / 2, y);
            if (a > .01f) zeroWidth++;
            if (b > .01f) fullWidth++;
            if (Math.abs((y + .5) / TILE - .5) > .2) { zeroHalo += a; fullHalo += b; }
        }
        require(fullWidth > zeroWidth + 4, "Glow=1 did not widen the visible halo");
        require(fullHalo > zeroHalo + .1, "Glow=1 added no outer halo energy");
        double maxSeamDifference = 0;
        for (float glow : new float[] {0, .55f, 1}) {
            float[] single = render(false, glow, ICE, 3, 1);
            float[] split = render(false, glow, ICE, 3, 16);
            double difference = difference(single, split);
            maxSeamDifference = Math.max(maxSeamDifference, difference);
            require(difference < .0002, "Subdividing path into 16 quads introduced a shader seam: " + difference);
        }
        System.out.printf(Locale.ROOT,
            "PASS glow/joins: Glow=0 core alpha=%.4f; visible width %d -> %d; 16-segment seam maxRGBA=%.8f%n",
            alpha(zero, TILE / 2, TILE / 2), zeroWidth, fullWidth, maxSeamDifference);
    }

    private static List<Vec3d> arc(double scale, boolean wall) {
        List<Vec3d> points = new ArrayList<>();
        for (int i = 0; i <= 96; i++) {
            double t = i / 96D;
            points.add(new Vec3d((-.84 + 1.36 * t) * scale,
                (-.58 + 3.1 * t * (1 - t) + (wall ? .65 : .14) * t) * scale, 0));
        }
        return List.copyOf(points);
    }

    private static int rgb(float[] color) {
        return Math.round(color[0] * 255) << 16 | Math.round(color[1] * 255) << 8 | Math.round(color[2] * 255);
    }

    private static float[] rgba(int color) {
        return new float[] {((color >>> 16) & 255) / 255F, ((color >>> 8) & 255) / 255F,
            (color & 255) / 255F, (color >>> 24) / 255F};
    }

    private static List<WorldGlowRenderer.Quad> stamps(List<Vec3d> points, Vec3d camera,
                                                      int budget, double glow, double opacity, float[] color) {
        List<WorldGlowRenderer.Quad> output = new ArrayList<>();
        List<Vec3d> original = List.copyOf(points);
        int count = GlowTrail.append(output, points, camera, budget, glow, opacity, false, progress -> rgb(color));
        require(count == output.size() && count <= budget && count <= GlowTrail.MAX_SPRITES,
            "Production GlowTrail exceeded its sprite budget");
        require(points.equals(original), "GlowTrail changed its input path");
        for (var sprite : output) {
            require(sprite.texture() == WorldGlowRenderer.Texture.SOFT, "GlowTrail emitted a non-SOFT submission");
            require(sprite.uStart() == 0 && sprite.uEnd() == 1, "GlowTrail does not use the full soft-image UVs");
            for (Vec3d point : List.of(sprite.a(), sprite.b(), sprite.c(), sprite.d())) {
                require(Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z),
                    "GlowTrail emitted a non-finite sprite corner");
            }
        }
        return output;
    }

    /** Preserve camera-facing depth so the GPU performs actual perspective-correct interpolation. */
    private static void submission(WorldGlowRenderer.Quad value) {
        Vec3d[] corners = {value.a(), value.b(), value.c(), value.d()};
        float[][] uv = {{value.uStart(), 0}, {value.uEnd(), 0}, {value.uEnd(), 1}, {value.uStart(), 1}};
        Material material = value.texture() == WorldGlowRenderer.Texture.SOFT ? spriteMaterial : pathMaterial;
        FloatBuffer vertices = BufferUtils.createFloatBuffer(72);
        for (int corner : TRIANGLES) {
            Vec3d position = corners[corner];
            vertices.put((float) position.x).put((float) position.y).put((float) (position.z - viewCameraZ))
                .put(uv[corner][0]).put(uv[corner][1]).put(rgba(corner == 1 || corner == 2 ? value.endColor() : value.color()))
                .put(0).put(0).put(1);
        }
        vertices.flip();
        glUseProgram(material.program());
        glBindTexture(GL_TEXTURE_2D, material.texture());
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private static void perspective(double scale, double cameraZ) {
        viewCameraZ = cameraZ;
        ByteBuffer projection = BufferUtils.createByteBuffer(64);
        projection.putFloat(0, (float) (cameraZ / scale)).putFloat(20, (float) (cameraZ / scale));
        projection.putFloat(40, -1.00020002f).putFloat(44, -1).putFloat(56, -.200020002f);
        glBindBuffer(GL_UNIFORM_BUFFER, projectionUniform);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, projection);
    }

    private static void identityProjection() {
        ByteBuffer projection = BufferUtils.createByteBuffer(64);
        matrix(projection, 0);
        glBindBuffer(GL_UNIFORM_BUFFER, projectionUniform);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, projection);
    }

    private static void beginSprites(float seconds) {
        glBindFramebuffer(GL_FRAMEBUFFER, frame);
        glViewport(0, 0, TILE, TILE);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(true);
        glClearDepth(.63);
        glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDepthMask(false);
        glEnable(GL_BLEND);
        // Exact BlendFunction.LIGHTNING factors used by WorldGlowRenderer.
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_SRC_ALPHA, GL_ONE);
        time(seconds, TILE, TILE);
    }

    private static float[] spriteReadback() {
        FloatBuffer read = BufferUtils.createFloatBuffer(TILE * TILE * 4);
        glReadPixels(0, 0, TILE, TILE, GL_RGBA, GL_FLOAT, read);
        float[] pixels = new float[read.remaining()];
        read.get(pixels);
        boolean finite = true;
        for (float value : pixels) finite &= Float.isFinite(value) && value >= -1e-6 && value <= 8;
        require(finite, "Additive SOFT output was non-finite or explosively bright");
        FloatBuffer depth = BufferUtils.createFloatBuffer(TILE * TILE);
        glReadPixels(0, 0, TILE, TILE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        boolean unchanged = true;
        for (int i = 0; i < depth.limit(); i++) unchanged &= Math.abs(depth.get(i) - .63f) < 1e-6f;
        require(unchanged, "Production SOFT image fixture altered depth");
        require(glGetError() == GL_NO_ERROR, "SOFT render/readback error");
        return pixels;
    }

    private static float[] renderStamps(List<WorldGlowRenderer.Quad> sprites, double scale, float seconds) {
        beginSprites(seconds);
        perspective(scale, 8);
        for (var sprite : sprites) submission(sprite);
        return spriteReadback();
    }

    private static double light(float[] pixels) {
        // RGB already includes SRC_ALPHA from the actual additive blend. Do not apply alpha twice.
        double total = 0;
        for (int i = 0; i < pixels.length; i += 4) total += pixels[i] * .2126 + pixels[i + 1] * .7152 + pixels[i + 2] * .0722;
        return total;
    }

    private static void spriteEdges(WorldGlowRenderer.Quad sprite) {
        // View one actual emitted stamp face-on, preserving its UVs/color while filling the viewport.
        Vec3d center = sprite.a().lerp(sprite.c(), .5);
        Vec3d along = sprite.b().subtract(sprite.a()), across = sprite.d().subtract(sprite.a());
        double halfLength = Math.sqrt(along.lengthSquared()) * .5, halfWidth = Math.sqrt(across.lengthSquared()) * .5;
        along = along.normalize(); across = across.normalize();
        float[][] xy = new float[4][2];
        Vec3d[] corners = {sprite.a(), sprite.b(), sprite.c(), sprite.d()};
        for (int i = 0; i < corners.length; i++) {
            Vec3d relative = corners[i].subtract(center);
            xy[i][0] = (float) (relative.dotProduct(along) / halfLength);
            xy[i][1] = (float) (relative.dotProduct(across) / halfWidth);
        }
        beginSprites(3);
        identityProjection();
        quad(spriteMaterial, xy, new float[][] {{sprite.uStart(), 0}, {sprite.uEnd(), 0},
            {sprite.uEnd(), 1}, {sprite.uStart(), 1}}, rgba(sprite.color()), rgba(sprite.endColor()));
        float[] pixels = spriteReadback();
        float peak = 0, edge = 0;
        int partial = 0;
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            int i = (y * TILE + x) * 4;
            float value = Math.max(pixels[i], Math.max(pixels[i + 1], pixels[i + 2]));
            peak = Math.max(peak, value);
            if (x < 2 || y < 2 || x >= TILE - 2 || y >= TILE - 2) edge = Math.max(edge, value);
            if (value > .001 && value < .08) partial++;
        }
        require(peak > .02, "Cached soft image produced no visible halo");
        require(edge < peak * .015 + .0001, "Visible hard rectangle around a production soft-image stamp: " + edge);
        require(partial > TILE * TILE / 10, "SOFT stamp has no broad gradual image falloff");
        System.out.printf(Locale.ROOT, "PASS production image edge: peakRGB=%.6f edgeRGB=%.8f gradualPixels=%d%n", peak, edge, partial);
    }

    private static void productionGlowStamps() {
        double scale = 4;
        List<Vec3d> points = arc(scale, false);
        Vec3d camera = new Vec3d(0, 0, 8);
        var low = stamps(points, camera, 32, .8, .9, ICE);
        var high = stamps(points, camera, 64, .8, .9, ICE);
        require(low.size() == 32 && high.size() == 64, "Density fixture did not exercise distinct 32/64 stamp counts");
        float[] lowImage = renderStamps(low, scale, 3);
        float[] highImage = renderStamps(high, scale, 3);
        double lowLight = light(lowImage), highLight = light(highImage);
        require(lowLight > 5 && highLight > 5, "Repeated production stamps have no visible image contribution");
        double densityChange = Math.abs(highLight - lowLight) / Math.max(lowLight, highLight);
        require(densityChange < .15, "Changing 32 to 64 stamps changes integrated brightness by " + densityChange);
        require(difference(highImage, renderStamps(high, scale, 31.5f)) < 1e-6,
            "SOFT image stamps crawl as frame time advances");
        var zeroGlow = stamps(points, camera, 64, 0, .9, ICE);
        var zeroOpacity = stamps(points, camera, 64, .8, 0, ICE);
        require(zeroGlow.isEmpty() && zeroOpacity.isEmpty(), "Glow/opacity zero still emits image stamps");
        require(light(renderStamps(zeroGlow, scale, 3)) == 0 && light(renderStamps(zeroOpacity, scale, 3)) == 0,
            "Glow/opacity zero leaves GPU image output");
        spriteEdges(high.get(high.size() / 2));
        System.out.printf(Locale.ROOT,
            "PASS production GlowTrail: 32/64 stamps RGB energy %.5f / %.5f (%.2f%% difference); zero-glow absent; static; finite; depth preserved%n",
            lowLight, highLight, densityChange * 100);
    }

    /** Bilinear RGBA sampling of the same PNG and SRC_ALPHA contribution used by the SOFT program. */
    private static double textureLight(double u, double v) {
        double pixelX = u * softPixels.getWidth() - .5, pixelY = v * softPixels.getHeight() - .5;
        int x = (int) Math.floor(pixelX), y = (int) Math.floor(pixelY);
        double fractionX = pixelX - x, fractionY = pixelY - y;
        double red = 0, green = 0, blue = 0, alpha = 0;
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            int rgba = softPixels.getRGB(Math.max(0, Math.min(softPixels.getWidth() - 1, x + dx)),
                Math.max(0, Math.min(softPixels.getHeight() - 1, y + dy)));
            double weight = (dx == 0 ? 1 - fractionX : fractionX) * (dy == 0 ? 1 - fractionY : fractionY);
            red += ((rgba >>> 16) & 255) / 255D * weight;
            green += ((rgba >>> 8) & 255) / 255D * weight;
            blue += (rgba & 255) / 255D * weight;
            alpha += (rgba >>> 24) / 255D * weight;
        }
        return (red * .2126 + green * .7152 + blue * .0722) * alpha;
    }

    private record RayStamp(Vec3d start, Vec3d along, Vec3d across, Vec3d normal,
                            double alongSquared, double acrossSquared, double alpha) {
        RayStamp(WorldGlowRenderer.Quad quad) {
            this(quad.a(), quad.b().subtract(quad.a()), quad.d().subtract(quad.a()),
                quad.b().subtract(quad.a()).crossProduct(quad.d().subtract(quad.a())).normalize(),
                quad.b().squaredDistanceTo(quad.a()), quad.d().squaredDistanceTo(quad.a()),
                (quad.color() >>> 24) / 255D);
        }
    }

    private static double rayLight(List<RayStamp> stamps, Vec3d camera, double screenX) {
        Vec3d ray = new Vec3d(screenX, 0, -1);
        double total = 0;
        for (RayStamp stamp : stamps) {
            double denominator = ray.dotProduct(stamp.normal());
            if (Math.abs(denominator) < 1e-12) continue;
            double time = stamp.start().subtract(camera).dotProduct(stamp.normal()) / denominator;
            if (time <= 0) continue;
            Vec3d relative = camera.add(ray.multiply(time)).subtract(stamp.start());
            double u = relative.dotProduct(stamp.along()) / stamp.alongSquared();
            double v = relative.dotProduct(stamp.across()) / stamp.acrossSquared();
            if (u >= 0 && u <= 1 && v >= 0 && v <= 1) total += textureLight(u, v) * stamp.alpha();
        }
        return total;
    }

    private static List<Vec3d> straight(double length) {
        List<Vec3d> points = new ArrayList<>();
        int segments = (int) Math.ceil(length / 6);
        for (int i = 0; i <= segments; i++) points.add(new Vec3d(-length * .5 + length * i / segments, 0, 0));
        return List.copyOf(points);
    }

    private static void localStampRipple(List<Vec3d> points, Vec3d camera, int count) {
        var quads = stamps(points, camera, count, 1, .9, new float[] {1, 1, 1, .9f});
        require(quads.size() == count, "Local-ripple fixture did not receive the complete intended stamp density");
        List<RayStamp> samples = quads.stream().map(RayStamp::new).toList();
        double maximumCenterDifference = 0, maximumRange = 0;
        for (int i = count / 4; i < count * 3 / 4; i++) {
            double first = quads.get(i).a().lerp(quads.get(i).c(), .5).x / camera.z;
            double last = quads.get(i + 1).a().lerp(quads.get(i + 1).c(), .5).x / camera.z;
            double centerAverage = (rayLight(samples, camera, first) + rayLight(samples, camera, last)) * .5;
            double between = rayLight(samples, camera, (first + last) * .5);
            require(centerAverage > .05 && between > .05, "Stamp ripple check cannot pass by suppressing the visible halo");
            maximumCenterDifference = Math.max(maximumCenterDifference,
                Math.abs(centerAverage - between) / ((centerAverage + between) * .5));
            double low = Double.POSITIVE_INFINITY, high = 0, sum = 0;
            for (int phase = 0; phase <= 32; phase++) {
                double value = rayLight(samples, camera, first + (last - first) * phase / 32);
                low = Math.min(low, value); high = Math.max(high, value); sum += value;
            }
            maximumRange = Math.max(maximumRange, (high - low) / (sum / 33));
        }
        require(maximumCenterDifference < .05, "SOFT stamps form bright beads at centers/midpoints: " + maximumCenterDifference);
        require(maximumRange < .05, "SOFT stamp local brightness oscillates between sample phases: " + maximumRange);
        System.out.printf(Locale.ROOT,
            "PASS local image ripple: length=%.0f stamps=%d center/midpoint=%.3f%% full-period=%.3f%%; exact PNG bilinear samples and perspective ray/quad mapping%n",
            points.getFirst().distanceTo(points.getLast()), count, maximumCenterDifference * 100, maximumRange * 100);
    }

    private static void imageRippleContract() {
        localStampRipple(straight(12), new Vec3d(0, 0, 8), 32);
        localStampRipple(straight(12), new Vec3d(0, 0, 8), 64);
        localStampRipple(straight(192), new Vec3d(0, 0, 128), 64);
        var sparse = stamps(straight(192), new Vec3d(0, 0, 128), 32, 1, .9, ICE);
        require(sparse.isEmpty(), "Insufficient long-path stamp budget must fall back rather than draw sparse beads");
        require(light(renderStamps(sparse, 4, 3)) == 0, "Sparse-budget fallback left image glow on the GPU");
    }

    private static void curve(float glow, boolean wall, float[] color) {
        double scale = 3;
        List<Vec3d> points = arc(scale, wall);
        Vec3d camera = new Vec3d(0, 0, 7);
        var sprites = stamps(points, camera, 64, glow, color[3], color);
        perspective(scale, camera.z);
        for (var sprite : sprites) submission(sprite);
        List<ConnectedRibbon.Node> nodes = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double progress = i / (double) (points.size() - 1);
            var style = TrajectoryAppearance.sample(points.get(i).distanceTo(camera), progress, color[3], true);
            nodes.add(new ConnectedRibbon.Node(points.get(i), style.width(),
                (int) Math.round(style.alpha() * 255) << 24 | rgb(color)));
        }
        List<WorldGlowRenderer.Quad> ribbon = new ArrayList<>();
        ConnectedRibbon.append(ribbon, nodes, camera, null, 128, false, WorldGlowRenderer.Texture.TRAJECTORY, glow);
        for (var segment : ribbon) submission(segment);
    }

    private static void preview() throws Exception {
        int panelWidth = 380, sceneHeight = 340, width = panelWidth * 3;
        framebuffer(width, sceneHeight, false);
        glViewport(0, 0, width, sceneHeight);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glClearColor(.025f, .032f, .048f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_SRC_ALPHA, GL_ONE);
        time(3, width, sceneHeight);
        for (int column = 0; column < 3; column++) {
            glViewport(column * panelWidth, 0, panelWidth, sceneHeight);
            boolean wall = column == 2;
            float[] color = column == 1 ? ICE : column == 2 ? SUNSET : PINK;
            curve(column == 0 ? 0 : column == 1 ? .55f : 1, wall, color);
            identityProjection();
            impact(.52f, wall ? .07f : -.44f, wall ? .15f : .27f, wall ? .27f : .12f, color);
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * sceneHeight * 4);
        glReadPixels(0, 0, width, sceneHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage actualGpu = new BufferedImage(width, sceneHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < sceneHeight; y++) for (int x = 0; x < width; x++) {
            int at = ((sceneHeight - 1 - y) * width + x) * 4;
            actualGpu.setRGB(x, y, 0xFF000000 | (pixels.get(at) & 255) << 16
                | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255);
        }
        // Labels are outside the unmodified driver-rendered content; this is never a game screenshot.
        BufferedImage labeled = new BufferedImage(width, sceneHeight + 154, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = labeled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(9, 13, 20));
        graphics.fillRect(0, 0, labeled.getWidth(), labeled.getHeight());
        graphics.setFont(new Font("SansSerif", Font.BOLD, 23));
        graphics.setColor(new Color(233, 238, 247));
        graphics.drawString("TRAJECTORIES / SOFT IMAGE GLOW", 24, 34);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 14));
        graphics.setColor(new Color(154, 167, 188));
        graphics.drawString("Production GlowTrail + connected ribbon + cached soft.png on this GPU. NOT an in-game screenshot.", 24, 58);
        graphics.drawImage(actualGpu, 0, 96, null);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 15));
        graphics.setColor(new Color(228, 234, 243));
        String[] titles = {"GLOW 0 / CORE PRESERVED", "GLOW 0.55 / IMAGE HALO", "GLOW 1 / WALL IMPACT"};
        for (int i = 0; i < titles.length; i++) graphics.drawString(titles[i], i * panelWidth + 24, 86);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 14));
        graphics.setColor(new Color(154, 167, 188));
        graphics.drawString("Repeated cached-image stamps · connected aiming core · floor/wall impact markers · no frame-time crawl", 24, sceneHeight + 128);
        graphics.dispose();
        Path preview = output.resolve("trajectory-gpu-preview.png");
        require(ImageIO.write(labeled, "png", preview.toFile()), "PNG encoder unavailable");
        System.out.println("GPU PREVIEW: " + preview + " (driver-rendered shader fixtures, not Minecraft integration evidence)");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected resources, Minecraft jar, output directory");
        resources = Path.of(args[0]);
        minecraft = new ZipFile(args[1]);
        output = Path.of(args[2]);
        Files.createDirectories(output);
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        require(glfwInit(), "GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(TILE, TILE, "Trajectory GPU regression (hidden)", 0, 0);
        require(window != 0, "OpenGL context unavailable");
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("Actual GPU: " + glGetString(GL_RENDERER) + "; OpenGL " + glGetString(GL_VERSION));
            ByteBuffer transforms = BufferUtils.createByteBuffer(160);
            matrix(transforms, 0);
            matrix(transforms, 96);
            for (int i = 0; i < 4; i++) transforms.putFloat(64 + i * 4, 1);
            buffer(0, transforms);
            ByteBuffer projection = BufferUtils.createByteBuffer(64);
            matrix(projection, 0);
            projectionUniform = buffer(1, projection);
            globals = buffer(2, BufferUtils.createByteBuffer(64));
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
            blankTexture = solidTexture(0);
            softTexture = texture("assets/celeste/textures/visual/fake_lag/soft.png");
            softPixels = ImageIO.read(resources.resolve("assets/celeste/textures/visual/fake_lag/soft.png").toFile());
            require(softPixels != null, "Exact SOFT texture unavailable for local-ripple analysis");
            pathMaterial = compileStatic("trajectory");
            impactMaterial = compileStatic("trajectory_impact");
            spriteMaterial = compileSoftSprite();
            frame = framebuffer(TILE, TILE, true);
            contract(false);
            contract(true);
            glowAndSeams();
            productionGlowStamps();
            imageRippleContract();
            preview();
            require(glGetError() == GL_NO_ERROR, "Final GL error");
            System.out.println("PASS TrajectoryMaterialCheck: " + assertions + " bounded driver assertions; runtime/in-game verification not performed.");
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
            callback.free();
            minecraft.close();
        }
    }
}
