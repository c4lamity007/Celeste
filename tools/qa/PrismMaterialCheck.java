import java.nio.*;
import java.nio.file.*;
import java.util.zip.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Production plasma box material: float-output checks plus a six-face perspective fixture. */
public class PrismMaterialCheck extends ImpactCheck {
    static int transforms, projection;
    static Material prism;
    static int compile(Path live,Path stage)throws Exception {
        String vertex=Files.readString(live.resolve("assets/celeste/shaders/core/world_vfx.vsh"));
        Path file=stage.resolve("assets/celeste/shaders/core/world_prism.fsh");
        if(!Files.exists(file))file=live.resolve("assets/celeste/shaders/core/world_prism.fsh");
        int vs=shader(GL_VERTEX_SHADER,preprocess(vertex).replace("#version 330","#version 330\n#define ECHO_MESH 0"));
        int fs=shader(GL_FRAGMENT_SHADER,preprocess(Files.readString(file)));
        int p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);glBindAttribLocation(p,0,"Position");glBindAttribLocation(p,1,"UV0");glBindAttribLocation(p,2,"Color");glLinkProgram(p);
        if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new AssertionError(glGetProgramInfoLog(p));
        String[] blocks={"DynamicTransforms","Projection","Globals"};for(int i=0;i<3;i++){int b=glGetUniformBlockIndex(p,blocks[i]);if(b!=GL_INVALID_INDEX)glUniformBlockBinding(p,b,i);}
        int gt=glGetUniformIndices(p,"GameTime");if(gt==GL_INVALID_INDEX||glGetActiveUniformsi(p,gt,GL_UNIFORM_OFFSET)!=44)throw new AssertionError("Wrong Globals GameTime offset");
        glUseProgram(p);glUniform1i(glGetUniformLocation(p,"Sampler0"),0);glDeleteShader(vs);glDeleteShader(fs);return p;
    }
    static void identity() {
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,0);matrix(data,96);for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        glBindBuffer(GL_UNIFORM_BUFFER,transforms);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer proj=BufferUtils.createByteBuffer(64);matrix(proj,0);glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,proj);
    }
    static float[] frameAt(float seconds,float alpha) {
        glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,TILE,TILE);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDepthMask(true);glClearDepth(.63);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDepthMask(false);
        draw(prism,seconds,alpha,prism.texture());FloatBuffer values=BufferUtils.createFloatBuffer(TILE*TILE*4);glReadPixels(0,0,TILE,TILE,GL_RGBA,GL_FLOAT,values);float[] result=new float[values.remaining()];values.get(result);
        for(float value:result)if(!Float.isFinite(value)||value<0||value>1.000001f)throw new AssertionError("Prism RGBA not finite/bounded");
        FloatBuffer depth=BufferUtils.createFloatBuffer(TILE*TILE);glReadPixels(0,0,TILE,TILE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);for(int i=0;i<depth.limit();i++)if(Math.abs(depth.get(i)-.63f)>1e-6)throw new AssertionError("Prism depth write");
        return result;
    }
    static void perspective() {
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,96);for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        float c=(float)Math.cos(.55),s=(float)Math.sin(.55),cx=(float)Math.cos(.20),sx=(float)Math.sin(.20);
        float[] view={c,sx*s,-cx*s,0,0,cx,sx,0,s,-sx*c,cx*c,0,0,0,-4.2f,1};for(int i=0;i<16;i++)data.putFloat(i*4,view[i]);
        glBindBuffer(GL_UNIFORM_BUFFER,transforms);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer proj=BufferUtils.createByteBuffer(64);float f=1f/(float)Math.tan(Math.toRadians(27.5));proj.putFloat(0,f).putFloat(20,f).putFloat(40,-1.002002f).putFloat(44,-1).putFloat(56,-.2002002f);
        glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,proj);
    }
    static void box(float seconds) {
        box(seconds,TILE,TILE);
    }
    static void box(float seconds,int width,int height) {
        ByteBuffer time=BufferUtils.createByteBuffer(64);time.putFloat(32,width).putFloat(36,height).putFloat(44,seconds/1200f);glBindBuffer(GL_UNIFORM_BUFFER,globals);glBufferSubData(GL_UNIFORM_BUFFER,0,time);
        float[][] v={{-.55f,-1,-.4f},{.55f,-1,-.4f},{.55f,-1,.4f},{-.55f,-1,.4f},{-.55f,1,-.4f},{.55f,1,-.4f},{.55f,1,.4f},{-.55f,1,.4f}};
        int[][] faces={{0,1,5,4},{2,3,7,6},{3,0,4,7},{1,2,6,5},{4,5,6,7},{3,2,1,0}};
        int[] triangles={0,1,2,0,2,3};float[][] uv={{0,0},{1,0},{1,1},{0,1}};
        FloatBuffer data=BufferUtils.createFloatBuffer(6*6*12);
        for(int[] face:faces)for(int corner:triangles){float[] xyz=v[face[corner]];data.put(xyz).put(uv[corner]).put(.16f).put(.72f).put(1).put(.72f).put(0).put(0).put(1);}
        data.flip();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,data,GL_STREAM_DRAW);glUseProgram(prism.program());glBindTexture(GL_TEXTURE_2D,prism.texture());glDrawArrays(GL_TRIANGLES,0,36);
    }
    public static void main(String[] args)throws Exception {
        Path live=Path.of(args[0]),stage=Path.of(args[3]);resources=stage;minecraft=new ZipFile(args[1]);output=Path.of(args[2]);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();if(!glfwInit())throw new AssertionError("No GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(TILE,TILE,"Prism GPU fixture (hidden)",0,0);if(window==0)throw new AssertionError("No OpenGL");glfwMakeContextCurrent(window);GL.createCapabilities();
        int program=compile(live,stage);if(!Files.exists(resources.resolve("assets/celeste/textures/visual/plasma_flow.png")))resources=live;
        prism=new Material("prism",program,texture("assets/celeste/textures/visual/plasma_flow.png"),"plasma_flow.png");
        transforms=buffer(0,BufferUtils.createByteBuffer(160));projection=buffer(1,BufferUtils.createByteBuffer(64));globals=buffer(2,BufferUtils.createByteBuffer(64));identity();
        glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);int[] sizes={3,2,4,3};int offset=0;for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}glDisable(GL_CULL_FACE);
        frame=framebuffer(TILE,TILE,true);float[] first=frameAt(3,.72f),fractional=frameAt(3+1f/60,.72f),later=frameAt(3.5f,.72f);int changes=0,frameChanges=0;float peak=0;
        for(int p=0;p<TILE*TILE;p++){boolean changed=false,small=false;for(int c=0;c<4;c++){changed|=Math.abs(first[p*4+c]-later[p*4+c])>1f/255;small|=Math.abs(first[p*4+c]-fractional[p*4+c])>1e-5;}if(changed)changes++;if(small)frameChanges++;peak=Math.max(peak,first[p*4+3]);}
        if(changes<1000||frameChanges<1000)throw new AssertionError("Prism is not frame animated");for(float value:frameAt(4,0))if(value!=0)throw new AssertionError("Zero tint alpha leak");
        if(first[3]<.2f)throw new AssertionError("Expected luminous box boundary absent");
        int width=TILE*3,height=TILE*2;framebuffer(width,height,false);glDisable(GL_DEPTH_TEST);glDepthMask(false);glClearColor(.025f,.03f,.045f,1);glClear(GL_COLOR_BUFFER_BIT);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE);
        float[] times={3,3+1f/60,3.5f};for(int col=0;col<3;col++){identity();glViewport(col*TILE,TILE,TILE,TILE);draw(prism,times[col],.72f,prism.texture(),width,height);perspective();glViewport(col*TILE,0,TILE,TILE);box(times[col],width,height);}
        ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);STBImageWrite.stbi_flip_vertically_on_write(true);if(!STBImageWrite.stbi_write_png(output.resolve("prism-gpu-preview.png").toString(),width,height,4,pixels,width*4))throw new AssertionError("PNG failed");if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL error");
        System.out.println("PASS actual prism shader/image; finite bounded RGBA; zero-alpha invisible; preserved depth pixels="+(TILE*TILE)+"; frame animation="+frameChanges+"; half-second="+changes+"; max alpha="+peak);
        System.out.println("GPU PREVIEW: top=flat material; bottom=six-face perspective box; columns=3s,3+1/60s,3.5s. NOT an in-game screenshot.");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
    }
}
