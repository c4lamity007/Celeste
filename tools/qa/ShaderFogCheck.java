import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real production GLSL against copied scene depth. Offscreen QA, never an in-game claim. */
public final class ShaderFogCheck extends ImpactCheck {
    static final int W = AtmosphereCheck.W, H = AtmosphereCheck.H;
    static final double TAU = Math.PI * 2;
    static final String[] MODES = {"Aurora", "Nebula", "Sunset", "Abyss", "BlackHole"};
    static int shaderFog, sceneDepth, dynamic, projection, fixture;
    static long assertions;
    static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    static float[] perspective() {
        float[] value = AtmosphereCheck.perspective();
        value[10] = -(1024.0f + .1f) / (1024.0f - .1f);
        value[14] = -2 * 1024.0f * .1f / (1024.0f - .1f);
        return value;
    }
    static float depthAt(float distance) {
        float[] p = perspective();
        return ((-p[10] * distance + p[14]) / distance) * .5f + .5f;
    }
    static void transforms(int mode, float density, float distance, double phase,
                           float[] viewToWorld, float x, float y, float z) {
        ByteBuffer data = BufferUtils.createByteBuffer(160);
        AtmosphereCheck.writeMatrix(data, 0, AtmosphereCheck.identity());
        data.putFloat(64, mode).putFloat(68, density).putFloat(72, distance).putFloat(76, (float) phase);
        data.putFloat(80, x).putFloat(84, y).putFloat(88, z);
        AtmosphereCheck.writeMatrix(data, 96, viewToWorld);
        glBindBuffer(GL_UNIFORM_BUFFER, dynamic);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, data);
        data = BufferUtils.createByteBuffer(64);
        AtmosphereCheck.writeMatrix(data, 0, perspective());
        glBindBuffer(GL_UNIFORM_BUFFER, projection);
        glBufferSubData(GL_UNIFORM_BUFFER, 0, data);
    }
    static void copyDepth() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, frame);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneDepth);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, W, H);
        require(glGetError() == GL_NO_ERROR, "scene depth copy failed");
    }
    static void overlay(int mode, float density, float distance, double phase,
                        float[] cameraRotation, float x, float y, float z, boolean blend) {
        transforms(mode, density, distance, phase, cameraRotation, x, y, z);
        glBindFramebuffer(GL_FRAMEBUFFER, frame);
        glViewport(0, 0, W, H);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        if (blend) {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else glDisable(GL_BLEND);
        glUseProgram(shaderFog);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneDepth);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        require(glGetError() == GL_NO_ERROR, "shader fog render failed");
    }
    static float[] render(int mode, float density, float distance, double phase, float depth,
                          float[] rotation, float x, float y, float z) {
        AtmosphereCheck.clear(depth);
        copyDepth();
        overlay(mode, density, distance, phase, rotation, x, y, z, false);
        float[] result = AtmosphereCheck.pixels();
        AtmosphereCheck.depth(depth);
        bounded(result);
        return result;
    }
    static float[] render(int mode, float density, double phase, float depth) {
        return render(mode, density, 160, phase, depth, AtmosphereCheck.identity(), 21, 72, -33);
    }
    static void bounded(float[] pixels) {
        for (int i = 0; i < pixels.length; i++)
            require(Float.isFinite(pixels[i]) && pixels[i] >= 0 && pixels[i] <= 1.0001,
                "nonfinite or out of display range channel " + i + ": " + pixels[i]);
    }
    static void zero(float[] pixels, String message) {
        for (float pixel : pixels) require(pixel == 0, message);
    }
    static double meanAlpha(float[] pixels) {
        double alpha = 0;
        for (int i = 3; i < pixels.length; i += 4) alpha += pixels[i];
        return alpha / (pixels.length / 4);
    }
    static void close(float[] a, float[] b, double mean, double peak, String label) {
        var delta = AtmosphereCheck.delta(a, b);
        require(delta.mean() <= mean && delta.peak() <= peak, label + ": " + delta);
    }
    static void correctness() {
        AtmosphereCheck.layout(shaderFog, "ColorModulator", 64);
        AtmosphereCheck.layout(shaderFog, "ModelOffset", 80);
        AtmosphereCheck.layout(shaderFog, "TextureMat", 96);
        float[][] modes = new float[MODES.length][];
        for (int mode = 0; mode < MODES.length; mode++) {
            zero(render(mode, 0, .7, depthAt(160)), "density zero must have no output");
            zero(render(mode, .65f, .7, 0), "invalid zero depth must not produce fog");
            zero(render(mode, .65f, .7, 1), "sky depth must remain unmodified");
            zero(render(mode, .65f, .7, depthAt(1)), "close surfaces must stay clear");
            float[] low = render(mode, .3f, .7, depthAt(160));
            modes[mode] = render(mode, .65f, .7, depthAt(160));
            require(meanAlpha(modes[mode]) > .06, "fog mode invisible: " + MODES[mode]);
            require(meanAlpha(low) < meanAlpha(modes[mode]), "density must increase coverage");
            require(meanAlpha(render(mode, .65f, .7, depthAt(32))) < meanAlpha(modes[mode]), "distance must increase coverage");
            require(meanAlpha(render(mode,.65f,512,.7,depthAt(160),AtmosphereCheck.identity(),21,72,-33))
                < meanAlpha(render(mode,.65f,32,.7,depthAt(160),AtmosphereCheck.identity(),21,72,-33)),
                "distance control must broaden the clear range");
            var subTick = AtmosphereCheck.delta(modes[mode], render(mode, .65f, .7 + TAU / 240 / 60, depthAt(160)));
            var later = AtmosphereCheck.delta(modes[mode], render(mode, .65f, .7 + TAU / 240, depthAt(160)));
            require(subTick.changed() > 20, "no frame-based animation: " + MODES[mode]);
            require(subTick.mean() < .003 && later.mean() > subTick.mean() * 3,
                "animation does not progress smoothly: " + MODES[mode] + " " + subTick + " " + later);
            close(render(mode, .65f, 0, depthAt(160)), render(mode, .65f, TAU, depthAt(160)),
                .00001, .001, "continuous loop: " + MODES[mode]);
            float[] shifted = render(mode, .65f, 160, .7, depthAt(160), AtmosphereCheck.identity(), 27, 72, -33);
            require(AtmosphereCheck.delta(modes[mode], shifted).mean() > .00001, "fog wrongly glued to screen");
            close(modes[mode], render(mode, .65f, 160, .7, depthAt(160), AtmosphereCheck.identity(), 21 + 8192, 72, -33),
                .0002, .015, "world-cell periodic wrap seam");
            // A rotation about the screen's optical axis maps the same scene/rays onto opposite pixels.
            float[] rotated = render(mode, .65f, 160, .7, depthAt(160),
                new float[]{-1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,0,0,1}, 21,72,-33);
            float[] reversed = new float[rotated.length];
            for (int i = 0; i < W * H; i++) for (int c = 0; c < 4; c++) reversed[i * 4 + c] = rotated[(W * H - 1 - i) * 4 + c];
            close(modes[mode], reversed, .0001, .01, "camera rotation/world anchoring");
            // The game's ordinary RGBA attachment is not sRGB. Toggling framebuffer conversion
            // on the same linear floating-point attachment must not change shader output.
            glEnable(GL_FRAMEBUFFER_SRGB);
            float[] srgbFlag = render(mode, .65f, .7, depthAt(160));
            glDisable(GL_FRAMEBUFFER_SRGB);
            close(modes[mode], srgbFlag, 0, 0, "no hidden sRGB attachment assumption");
            System.out.printf(Locale.ROOT, "PASS ShaderFog %s: coverage=%.4f, frame delta=%.8f; depth/near/sky/disable/loop/world anchoring/sRGB passed%n",
                MODES[mode], meanAlpha(modes[mode]), subTick.mean());
        }
        for (int a = 0; a < modes.length; a++) for (int b = a + 1; b < modes.length; b++)
            require(AtmosphereCheck.delta(modes[a], modes[b]).mean() > .008, "fog presets are not visually distinct");
        // Exactly one source-alpha composite, not layered geometry or repeated alpha accumulation.
        float[] source = render(2, .65f, .7, depthAt(160));
        AtmosphereCheck.clear(depthAt(160));
        glClearColor(.23f,.34f,.45f,1);glClear(GL_COLOR_BUFFER_BIT);copyDepth();
        overlay(2,.65f,160,.7,AtmosphereCheck.identity(),21,72,-33,true);
        float[] mixed = AtmosphereCheck.pixels();
        float[] background = {.23f,.34f,.45f};
        for (int i=0;i<source.length;i+=4) {
            float alpha=source[i+3];
            for(int c=0;c<3;c++) require(Math.abs(mixed[i+c]-(source[i+c]*alpha+background[c]*(1-alpha)))<.0015,
                "fog does not composite exactly once");
            require(mixed[i+3]==1,"fog changed opaque framebuffer alpha");
        }
        AtmosphereCheck.depth(depthAt(160));
        System.out.println("PASS ShaderFog: exact one-pass alpha composite and no scene-depth modification");
        // A degenerate external projection must preserve the target, never flash NaNs.
        AtmosphereCheck.clear(depthAt(160));copyDepth();
        transforms(0,.65f,160,.7,AtmosphereCheck.identity(),21,72,-33);
        glBindBuffer(GL_UNIFORM_BUFFER,projection);
        glBufferSubData(GL_UNIFORM_BUFFER,0,BufferUtils.createByteBuffer(64));
        glDisable(GL_BLEND);glUseProgram(shaderFog);glDrawArrays(GL_TRIANGLES,0,3);
        zero(AtmosphereCheck.pixels(),"singular projection must leave target untouched");
        AtmosphereCheck.depth(depthAt(160));
    }
    static float[] scene(int mode, boolean withFog, double phase) {
        float[] view = AtmosphereCheck.rotation(0,.09), proj = perspective();
        AtmosphereCheck.clear(1);
        // Same production palette and native horizon-color interpolation as the adapter.
        int tint=dev.c4lam.celeste.module.impl.visual.expansion.ShaderFogMath.horizon(mode);
        double weight=.65*.84;
        var horizon=new dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath.Color(
            .18+(((tint>>16)&255)/255.0-.18)*weight,
            .24+(((tint>>8)&255)/255.0-.24)*weight,
            .30+((tint&255)/255.0-.30)*weight,1);
        AtmosphereCheck.fog(new dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath.Fog(horizon,0,1024,768,1024,1024,1024));
        AtmosphereCheck.transforms(view,proj,new float[]{mode,.85f,(float)phase,.7f},0);
        glUseProgram(AtmosphereCheck.sky);glDrawArrays(GL_TRIANGLES,0,3);
        AtmosphereCheck.transforms(view,proj,new float[]{1,1,1,1},0);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDisable(GL_BLEND);
        FloatBuffer terrain = BufferUtils.createFloatBuffer(12*6*(80*48+6*14));
        for(int x=-160;x<160;x+=4)for(int z=-192;z<0;z+=4)
            AtmosphereCheck.fixtureQuad(terrain,new double[][]{{x,-4,z},{x+4,-4,z},{x+4,-4,z+4},{x,-4,z+4}},
                ((x+z)/4&1)==0 ? 0x495C4F : 0x435548);
        for(int i=0;i<14;i++) {
            double z=-18-i*12,x=(i%2==0?-1:1)*(7+i*2),s=2+i*.3,top=1+i*.6;
            double[][] p={{x-s,-4,z-s},{x+s,-4,z-s},{x+s,top,z-s},{x-s,top,z-s},
                {x-s,-4,z+s},{x+s,-4,z+s},{x+s,top,z+s},{x-s,top,z+s}};
            int f=0;
            for(int[] face:new int[][]{{0,1,2,3},{4,7,6,5},{0,4,5,1},{3,2,6,7},{0,3,7,4},{1,5,6,2}})
                AtmosphereCheck.fixtureQuad(terrain,new double[][]{p[face[0]],p[face[1]],p[face[2]],p[face[3]]},
                    f++==3?0x7C827F:0x626A68);
        }
        AtmosphereCheck.drawVertices(fixture,terrain);
        glDepthMask(false);copyDepth();
        if(withFog)overlay(mode,.65f,160,phase,AtmosphereCheck.transpose(view),0,72,0,true);
        return AtmosphereCheck.pixels();
    }
    static void previews() throws Exception {
        int header=66,label=29;
        BufferedImage sheet=new BufferedImage(W*2,(H+label)*((MODES.length+1)/2)+header,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=sheet.createGraphics();
        g.setColor(new Color(15,18,24));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString("SHADER FOG + MATCHING SKY",14,24);
        g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString("Actual production GLSL / offscreen depth fixture — not Minecraft screenshots",14,44);
        for(int mode=0;mode<MODES.length;mode++) {
            float[] without=scene(mode,false,.7),with=scene(mode,true,.7);
            require(AtmosphereCheck.delta(without,with).mean()>.002,"fog does not affect scene fixture");
            BufferedImage image=AtmosphereCheck.image(with);
            ImageIO.write(image,"PNG",output.resolve(MODES[mode].toLowerCase(Locale.ROOT)+"-shader-fog-gpu.png").toFile());
            int x=mode%2*W,y=header+mode/2*(H+label);
            g.drawImage(image,x,y+label,null);g.setColor(Color.WHITE);g.drawString(MODES[mode]+" / density .65 / distance 160",x+10,y+20);
        }
        g.dispose();Path destination=output.resolve("shader-fog-gpu-contact-sheet.png");
        ImageIO.write(sheet,"PNG",destination.toFile());System.out.println("GPU preview: "+destination);
    }
    public static void main(String[] args) throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        require(glfwInit(),"GLFW initialization");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Shader fog offscreen regression",0,0);require(window!=0,"OpenGL3.3 context");
        glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3},offsets={0,3,5,9};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            dynamic=AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));
            projection=AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));
            AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));
            shaderFog=AtmosphereCheck.link("shader_fog.vsh","shader_fog.fsh");
            AtmosphereCheck.sky=AtmosphereCheck.link("skybox.vsh","skybox.fsh");
            glUseProgram(shaderFog);int sampler=glGetUniformLocation(shaderFog,"SceneDepth");require(sampler>=0,"depth sampler missing");glUniform1i(sampler,0);
            fixture=AtmosphereCheck.linkSource(preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/ambient_mesh.vsh"))),
                "#version 330\nin vec4 tint;out vec4 fragColor;void main(){fragColor=tint;}","unfogged block fixture");
            frame=framebuffer(W,H,true);sceneDepth=attachment(true,W,H);
            correctness();previews();
            System.out.println("PASS ShaderFog GPU: "+assertions+" assertions; "+AtmosphereCheck.depthPixels+" unmodified scene-depth pixels; "+glGetString(GL_RENDERER)+". Not in-game verification.");
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
