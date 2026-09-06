import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Node;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.VolumeSurfaceMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.TargetAuraShape;
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

/** Actual closed-volume mesh and shaders on neutral fixtures, not a Minecraft capture. */
public class VolumeShowcase extends VisualShowcase {
    static final int W=1440,H=1080,CW=W/2,CH=H/2;
    static int volumeProgram,pulseProgram,pulseTexture,volumeComposite,volumeMask,volumeMaskColor;
    static int terrainProgram,grassTop,grassSide,dirtTexture,stoneTexture,skinTexture,softTexture;
    static float[] eye;
    static double materialTime;
    static boolean ice;
    static final Point UP=new Point(0,1,0);
    static final double TAU=Math.PI*2;

    static void view(float[] camera,float[] target,float degrees) {
        eye=camera;
        float[] f=unit(new float[]{target[0]-eye[0],target[1]-eye[1],target[2]-eye[2]});
        float[] s=unit(cross(f,new float[]{0,1,0})),u=cross(s,f);
        float[] v={s[0],u[0],-f[0],0,s[1],u[1],-f[1],0,s[2],u[2],-f[2],0,-dot(s,eye),-dot(u,eye),dot(f,eye),1};
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,96);
        for(int i=0;i<16;i++)data.putFloat(i*4,v[i]);for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        glBindBuffer(GL_UNIFORM_BUFFER,transformBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer proj=BufferUtils.createByteBuffer(64);float scale=1f/(float)Math.tan(Math.toRadians(degrees));
        proj.putFloat(0,scale*CH/CW).putFloat(20,scale).putFloat(40,-1.002002f).putFloat(44,-1).putFloat(56,-.2002002f);
        glBindBuffer(GL_UNIFORM_BUFFER,projectionBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,proj);
    }
    static int color(double progress,double alpha) {
        double blend=.5+.5*Math.sin(materialTime*.65+progress*TAU);
        int first=ice?0x79DCFF:0xFF4FA3,second=ice?0xD3B9FF:0xA987FF,result=0;
        for(int shift=0;shift<=16;shift+=8)result|=(int)Math.round((first>>>shift&255)*(1-blend)+(second>>>shift&255)*blend)<<shift;
        return ((int)(Math.max(0,Math.min(1,alpha))*255)<<24)|result;
    }
    static float[] rgba(int color) {
        return new float[]{(color>>>16&255)/255f,(color>>>8&255)/255f,(color&255)/255f,(color>>>24&255)/255f};
    }
    static List<Node> ring(double cx,double y,double cz,double radius,double tilt,double spin,
                           double width,double depth,int segments,double alpha) {
        List<Node> nodes=new ArrayList<>();
        for(int i=0;i<segments;i++) {
            double a=TAU*i/segments;
            nodes.add(new Node(new Point(cx+Math.cos(a)*radius,y+Math.sin(a+spin)*tilt,cz+Math.sin(a)*radius),
                width,depth,color((double)i/segments,alpha)));
        }
        return nodes;
    }
    static VolumeSurfaceMesh.Point surfacePoint(Point point) {
        return new VolumeSurfaceMesh.Point(point.x(),point.y(),point.z());
    }
    static FloatBuffer surfaceData(List<Face> faces,float alpha,boolean reverse,boolean alternate) {
        if(faces.size()>768)throw new AssertionError("Unbounded preview fixture");
        List<VolumeSurfaceMesh.Face> surfaces=new ArrayList<>();
        for(Face f:faces)surfaces.add(new VolumeSurfaceMesh.Face(surfacePoint(f.a()),surfacePoint(f.b()),surfacePoint(f.c()),surfacePoint(f.d())));
        List<VolumeSurfaceMesh.Vertex> vertices=VolumeSurfaceMesh.smooth(surfaces);
        if(vertices.size()!=faces.size()*4)throw new AssertionError("Unexpected production welded-normal vertex count");
        FloatBuffer data=BufferUtils.createFloatBuffer(Math.max(1,faces.size())*72);
        int[] triangles=alternate?new int[]{0,1,3,1,2,3}:TRIANGLES;
        for(int index=0;index<faces.size();index++) {
            int at=reverse?faces.size()-index-1:index;Face face=faces.get(at);
            for(int i:triangles) {
                VolumeSurfaceMesh.Vertex vertex=vertices.get(at*4+i);
                VolumeSurfaceMesh.Point p=vertex.position(),n=vertex.normal();
                data.put((float)p.x()).put((float)p.y()).put((float)p.z());
                float[] color=rgba(i==0||i==3?face.color():face.endColor());
                // Negative override keeps the feature's normal opacity/fade.
                data.put(i==0||i==3?face.uStart():face.uEnd()).put(i<2?0:1)
                    .put(color[0]).put(color[1]).put(color[2]).put(alpha<0?color[3]:alpha)
                    .put((float)n.x()).put((float)n.y()).put((float)n.z());
            }
        }
        return data;
    }
    static void volumes(List<Face> faces) {
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        glBindFramebuffer(GL_FRAMEBUFFER,volumeMask);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
        glDepthMask(true);glDepthFunc(GL_LEQUAL);glClearColor(0,0,0,0);glClearDepth(1);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glUseProgram(volumeProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,prismTexture);
        send(surfaceData(faces,-1,false,false));
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,W,H);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(volumeComposite);glBindTexture(GL_TEXTURE_2D,volumeMaskColor);glDrawArrays(GL_TRIANGLES,0,3);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
    }
    static byte[] gameAsset(String path)throws Exception {
        var entry=minecraft.getEntry("assets/minecraft/"+path);
        if(entry==null)throw new IllegalStateException("Missing cached Minecraft fixture asset: "+path);
        try(var stream=minecraft.getInputStream(entry)){return stream.readAllBytes();}
    }
    static int gameTexture(String path)throws Exception {
        byte[] png=gameAsset("textures/"+path);ByteBuffer data=BufferUtils.createByteBuffer(png.length);data.put(png).flip();
        int[] width={0},height={0},channels={0};ByteBuffer pixels=STBImage.stbi_load_from_memory(data,width,height,channels,4);
        if(pixels==null)throw new IllegalStateException("Cached Minecraft PNG: "+STBImage.stbi_failure_reason());
        int texture=textureData(width[0],height[0],pixels);STBImage.stbi_image_free(pixels);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        return texture;
    }
    static void fixtureResources() {
        if(terrainProgram!=0)return;
        try {
            int vertex=shader(GL_VERTEX_SHADER,new String(gameAsset("shaders/core/position_tex_color.vsh"),java.nio.charset.StandardCharsets.UTF_8));
            int fragment=shader(GL_FRAGMENT_SHADER,new String(gameAsset("shaders/core/position_tex_color.fsh"),java.nio.charset.StandardCharsets.UTF_8));
            terrainProgram=glCreateProgram();glAttachShader(terrainProgram,vertex);glAttachShader(terrainProgram,fragment);
            glBindAttribLocation(terrainProgram,0,"Position");glBindAttribLocation(terrainProgram,1,"UV0");glBindAttribLocation(terrainProgram,2,"Color");
            glLinkProgram(terrainProgram);if(glGetProgrami(terrainProgram,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(terrainProgram));
            String[] blocks={"DynamicTransforms","Projection"};for(int i=0;i<blocks.length;i++)glUniformBlockBinding(terrainProgram,glGetUniformBlockIndex(terrainProgram,blocks[i]),i);
            glUseProgram(terrainProgram);glUniform1i(glGetUniformLocation(terrainProgram,"Sampler0"),0);glDeleteShader(vertex);glDeleteShader(fragment);
            grassTop=gameTexture("block/grass_block_top.png");grassSide=gameTexture("block/grass_block_side.png");
            dirtTexture=gameTexture("block/dirt.png");stoneTexture=gameTexture("block/stone.png");
            skinTexture=gameTexture("entity/player/wide/steve.png");softTexture=texture("assets/celeste/textures/visual/fake_lag/soft.png");
        } catch(Exception failure) {throw new IllegalStateException("Minecraft-context fixture initialization failed",failure);}
    }
    static void texturedFace(float[][] corners,int[] face,float u0,float v0,float u1,float v1,float[] tint) {
        FloatBuffer data=BufferUtils.createFloatBuffer(72);
        for(int i:TRIANGLES)data.put(corners[face[i]]).put(i==0||i==3?u0:u1).put(i<2?v1:v0).put(tint).put(0).put(1).put(0);
        send(data);
    }
    static void terrainBlock(float x,float y,float z,boolean grass) {
        float[][] corners={{x,y,z},{x+1,y,z},{x+1,y,z+1},{x,y,z+1},
            {x,y+1,z},{x+1,y+1,z},{x+1,y+1,z+1},{x,y+1,z+1}};
        for(int face=0;face<FACES.length;face++) {
            int texture=grass?(face==4?grassTop:face==5?dirtTexture:grassSide):stoneTexture;
            glBindTexture(GL_TEXTURE_2D,texture);float shade=face==4?1:face==5?.5f:face<2?.8f:.65f;
            float[] tint=grass&&face==4?new float[]{.55f,.77f,.32f,1}:new float[]{shade,shade,shade,1};
            texturedFace(corners,FACES[face],0,0,1,1,tint);
        }
    }
    static void skinPart(float cx,float cy,float cz,float hx,float hy,float hz,float px,float py,float pz,
                         float pitch,float roll,int u,int v,int w,int h,int d) {
        float[][] corners={{-hx,-hy,-hz},{hx,-hy,-hz},{hx,-hy,hz},{-hx,-hy,hz},
            {-hx,hy,-hz},{hx,hy,-hz},{hx,hy,hz},{-hx,hy,hz}};
        for(int i=0;i<corners.length;i++) {
            float[] p=corners[i],r=rotate(p[0]+cx-px,p[1]+cy-py,p[2]+cz-pz,pitch,roll,0);
            r=rotate(r[0]+px,r[1]+py,r[2]+pz,0,0,-.22f);
            corners[i]=new float[]{r[0]*.90f,r[1]*.90f,r[2]*.90f};
        }
        // Standard 64x64 cuboid layout; base skin only, same block pose/dimensions.
        int[][] uv={{u+d+w+d,v+d,u+d+w+d+w,v+d+h},{u+d+w,v+d,u+d,v+d+h},
            {u,v+d,u+d,v+d+h},{u+d+w,v+d,u+d+w+d,v+d+h},
            {u+d,v,u+d+w,v+d},{u+d+w,v,u+d+w+w,v+d}};
        for(int face=0;face<FACES.length;face++) {
            float shade=face==4?1:face==5?.60f:face<2?.92f:.80f;
            texturedFace(corners,FACES[face],uv[face][0]/64f,uv[face][1]/64f,uv[face][2]/64f,uv[face][3]/64f,
                new float[]{shade,shade,shade,1});
        }
    }
    static void minecraftPlayer() {
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthMask(true);glUseProgram(terrainProgram);glBindTexture(GL_TEXTURE_2D,skinTexture);
        skinPart(0,1.76f,0,.25f,.25f,.25f,0,1.51f,0,0,-.06f,0,0,8,8,8);
        skinPart(0,1.13f,0,.25f,.38f,.125f,0,1.5f,0,0,0,16,16,8,12,4);
        skinPart(-.375f,1.13f,0,.126f,.38f,.125f,-.375f,1.50f,0,-.22f,-.14f,40,16,4,12,4);
        skinPart(.375f,1.13f,0,.126f,.38f,.125f,.375f,1.50f,0,.29f,.16f,32,48,4,12,4);
        skinPart(-.126f,.375f,0,.126f,.375f,.125f,-.126f,.75f,0,.23f,0,0,16,4,12,4);
        skinPart(.126f,.375f,0,.126f,.375f,.125f,.126f,.75f,0,-.23f,0,16,48,4,12,4);
    }
    static void floor() {
        fixtureResources();
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(GL_LEQUAL);
        glUseProgram(terrainProgram);glActiveTexture(GL_TEXTURE0);
        for(int x=-6;x<6;x++)for(int z=-6;z<6;z++) {
            boolean stone=(x==-3&&z>=-2&&z<=1)||(x==-2&&z==-2);
            glBindTexture(GL_TEXTURE_2D,stone?stoneTexture:grassTop);
            horizontal(x+.5f,-.050f,z+.5f,.5f,stone?new float[]{.82f,.82f,.82f,1}:new float[]{.55f,.77f,.32f,1});
        }
        terrainBlock(-2.6f,-.05f,-2.3f,true);terrainBlock(-3.6f,-.05f,-2.3f,false);
    }
    static void ground(float x,float z,float radius,float alpha) {
        groundLayer(x,.01f,z,radius,alpha,false);
    }
    static void groundLayer(float x,float y,float z,float radius,float alpha,boolean soft) {
        glDepthMask(false);glDisable(GL_DEPTH_TEST);glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(soft?terrainProgram:pulseProgram);glBindTexture(GL_TEXTURE_2D,soft?softTexture:pulseTexture);
        float[] tint=rgba(color(0,1));tint[3]=alpha;horizontal(x,y,z,radius,tint);
    }
    static void panel(int index,float time) {
        int panelX=(index%2)*CW,panelY=(1-index/2)*CH;
        glViewport(panelX,panelY,CW,CH);glEnable(GL_SCISSOR_TEST);glScissor(panelX,panelY,CW,CH);
        glDepthMask(true);glClearColor(.52f,.73f,.91f,1);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
        sceneTime(time);
        materialTime=time;ice=index==1||index==3;
        List<Face> faces=new ArrayList<>();
        if(index<2) {
            view(new float[]{2.4f,2.0f,3.65f},new float[]{0,.89f,0},21);
            // The fixture uses the opposite yaw convention to Minecraft's torso.
            double bodyYaw=TargetAuraShape.bodyYawRadians(Math.toDegrees(.22),Math.toDegrees(.22),1);
            floor();minecraftPlayer();
            groundLayer(0,.010f,0,.672f*1.52f,.86f*.65f*.12f,true);
            groundLayer(0,.018f,0,.672f*1.34f,.86f*.65f*.50f,false);
            groundLayer(0,.025f,0,.672f*(.82f+.06f*(float)Math.sin(time*1.8*.72)),.86f*.65f*.24f,false);
            if(index==0) {
                for(int layer=0;layer<2;layer++) {
                    materialTime=time+layer*1.4;
                    List<TargetAuraShape.Point> path=TargetAuraShape.path("Orbit",layer,.672,1.8,time*1.8,bodyYaw,true);
                    List<Node> nodes=new ArrayList<>();
                    for(int i=0;i<path.size();i++) {
                        double p=i/(double)path.size(),fade=.86+.14*Math.pow(Math.sin(TAU*p),2);
                        TargetAuraShape.Point point=path.get(i);
                        nodes.add(new Node(new Point(point.x(),point.y(),point.z()),
                            layer==0?.16:.13,layer==0?.12:.10,color(p,.86*(layer==0?1:.82)*fade*.902)));
                    }
                    faces.addAll(SolidVisualMesh.tube(nodes,UP,4,layer==0?96:64,true));
                }
            } else {
                for(int strand=0;strand<2;strand++) {
                    materialTime=time+strand;
                    List<TargetAuraShape.Point> path=TargetAuraShape.path("Helix",strand,.672,1.8,time*1.8,bodyYaw,true);
                    List<Node> nodes=new ArrayList<>();
                    for(int i=0;i<path.size();i++) {
                        double p=i/(double)(path.size()-1);
                        double taper=.64+.36*Math.sin(p*Math.PI);
                        TargetAuraShape.Point point=path.get(i);
                        nodes.add(new Node(new Point(point.x(),point.y(),point.z()),
                            .14*taper,.105*taper,color(p,.86*(.46+.54*Math.sin(p*Math.PI))*.902)));
                    }
                    faces.addAll(SolidVisualMesh.tube(nodes,UP,4,80,false));
                }
            }
        } else if(index==2) {
            view(new float[]{1.8f,1.70f,2.6f},new float[]{0,.38f,0},22);floor();
            for(int i=0;i<2;i++) {
                double x=(i-.5)*.90,z=(i-.5)*-.45;
                ground((float)x,(float)z,(float)(1.80*.245/.90),.46f*.76f*.45f);
                faces.addAll(SolidVisualMesh.diamond(new Point(x,.51+.06*Math.sin(time*1.5+i),z),.245,.417,color(i*.42,.76*.81),8));
                faces.addAll(SolidVisualMesh.tube(ring(x,.185,z,.47,0,time,.085,.095,8,.76*.7425),UP,4,32,true));
            }
        } else {
            view(new float[]{2.0f,2.65f,3.7f},new float[]{0,.52f,0},22);floor();
            List<Node> nodes=new ArrayList<>();
            for(int i=0;i<=32;i++) {
                double p=i/32.0;
                nodes.add(new Node(new Point(-1.27+p*2.27,.09+(1-p)*1.44+Math.sin(p*Math.PI)*.77,-.4+p*.56),
                    .043+.010*p,.037+.010*p,color(p*.75,.76)));
            }
            faces.addAll(SolidVisualMesh.tube(nodes,UP,4,128,false));
            float impactRadius=.36f+.018f*(float)Math.sin(time*3);
            groundLayer(1,.010f,.16f,impactRadius*2.10f,.90f*.55f*.10f,true);
            groundLayer(1,.018f,.16f,impactRadius*1.60f,.90f*.55f*.36f,false);
            faces.addAll(SolidVisualMesh.tube(ring(1,.138,.16,.36+.018*Math.sin(time*3),0,0,.10,.115,12,.90*.88),UP,4,48,true));
            faces.addAll(SolidVisualMesh.diamond(new Point(1,.178,.16),.14,.15,color(.6,.89),8));
        }
        volumes(faces);
    }
    static void captureVolumes(float time,String filename) {
        glBindFramebuffer(GL_FRAMEBUFFER,scene);glViewport(0,0,W,H);
        glDepthMask(true);glClearColor(.024f,.030f,.04f,1);glClearDepth(1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        for(int i=0;i<4;i++)panel(i,time);
        ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        STBImageWrite.stbi_flip_vertically_on_write(true);
        if(!STBImageWrite.stbi_write_png(output.resolve(filename).toString(),W,H,4,pixels,W*4))throw new AssertionError("PNG write failed");
        if(glGetError()!=GL_NO_ERROR)throw new AssertionError("Volume showcase GL error");
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);changedResources=resources;Files.createDirectories(output);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();if(!glfwInit())throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Celeste volume fixture (hidden)",0,0);if(window==0)throw new AssertionError("No GL context");glfwMakeContextCurrent(window);GL.createCapabilities();
        fill=material("unified_chams_fill",null);volumeProgram=program("volume");pulseProgram=program("pulse");
        volumeComposite=material("unified_volume_composite",null);
        prismTexture=texture("assets/celeste/textures/visual/plasma_flow.png");pulseTexture=texture("assets/celeste/textures/visual/fake_lag/halo.png");
        transformBuffer=buffer(0,BufferUtils.createByteBuffer(160));projectionBuffer=buffer(1,BufferUtils.createByteBuffer(64));globals=buffer(2,BufferUtils.createByteBuffer(64));
        glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);int[] sizes={3,2,4,3};int offset=0;
        for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}glDisable(GL_CULL_FACE);
        volumeMaskColor=attachment(false,W,H);volumeMask=fbo(volumeMaskColor,attachment(true,W,H));
        scene=fbo(attachment(false,W,H),attachment(true,W,H));captureVolumes(3.2f,"volume-showcase.png");captureVolumes(3.7f,"volume-showcase-later.png");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
        System.out.println("PASS production TargetAuraShape + SolidVisualMesh + VolumeSurfaceMesh normals + unified VOLUME shaders with normal feature opacity/fades. Cached Minecraft grass/dirt/stone textures and base Steve skin provide context; body-fit orbit, helix, loot, trajectory remain offscreen fixtures, not Minecraft screenshots. No image post-processing.");
    }
}
