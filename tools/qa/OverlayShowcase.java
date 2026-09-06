import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Node;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;
import dev.c4lam.celeste.module.impl.visual.expansion.TargetAuraShape;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImageWrite;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Moving-camera/player fixtures using production overlay shaders and block-union geometry.
 * This is an offscreen visual demonstration, not gameplay or a combat acceptance test. */
public final class OverlayShowcase extends VolumeShowcase {
    static final int FRAMES=360;
    static final List<BlockHighlightMesh.Cuboid> STAIR=List.of(
        new BlockHighlightMesh.Cuboid(0,0,0,1,.5,1),
        new BlockHighlightMesh.Cuboid(0,.5,0,1,1,.5));
    static final List<Face> STAIR_SURFACE=BlockHighlightMesh.surface(STAIR,192);
    static final List<Face> CUBE_SURFACE=BlockHighlightMesh.surface(
        List.of(new BlockHighlightMesh.Cuboid(-.002,-.002,-.002,1.002,1.002,1.002)),6);
    static float[] sceneEye,sceneLook;

    static void clock(float seconds) {
        ByteBuffer data=BufferUtils.createByteBuffer(64);
        data.putFloat(32,W).putFloat(36,H).putFloat(44,seconds/1200f);
        glBindBuffer(GL_UNIFORM_BUFFER,globals);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
    }
    static void movingView(float seconds,boolean low) {
        double yaw=.62+.18*Math.sin(seconds*.85);
        sceneEye=new float[]{(float)(4.1*Math.sin(yaw)),low?2.75f:2.05f,(float)(4.1*Math.cos(yaw))};
        sceneLook=new float[]{0,low?.45f:.88f,0};
        view(sceneEye,sceneLook,23);
    }
    static void skinAt(float x,float z) {
        view(new float[]{sceneEye[0]-x,sceneEye[1],sceneEye[2]-z},
             new float[]{sceneLook[0]-x,sceneLook[1],sceneLook[2]-z},23);
        minecraftPlayer();
        view(sceneEye,sceneLook,23);
    }
    static void silhouette(float x,float z) {
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        glBindFramebuffer(GL_FRAMEBUFFER,volumeMask);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
        glDepthMask(true);glDepthFunc(GL_LEQUAL);glClearColor(0,0,0,0);glClearDepth(1);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glUseProgram(fill);
        player(x,z,-.22f,new float[]{.46f,.82f,1f,.68f},false);
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,W,H);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(composite);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,prismTexture);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,volumeMaskColor);glDrawArrays(GL_TRIANGLES,0,3);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
    }
    static void solidShape(List<Face> faces) {
        glUseProgram(terrainProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,stoneTexture);
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthMask(true);
        FloatBuffer data=BufferUtils.createFloatBuffer(faces.size()*72);
        for(Face face:faces) {
            Point[] points={face.a(),face.b(),face.c(),face.d()};
            for(int i:TRIANGLES) {
                Point p=points[i];
                data.put((float)p.x()).put((float)p.y()).put((float)p.z())
                    .put(i==0||i==3?0:1).put(i<2?0:1).put(.86f).put(.86f).put(.86f).put(1)
                    .put(0).put(1).put(0);
            }
        }
        send(data);
    }
    static void depthVolumes(List<Face> faces) {
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,scene);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,volumeMask);
        glBlitFramebuffer(0,0,W,H,0,0,W,H,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER,volumeMask);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
        glDepthMask(true);glDepthFunc(GL_LEQUAL);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(volumeProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,prismTexture);
        send(surfaceData(faces,-1,false,false));
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,W,H);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(volumeComposite);glBindTexture(GL_TEXTURE_2D,volumeMaskColor);glDrawArrays(GL_TRIANGLES,0,3);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
    }
    static void overlayPanel(int index,float seconds) {
        int px=index%2*CW,py=(1-index/2)*CH;
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(px,py,CW,CH);
        glEnable(GL_SCISSOR_TEST);glScissor(px,py,CW,CH);
        glDepthMask(true);glClearColor(.52f,.73f,.91f,1);glClearDepth(1);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
        clock(seconds);materialTime=seconds;ice=index==0||index==2;
        movingView(seconds,index>=2);floor();
        float move=(float)Math.sin(seconds*1.25)*.36f;
        if(index==0) {
            skinAt(move,0);silhouette(move,0);
        } else if(index==1) {
            skinAt(move,0);
            List<Face> faces=new ArrayList<>();
            for(int layer=0;layer<2;layer++) {
                List<TargetAuraShape.Point> path=TargetAuraShape.path("Helix",layer,.672,1.8,seconds*1.8,.22,true);
                List<Node> nodes=new ArrayList<>();
                for(int i=0;i<path.size();i++) {
                    var p=path.get(i);
                    nodes.add(new Node(new Point(p.x()+move,p.y(),p.z()),.066,.048,color(layer*.5,.86)));
                }
                faces.addAll(SolidVisualMesh.tube(nodes,UP,4,80,false));
            }
            volumes(faces);
        } else if(index==2) {
            List<Face> shape=BlockHighlightMesh.materialize(STAIR_SURFACE,-.5,0,-.5,0xFFFFFFFF,192);
            solidShape(shape);
            var inflated=BlockHighlightMesh.surface(STAIR.stream().map(b->new BlockHighlightMesh.Cuboid(
                b.minX()-.002,b.minY()-.002,b.minZ()-.002,b.maxX()+.002,b.maxY()+.002,b.maxZ()+.002)).toList(),192);
            depthVolumes(BlockHighlightMesh.materialize(inflated,-.5,0,-.5,0x5974DFFF,192));
        } else {
            skinAt(.72f,-.5f);
            terrainBlock(-1,-.05f,0,false);
            // Exact future-anchor unit volume. The terrain/player and plan position are fixtures.
            depthVolumes(BlockHighlightMesh.materialize(CUBE_SURFACE,-1,.95,0,0x58D5A4FF,6));
        }
    }
    static void frame(float seconds) {
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,W,H);
        for(int panel=0;panel<4;panel++)overlayPanel(panel,seconds);
        if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Overlay preview GL error");
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);changedResources=resources;
        Files.createDirectories(output);Path video=output.resolve("Celeste-Overlay-Visuals.mp4");
        if(Files.exists(video))throw new IllegalStateException("Refusing to overwrite an existing video");
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();
        if(!glfwInit())throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Celeste overlay preview (hidden)",0,0);
        if(window==0)throw new AssertionError("No GL context");
        glfwMakeContextCurrent(window);GL.createCapabilities();Process encoder=null;
        try {
            fill=material("unified_chams_fill",null);composite=material("unified_chams_composite",null);
            volumeProgram=program("volume");volumeComposite=material("unified_volume_composite",null);
            pulseProgram=program("pulse");prismTexture=texture("assets/celeste/textures/visual/plasma_flow.png");
            pulseTexture=texture("assets/celeste/textures/visual/fake_lag/halo.png");
            transformBuffer=buffer(0,BufferUtils.createByteBuffer(160));projectionBuffer=buffer(1,BufferUtils.createByteBuffer(64));
            globals=buffer(2,BufferUtils.createByteBuffer(64));glBindVertexArray(glGenVertexArrays());
            vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3};int offset=0;
            for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}
            glDisable(GL_CULL_FACE);volumeMaskColor=attachment(false,W,H);
            volumeMask=fbo(volumeMaskColor,attachment(true,W,H));scene=fbo(attachment(false,W,H),attachment(true,W,H));
            encoder=new ProcessBuilder(args[3],"-hide_banner","-loglevel","error","-n",
                "-f","rawvideo","-pixel_format","rgba","-video_size",W+"x"+H,"-framerate","60","-i","pipe:0",
                "-vf","vflip","-an","-c:v","libx264","-preset","fast","-crf","18","-pix_fmt","yuv420p",
                "-movflags","+faststart",video.toString()).redirectError(output.resolve("encode.log").toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);byte[] bytes=new byte[W*H*4];
            try(OutputStream input=new BufferedOutputStream(encoder.getOutputStream(),1<<20)) {
                for(int i=0;i<FRAMES;i++) {
                    frame(3.2f+i/60f);pixels.clear();glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                    if(i==FRAMES/2) {
                        STBImageWrite.stbi_flip_vertically_on_write(true);
                        if(!STBImageWrite.stbi_write_png(output.resolve("preview.png").toString(),W,H,4,pixels,W*4))
                            throw new IOException("Preview PNG failed");
                    }
                    pixels.position(0);pixels.get(bytes);input.write(bytes);
                    if((i+1)%60==0)System.out.println("Rendered "+(i+1)+" / "+FRAMES+" frames");
                }
            }
            if(!encoder.waitFor(60,TimeUnit.SECONDS)||encoder.exitValue()!=0)throw new IOException("Video encoding failed");
            System.out.println("PASS six-second60fps production-shader preview; camera+subject movement; chams/helix/block/anchor-plan. Offscreen fixtures, not gameplay.");
        } finally {
            if(encoder!=null&&encoder.isAlive())encoder.destroy();
            glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
        }
    }
}
