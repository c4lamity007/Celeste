import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientGeometry;

/** Exact production leaf helpers/material in a hidden GPU fixture, never an in-game screenshot. */
public final class FallingLeavesGpuCheck extends ImpactCheck {
    static int mesh, leafVertices, checks;
    static final int W = AtmosphereCheck.W, H = AtmosphereCheck.H;
    static void check(boolean value, String text) { checks++; if (!value) throw new AssertionError(text); }
    static List<AmbientField.Mote> samples(double time) {
        List<AmbientField.Mote> motes = new ArrayList<>();
        double ex = 2.5, ey = 3, ez = 2.5;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -3; y <= 3; y++) {
            if (!AmbientField.occupied(x, y, z, .6) || !AmbientField.mayBeVisible(x, y, z, ex, ey, ez)) continue;
            var m = AmbientField.sample(x, y, z, AmbientField.Style.LEAVES, time);
            double alpha = m.opacity() * AmbientField.visibility(m.x() - ex, m.y() - ey, m.z() - ez);
            if (alpha >= .01 && m.y() > 0) motes.add(new AmbientField.Mote(m.x() - ex, m.y() - ey, m.z() - ez,
                m.size(), m.yaw(), m.pitch(), alpha, m.color()));
        }
        motes.sort(Comparator.comparingDouble(m -> m.x()*m.x() + m.y()*m.y() + m.z()*m.z()));
        if (motes.size() > 128) motes.subList(128, motes.size()).clear();
        Collections.reverse(motes);
        return motes;
    }
    static FloatBuffer geometry(List<AmbientField.Mote> motes, float alpha) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(Math.max(1, motes.size()) * 16 * 3 * 12);
        double[] scratch = new double[AmbientGeometry.SCRATCH_SIZE];
        for (var m : motes) {
            AmbientGeometry.leaf(scratch, m.x(), m.y(), m.z(), m.size(), m.yaw(), m.pitch());
            float[] color = AtmosphereCheck.color(m.color(), (Math.round(m.opacity() * .96 * 255) / 255.0) * alpha);
            for (int edge = 0; edge < AmbientGeometry.LEAF_EDGE_VERTICES; edge++) {
                int next = (edge + 1) % AmbientGeometry.LEAF_EDGE_VERTICES;
                face(buffer, scratch, 8, next, edge, color);
                face(buffer, scratch, 9, edge, next, color);
            }
        }
        leafVertices = buffer.position() / 12;
        check(leafVertices <= 6144 && leafVertices % 48 == 0, "bounded production leaf triangles");
        return buffer;
    }
    static void face(FloatBuffer buffer, double[] scratch, int a, int b, int c, float[] color) {
        AmbientGeometry.leafNormal(scratch, a, b, c);
        for (int index : new int[]{a, b, c}) AtmosphereCheck.vertex(buffer, scratch[index * 3], scratch[index * 3 + 1], scratch[index * 3 + 2],
            AmbientGeometry.leafU(index), AmbientGeometry.leafV(index), color, scratch[30], scratch[31], scratch[32]);
    }
    static List<AmbientField.Mote> detail() {
        // Default-scale leaves viewed from a closer camera; geometry and shader are unchanged.
        return List.of(new AmbientField.Mote(-.39, -.01, -1.2, .17, -.42, 1.19, 1, 0xE5A94D),
            new AmbientField.Mote(.06, .03, -1.52, .17, .42, .92, 1, 0x83B18C),
            new AmbientField.Mote(.43, -.05, -1.52, .17, -.10, 1.52, 1, 0xD9903E));
    }
    static float[] draw(List<AmbientField.Mote> motes, float alpha, float depth, boolean background) {
        AtmosphereCheck.clear(depth);
        if (background) { glClearColor(.075f, .105f, .12f, 1); glClear(GL_COLOR_BUFFER_BIT); }
        AtmosphereCheck.transforms(AtmosphereCheck.identity(), AtmosphereCheck.perspective(), new float[]{1,1,1,1}, 0);
        AtmosphereCheck.fog(18, 40);
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(false);
        glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_CULL_FACE); glCullFace(GL_BACK);
        AtmosphereCheck.drawVertices(mesh, geometry(motes, alpha));
        AtmosphereCheck.depth(depth);
        check(glGetError() == GL_NO_ERROR, "leaf draw OpenGL error");
        float[] result = AtmosphereCheck.pixels();
        for (float v : result) check(Float.isFinite(v) && v >= 0 && v <= 1.00001f, "finite bounded leaf fragments");
        return result;
    }
    static void sheet(float[] detail, float[] field) throws Exception {
        int header = 70, footer = 38;
        BufferedImage sheet = new BufferedImage(W * 2, H + header + footer, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(18, 24, 29)); g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        g.setColor(new Color(232, 239, 236)); g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("FALLING LEAVES  /  production mesh + shader GPU preview", 18, 28);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("Closed folded blades. Amber, honey and sage. No sprites, entities, texture downloads or glow billboards.", 18, 51);
        g.drawImage(AtmosphereCheck.image(detail), 0, header, null);
        g.drawImage(AtmosphereCheck.image(field), W, header, null);
        g.drawString("Material detail / default leaf size, closer camera", 18, header + H + 24);
        g.drawString("Empty-air field / density .6, size 1 / NOT an in-game screenshot", W + 18, header + H + 24);
        g.dispose();
        Path target = output.resolve("falling-leaves-gpu-preview.png");
        ImageIO.write(sheet, "PNG", target.toFile());
        System.out.println("GPU PREVIEW: " + target);
    }
    public static void main(String[] args) throws Exception {
        resources = Path.of(args[0]); minecraft = new ZipFile(args[1]); output = Path.of(args[2]); Files.createDirectories(output);
        check(glfwInit(), "GLFW init"); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3); glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W,H,"Falling leaves hidden GPU QA",0,0); check(window != 0,"GPU context");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        try {
            glBindVertexArray(glGenVertexArrays()); vertexBuffer = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3}, offsets={0,3,5,9};
            for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));
            AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));
            AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));
            mesh=AtmosphereCheck.link("ambient_mesh.vsh","ambient_mesh.fsh"); frame=framebuffer(W,H,true);
            var field=samples(12); float[] visible=draw(field,1,1,false);
            int coverage=0;for(int i=3;i<visible.length;i+=4)if(visible[i]>.003)coverage++;
            check(coverage>30,"default field visibly renders");
            var shortStep=AtmosphereCheck.delta(visible,draw(samples(12+1.0/60),1,1,false));
            var longStep=AtmosphereCheck.delta(visible,draw(samples(12.5),1,1,false));
            check(shortStep.changed()>20 && longStep.mean()>shortStep.mean()*2,"smooth sub-tick drift and tumble");
            for(float v:draw(field,0,1,false))check(v==0,"zero opacity / disabled field is empty");
            for(float v:draw(field,1,.5f,false))check(v==0,"scene depth fully occludes leaves");
            for(float v:draw(List.of(),1,1,false))check(v==0,"empty batch retains no stale geometry");
            float[] front=draw(detail(),1,1,true), overview=draw(field,1,1,true); sheet(front,overview);
            System.out.println("PASS Falling Leaves GPU: "+checks+" checks; "+coverage+" default-field pixels; actual folded mesh/material, sub-tick motion, zero opacity, depth occlusion, unchanged main depth");
            System.out.println("GPU: "+glGetString(GL_RENDERER)+"; Minecraft native shader includes. Preview is not in-game evidence.");
        } finally { minecraft.close(); glfwDestroyWindow(window); glfwTerminate(); }
    }
}
