import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Out-of-game posed block-model scene using the unmodified production material programs.
 * No post-production glow, recoloring, skin images or fabricated Minecraft screenshot. */
public class VisualShowcase extends ChamsAuroraCheck {
    static final int WIDTH = 1100, HEIGHT = 1100;
    static final float[] CHAMS = {.16f, .78f, 1f, .68f};
    static final float[] BOX = {.16f, .72f, 1f, .72f};
    static final float[] UVS = {0,0, 1,0, 1,1, 0,1};
    static final int[][] FACES = {{0,1,5,4},{2,3,7,6},{3,0,4,7},{1,2,6,5},{4,5,6,7},{3,2,1,0}};
    static final int[] TRIANGLES = {0,1,2,0,2,3};
    static int transformBuffer, projectionBuffer, prismProgram, prismTexture, jumpProgram, jumpTexture;
    static int scene, sceneDepth;
    static float modelScale = 1f;

    static void send(FloatBuffer vertices) {
        vertices.flip(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertices.remaining()/12);
    }
    static void vertex(FloatBuffer out, float[] p, int uv, float[] color, float shade) {
        out.put(p).put(UVS[uv*2]).put(UVS[uv*2+1]);
        out.put(color[0]*shade).put(color[1]*shade).put(color[2]*shade).put(color[3]);
        out.put(0).put(1).put(0);
    }
    static float[] rotate(float x,float y,float z,float pitch,float roll,float yaw) {
        float cx=(float)Math.cos(pitch),sx=(float)Math.sin(pitch),cz=(float)Math.cos(roll),sz=(float)Math.sin(roll);
        float cy=(float)Math.cos(yaw),sy=(float)Math.sin(yaw);
        float y1=y*cx-z*sx,z1=y*sx+z*cx,x1=x*cz-y1*sz,y2=x*sz+y1*cz;
        return new float[]{x1*cy+z1*sy,y2,-x1*sy+z1*cy};
    }
    static void part(float cx,float cy,float cz,float hx,float hy,float hz,
                     float px,float py,float pz,float pitch,float roll,float originX,float originZ,
                     float yaw,float[] color,boolean shaded) {
        float[][] corners={{-hx,-hy,-hz},{hx,-hy,-hz},{hx,-hy,hz},{-hx,-hy,hz},
            {-hx,hy,-hz},{hx,hy,-hz},{hx,hy,hz},{-hx,hy,hz}};
        for(int i=0;i<corners.length;i++) {
            float[] p=corners[i], r=rotate(p[0]+cx-px,p[1]+cy-py,p[2]+cz-pz,pitch,roll,0);
            r=rotate(r[0]+px,r[1]+py,r[2]+pz,0,0,yaw);
            corners[i]=new float[]{r[0]*modelScale+originX,r[1]*modelScale,r[2]*modelScale+originZ};
        }
        FloatBuffer data=BufferUtils.createFloatBuffer(36*12);
        float[] shades={.70f,.88f,.65f,.83f,1f,.5f};
        for(int f=0;f<FACES.length;f++)for(int corner:TRIANGLES)
            vertex(data,corners[FACES[f][corner]],corner,color,shaded?shades[f]:1);
        send(data);
    }
    static void player(float x,float z,float yaw,float[] color,boolean shaded) {
        modelScale=.90f;
        part(0,1.76f,0,.25f,.25f,.25f,0,1.51f,0,0,-.06f,x,z,yaw,color,shaded);
        part(0,1.13f,0,.25f,.38f,.125f,0,1.5f,0,0,0,x,z,yaw,color,shaded);
        part(-.375f,1.13f,0,.126f,.38f,.125f,-.375f,1.50f,0,-.22f,-.14f,x,z,yaw,color,shaded);
        part(.375f,1.13f,0,.126f,.38f,.125f,.375f,1.50f,0,.29f,.16f,x,z,yaw,color,shaded);
        part(-.126f,.375f,0,.126f,.375f,.125f,-.126f,.75f,0,.23f,0,x,z,yaw,color,shaded);
        part(.126f,.375f,0,.126f,.375f,.125f,.126f,.75f,0,-.23f,0,x,z,yaw,color,shaded);
        modelScale=1f;
    }
    static void horizontal(float x,float y,float z,float half,float[] color) {
        float[][] p={{x-half,y,z-half},{x+half,y,z-half},{x+half,y,z+half},{x-half,y,z+half}};
        FloatBuffer data=BufferUtils.createFloatBuffer(72);
        for(int i:TRIANGLES)vertex(data,p[i],i,color,1); send(data);
    }
    static float[] unit(float[] p) {
        float length=(float)Math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]);
        return new float[]{p[0]/length,p[1]/length,p[2]/length};
    }
    static float[] cross(float[] a,float[] b) {
        return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
    static float dot(float[] a,float[] b) {return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    static void camera() {
        float[] eye={2.10f,2.65f,5.30f},target={0,1.02f,0};
        float[] f=unit(new float[]{target[0]-eye[0],target[1]-eye[1],target[2]-eye[2]});
        float[] s=unit(cross(f,new float[]{0,1,0})),u=cross(s,f);
        float[] view={s[0],u[0],-f[0],0,s[1],u[1],-f[1],0,s[2],u[2],-f[2],0,-dot(s,eye),-dot(u,eye),dot(f,eye),1};
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,96);
        for(int i=0;i<16;i++)data.putFloat(i*4,view[i]);for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        glBindBuffer(GL_UNIFORM_BUFFER,transformBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer proj=BufferUtils.createByteBuffer(64);float scale=1f/(float)Math.tan(Math.toRadians(18));
        proj.putFloat(0,scale*HEIGHT/WIDTH).putFloat(20,scale).putFloat(40,-1.002002f).putFloat(44,-1).putFloat(56,-.2002002f);
        glBindBuffer(GL_UNIFORM_BUFFER,projectionBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,proj);
    }
    static void sceneTime(float seconds) {
        ByteBuffer values=BufferUtils.createByteBuffer(64);
        values.putFloat(32,WIDTH).putFloat(36,HEIGHT).putFloat(44,seconds/1200f);
        glBindBuffer(GL_UNIFORM_BUFFER,globals);glBufferSubData(GL_UNIFORM_BUFFER,0,values);
    }
    static void capture(float seconds,String name) {
        sceneTime(seconds);camera();
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,WIDTH,HEIGHT);
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);
        glClearColor(.025f,.03f,.05f,1);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glUseProgram(fill);
        horizontal(0,-.055f,0,8,new float[]{.040f,.045f,.070f,1});
        for(int x=-8;x<8;x++)for(int z=-8;z<8;z++) {
            float amount=((x+z)&1)==0?.055f:.063f;
            horizontal(x*.62f,-.051f,z*.62f,.304f,new float[]{amount*.85f,amount,amount*1.40f,1});
        }
        player(-.78f,.12f,-.42f,new float[]{.075f,.115f,.19f,1},true);
        player(.87f,-.12f,-.35f,new float[]{.105f,.16f,.23f,1},true);
        glDepthMask(false);glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(jumpProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,jumpTexture);
        horizontal(-.78f,-.015f,.12f,1.03f,new float[]{.28f,.57f,1,.76f});

        // Same private depth/constant-RGBA union and single translucent composite as the client.
        glBindFramebuffer(GL_FRAMEBUFFER,mask);glDisable(GL_BLEND);glDepthMask(true);glEnable(GL_DEPTH_TEST);
        glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glUseProgram(fill);
        player(-.78f,.12f,-.42f,CHAMS,false);
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(composite);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,energyTexture);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,maskColor);glDrawArrays(GL_TRIANGLES,0,3);

        // Exact additive prism material, all six box faces, no terrain-depth writes.
        glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(prismProgram);glBindTexture(GL_TEXTURE_2D,prismTexture);
        // Typical standing player collision bounds, expanded by the module's .035 margin.
        part(0,.90f,0,.335f,.935f,.335f,0,0,0,0,0,.87f,-.12f,0,BOX,false);
        ByteBuffer pixels=BufferUtils.createByteBuffer(WIDTH*HEIGHT*4);
        glReadPixels(0,0,WIDTH,HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        STBImageWrite.stbi_flip_vertically_on_write(true);
        if(!STBImageWrite.stbi_write_png(output.resolve(name).toString(),WIDTH,HEIGHT,4,pixels,WIDTH*4))throw new AssertionError("PNG failed");
        if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL showcase error");
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);changedResources=Path.of(args[3]);
        Files.createDirectories(output);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();
        if(!glfwInit())throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(WIDTH,HEIGHT,"Celeste material showcase (hidden)",0,0);
        if(window==0)throw new AssertionError("No OpenGL context");glfwMakeContextCurrent(window);GL.createCapabilities();
        fill=material("unified_chams_fill",null);composite=material("unified_chams_composite",null);
        prismProgram=PrismMaterialCheck.compile(resources,changedResources);jumpProgram=program("jump");
        prismTexture=texture("assets/celeste/textures/visual/plasma_flow.png");energyTexture=prismTexture;
        jumpTexture=texture("assets/celeste/textures/visual/jump/glow.png");
        transformBuffer=buffer(0,BufferUtils.createByteBuffer(160));projectionBuffer=buffer(1,BufferUtils.createByteBuffer(64));globals=buffer(2,BufferUtils.createByteBuffer(64));
        glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
        int[] sizes={3,2,4,3};int offset=0;for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}
        glDisable(GL_CULL_FACE);maskColor=attachment(false,WIDTH,HEIGHT);mask=fbo(maskColor,attachment(true,WIDTH,HEIGHT));
        sceneDepth=attachment(true,WIDTH,HEIGHT);scene=fbo(attachment(false,WIDTH,HEIGHT),sceneDepth);
        capture(4.2f,"visual-showcase.png");capture(4.7f,"visual-showcase-later.png");
        System.out.println("PASS 1100x1100 production shader showcase. Posed block-model fixture + test floor, not Minecraft. No extra post-processing. Chams left; Player ESP right; existing jump ring below.");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
    }
}
