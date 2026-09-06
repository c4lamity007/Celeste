import dev.c4lam.celeste.module.impl.visual.expansion.WaterJumpMesh;
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

/** Production water geometry + production shaders; neutral out-of-game test floor only. */
public final class WaterShowcase extends VisualShowcase {
    static final int WATER_WIDTH=1100, WATER_HEIGHT=850;
    static final double SIZE=1.8, PHASE=.80, INTENSITY=.85;
    static int waterProgram,splashProgram,splashTexture;
    static float[] eye={3.05f,2.4f,3.80f},cameraRight,cameraUp;

    static void waterCamera() {
        float[] target={0,.27f,0};
        float[] f=unit(new float[]{target[0]-eye[0],target[1]-eye[1],target[2]-eye[2]});
        cameraRight=unit(cross(f,new float[]{0,1,0}));cameraUp=cross(cameraRight,f);
        float[] s=cameraRight,u=cameraUp;
        float[] view={s[0],u[0],-f[0],0,s[1],u[1],-f[1],0,s[2],u[2],-f[2],0,-dot(s,eye),-dot(u,eye),dot(f,eye),1};
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,96);
        for(int i=0;i<16;i++)data.putFloat(i*4,view[i]);for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        glBindBuffer(GL_UNIFORM_BUFFER,transformBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer proj=BufferUtils.createByteBuffer(64);float scale=1f/(float)Math.tan(Math.toRadians(21));
        proj.putFloat(0,scale*WATER_HEIGHT/WATER_WIDTH).putFloat(20,scale).putFloat(40,-1.002002f).putFloat(44,-1).putFloat(56,-.2002002f);
        glBindBuffer(GL_UNIFORM_BUFFER,projectionBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,proj);
    }
    static double clamp(double v) {return Math.max(0,Math.min(1,v));}
    // Existing flat ring formula. The raised mesh comes from the production helper below.
    static double opacity(double age) {
        if(age<0||age>=1)return 0;
        double a=clamp(age/.075),b=clamp((1-age)/.64);return a*a*(3-2*a)*b*b;
    }
    static double radius(double age,double size) {
        double rem=1-clamp(age);return size*(.14+.86*(1-rem*rem*rem));
    }
    static float[] waterColor(double age,double alpha) {
        double mix=clamp(age*.65);
        return new float[]{(float)((67+(168-67)*mix)/255),(float)((200+(244-200)*mix)/255),1,(float)alpha};
    }
    static float[] point(WaterJumpMesh.Point p) {return new float[]{(float)p.x(),(float)p.y()+.028f,(float)p.z()};}
    static void waveFace(WaterJumpMesh.Face face,double age) {
        WaterJumpMesh.Point[] corners={face.a(),face.b(),face.c(),face.d()};
        float[] color=waterColor(age,face.opacity()*INTENSITY);
        FloatBuffer data=BufferUtils.createFloatBuffer(72);
        for(int i:TRIANGLES) {
            data.put(point(corners[i])).put(i==0||i==3?face.uStart():face.uEnd()).put(i<2?0:1);
            data.put(color).put(0).put(1).put(0);
        }
        send(data);
    }
    static double distance(WaterJumpMesh.Face face) {
        double x=(face.a().x()+face.b().x()+face.c().x()+face.d().x())*.25-eye[0];
        double y=(face.a().y()+face.b().y()+face.c().y()+face.d().y())*.25-eye[1];
        double z=(face.a().z()+face.b().z()+face.c().z()+face.d().z())*.25-eye[2];
        return x*x+y*y+z*z;
    }
    static void drawWater(double age,String filename)throws Exception {
        waterCamera();sceneTime((float)(6+age*1.2));
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,WATER_WIDTH,WATER_HEIGHT);
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);
        glClearColor(.025f,.032f,.044f,1);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glUseProgram(fill);horizontal(0,-.06f,0,12,new float[]{.04f,.045f,.055f,1});
        for(int x=-9;x<9;x++)for(int z=-9;z<9;z++) {
            float tone=((x+z)&1)==0?.065f:.075f;
            horizontal(x*.50f,-.05f,z*.50f,.247f,new float[]{tone*.80f,tone,tone*1.20f,1});
        }
        glEnable(GL_BLEND);glDepthMask(false);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(splashProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,splashTexture);
        horizontal(0,.028f,0,(float)radius(age,SIZE),waterColor(age,opacity(age)*INTENSITY*.45));
        double echo=(age-.13)/.87;
        horizontal(0,.037f,0,(float)radius(echo,SIZE*.77),waterColor(age,opacity(echo)*INTENSITY*.27*.20));
        glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(waterProgram);glBindTexture(GL_TEXTURE_2D,prismTexture);
        List<WaterJumpMesh.Face> faces=new ArrayList<>(WaterJumpMesh.build(age,SIZE,PHASE,144));
        faces.sort(Comparator.comparingDouble(WaterShowcase::distance).reversed());
        for(var face:faces)waveFace(face,age);
        ByteBuffer pixels=BufferUtils.createByteBuffer(WATER_WIDTH*WATER_HEIGHT*4);
        glReadPixels(0,0,WATER_WIDTH,WATER_HEIGHT,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        STBImageWrite.stbi_flip_vertically_on_write(true);
        if(!STBImageWrite.stbi_write_png(output.resolve(filename).toString(),WATER_WIDTH,WATER_HEIGHT,4,pixels,WATER_WIDTH*4))throw new AssertionError("PNG failed");
        if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL water-preview error");
        System.out.println("GPU water preview age="+age+", actual raised faces="+faces.size()+"; "+filename);
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);changedResources=resources;Files.createDirectories(output);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();if(!glfwInit())throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(WATER_WIDTH,WATER_HEIGHT,"3D water wave fixture (hidden)",0,0);if(window==0)throw new AssertionError("No GL context");glfwMakeContextCurrent(window);GL.createCapabilities();
        fill=material("unified_chams_fill",null);waterProgram=program("water");splashProgram=program("splash");
        prismTexture=texture("assets/celeste/textures/visual/plasma_flow.png");splashTexture=texture("assets/celeste/textures/visual/jump/splash.png");
        transformBuffer=buffer(0,BufferUtils.createByteBuffer(160));projectionBuffer=buffer(1,BufferUtils.createByteBuffer(64));globals=buffer(2,BufferUtils.createByteBuffer(64));
        glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);int[] sizes={3,2,4,3};int offset=0;
        for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}glDisable(GL_CULL_FACE);
        scene=fbo(attachment(false,WATER_WIDTH,WATER_HEIGHT),attachment(true,WATER_WIDTH,WATER_HEIGHT));
        drawWater(.23,"water-rising.png");drawWater(.48,"water-spreading.png");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
        System.out.println("PASS actual production raised mesh + shaders, alpha blend, depth test without depth writes. Out-of-game fixture, not Minecraft.");
    }
}
