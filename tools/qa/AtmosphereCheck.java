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
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;

/** Actual production shader GPU fixtures. No Minecraft launch, artwork, or post-process glow. */
public final class AtmosphereCheck extends ImpactCheck {
    static final int W=Integer.getInteger("celeste.atmosphere.width",384), H=W*2/3;
    static final double TAU=Math.PI*2;
    static int dynamic,projection,fog,sky,mesh,glow,fixture;
    static long assertions,depthPixels;
    static final float[] FOG={.18f,.24f,.30f,1};
    static void require(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
    static float[] identity() { float[] m=new float[16];for(int i=0;i<16;i+=5)m[i]=1;return m; }
    static float[] multiply(float[] a,float[] b) {
        float[] r=new float[16];for(int c=0;c<4;c++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)r[c*4+row]+=a[k*4+row]*b[c*4+k];return r;
    }
    static float[] transpose(float[] a) {float[] r=new float[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++)r[i*4+j]=a[j*4+i];return r;}
    static float[] rotation(double yaw,double pitch) {
        float[] y=identity(),x=identity();float cy=(float)Math.cos(yaw),sy=(float)Math.sin(yaw),cx=(float)Math.cos(pitch),sx=(float)Math.sin(pitch);
        y[0]=cy;y[2]=-sy;y[8]=sy;y[10]=cy;x[5]=cx;x[6]=sx;x[9]=-sx;x[10]=cx;return multiply(x,y);
    }
    static float[] perspective() {
        float[] p=new float[16];float f=(float)(1/Math.tan(Math.toRadians(75)/2));
        p[0]=f*H/W;p[5]=f;p[10]=-1.002002f;p[11]=-1;p[14]=-.2002002f;return p;
    }
    static void writeMatrix(ByteBuffer data,int at,float[] matrix) {for(int i=0;i<16;i++)data.putFloat(at+i*4,matrix[i]);}
    static void transforms(float[] view,float[] proj,float[] color,float rain) {
        ByteBuffer data=BufferUtils.createByteBuffer(160);writeMatrix(data,0,view);writeMatrix(data,96,identity());
        for(int i=0;i<4;i++)data.putFloat(64+i*4,color[i]);data.putFloat(80,rain);
        glBindBuffer(GL_UNIFORM_BUFFER,dynamic);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        data=BufferUtils.createByteBuffer(64);writeMatrix(data,0,proj);glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
    }
    static void fog(float start,float end) {
        ByteBuffer data=BufferUtils.createByteBuffer(48);for(int i=0;i<4;i++)data.putFloat(i*4,FOG[i]);
        data.putFloat(16,start).putFloat(20,end).putFloat(24,start).putFloat(28,end).putFloat(32,end).putFloat(36,end);
        glBindBuffer(GL_UNIFORM_BUFFER,fog);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
    }
    static void fog(CustomFogMath.Fog value) {
        ByteBuffer data=BufferUtils.createByteBuffer(48);
        data.putFloat(0,(float)value.color().r()).putFloat(4,(float)value.color().g()).putFloat(8,(float)value.color().b()).putFloat(12,(float)value.color().a());
        data.putFloat(16,(float)value.environmentalStart()).putFloat(20,(float)value.environmentalEnd()).putFloat(24,(float)value.renderDistanceStart())
            .putFloat(28,(float)value.renderDistanceEnd()).putFloat(32,(float)value.skyEnd()).putFloat(36,(float)value.cloudEnd());
        glBindBuffer(GL_UNIFORM_BUFFER,fog);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
    }
    static int link(String vertexName,String fragmentName) throws Exception {
        Path core=resources.resolve("assets/celeste/shaders/core");
        return linkSource(preprocess(Files.readString(core.resolve(vertexName))),preprocess(Files.readString(core.resolve(fragmentName))),fragmentName);
    }
    static int linkSource(String vertex,String fragment,String name) {
        int vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment),p=glCreateProgram();
        glAttachShader(p,vs);glAttachShader(p,fs);glBindAttribLocation(p,0,"Position");glBindAttribLocation(p,1,"UV0");glBindAttribLocation(p,2,"Color");glBindAttribLocation(p,3,"Normal");glLinkProgram(p);
        require(glGetProgrami(p,GL_LINK_STATUS)!=0,name+": "+glGetProgramInfoLog(p));glDeleteShader(vs);glDeleteShader(fs);
        String[] blocks={"DynamicTransforms","Projection","Fog"};
        for(int i=0;i<blocks.length;i++){int block=glGetUniformBlockIndex(p,blocks[i]);if(block!=GL_INVALID_INDEX)glUniformBlockBinding(p,block,i);}
        return p;
    }
    static void layout(int p,String uniform,int expected) {
        int i=glGetUniformIndices(p,uniform);require(i!=GL_INVALID_INDEX,"Missing native uniform "+uniform);
        require(glGetActiveUniformsi(p,i,GL_UNIFORM_OFFSET)==expected,"Native offset mismatch for "+uniform);
    }
    static void clear(float depth) {
        glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,W,H);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_DEPTH_TEST);
        glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(depth);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDepthMask(false);
    }
    static float[] pixels() {FloatBuffer read=BufferUtils.createFloatBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,read);float[] r=new float[read.remaining()];read.get(r);return r;}
    static void depth(float expected) {
        FloatBuffer read=BufferUtils.createFloatBuffer(W*H);glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,read);
        for(int i=0;i<read.limit();i++)require(Math.abs(read.get(i)-expected)<1e-6,"Main depth was modified at "+i);depthPixels+=read.limit();
        require(glGetError()==GL_NO_ERROR,"OpenGL error");
    }
    static void bounded(float[] rgba,boolean opaque,float max) {
        for(int i=0;i<rgba.length;i++)require(Float.isFinite(rgba[i])&&rgba[i]>=-1e-6&&rgba[i]<=(i%4==3?1.00001:max),"Nonfinite/out-of-range RGBA at "+i+": "+rgba[i]);
        if(opaque)for(int i=3;i<rgba.length;i+=4)require(rgba[i]==1,"Sky coverage/alpha seam");
    }
    record Delta(double mean,double peak,int changed) {}
    static Delta delta(float[] a,float[] b) {
        double sum=0,max=0;int changed=0;for(int i=0;i<a.length;i++){double d=Math.abs(a[i]-b[i]);sum+=d;max=Math.max(max,d);if(d>1e-6)changed++;}return new Delta(sum/a.length,max,changed);
    }
    static void close(float[] a,float[] b,double mean,double peak,String label) {
        Delta d=delta(a,b);System.out.printf(Locale.ROOT,"%s mean=%.9g peak=%.9g samples=%d%n",label,d.mean,d.peak,a.length);
        require(d.mean<=mean&&d.peak<=peak,label+" differs: "+d);
    }
    static float[] skyFrame(int mode,double phase,float stars,float rain,float[] view,float[] proj) {
        clear(.63f);transforms(view,proj,new float[]{mode,.85f,(float)phase,stars},rain);fog(1000,1024);glUseProgram(sky);glDrawArrays(GL_TRIANGLES,0,3);
        float[] result=pixels();bounded(result,true,1.00001f);depth(.63f);return result;
    }
    static void skyChecks() {
        float[] view=rotation(.2,-.40),proj=perspective();float[][] modes=new float[5][];
        for(int mode=0;mode<5;mode++) {
            modes[mode]=skyFrame(mode,.7,.7f,0,view,proj);
            float[] fractional=skyFrame(mode,.7+TAU/240/60,.7f,0,view,proj),later=skyFrame(mode,.7+TAU/240*.5,.7f,0,view,proj);
            Delta shortStep=delta(modes[mode],fractional),longStep=delta(modes[mode],later);
            require(shortStep.changed>100&&shortStep.mean>1e-7,"Sky lacks sub-tick animation: "+mode+" "+shortStep);
            require(longStep.mean>shortStep.mean*4,"Sky time does not progress smoothly: "+mode);
            float[] translated=view.clone();translated[12]=12345;translated[13]=-829;translated[14]=-.175f;
            close(modes[mode],skyFrame(mode,.7,.7f,0,translated,proj),0,0,"Camera translation invariant mode "+mode);
            float[] smooth=skyFrame(mode,.7,0,0,view,proj),r=rotation(.6,.17);
            close(smooth,skyFrame(mode,.7,0,0,multiply(r,view),multiply(proj,transpose(r))),2e-5,.001,"World-ray camera/projection decomposition mode "+mode);
            require(delta(smooth,skyFrame(mode,.7,0,0,multiply(r,view),proj)).mean>.003,"Sky is incorrectly camera-locked");
            close(skyFrame(mode,0,0,0,view,proj),skyFrame(mode,TAU,0,0,view,proj),2e-6,.0002,"240-second shader loop mode "+mode);
            close(skyFrame(mode,.7,0,0,rotation(Math.PI-1e-6,-.4),proj),skyFrame(mode,.7,0,0,rotation(-Math.PI+1e-6,-.4),proj),2e-5,.001,"World-direction longitude seam mode "+mode);
            for(double pitch:new double[]{-Math.PI/2,Math.PI/2})skyFrame(mode,.7,.7f,1,rotation(0,pitch),proj);
            float[] horizon=skyFrame(mode,.7,.7f,0,identity(),proj);
            for(int y=0;y<H/2;y++)for(int x=0;x<W;x++)for(int c=0;c<3;c++)require(horizon[(y*W+x)*4+c]==FOG[c],"Horizon does not match native fog");
            require(delta(modes[mode],skyFrame(mode,.7,.7f,1,view,proj)).mean>.003,"Rain coverage does not affect sky");
            System.out.printf(Locale.ROOT,"PASS sky mode=%d changed(1/60s)=%d mean=%.8g changed(0.5s)=%d mean=%.8g%n",mode,shortStep.changed,shortStep.mean,longStep.changed,longStep.mean);
        }
        for(int a=0;a<modes.length;a++)for(int b=a+1;b<modes.length;b++)require(delta(modes[a],modes[b]).mean>.01,"Sky modes are visually identical");
    }
    static void vertex(FloatBuffer b,double x,double y,double z,float u,float v,float[] color,double nx,double ny,double nz) {
        b.put((float)x).put((float)y).put((float)z).put(u).put(v);for(float c:color)b.put(c);b.put((float)nx).put((float)ny).put((float)nz);
    }
    static void drawVertices(int program,FloatBuffer vertices) {
        vertices.flip();glUseProgram(program);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,vertices.remaining()/12);
    }
    static float[] ambientQuad(int program,float alpha,float z,float sceneDepth,boolean farFog) {
        clear(sceneDepth);transforms(identity(),identity(),new float[]{1,1,1,1},0);fog(farFog?0:1000,farFog?.1f:1024);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);FloatBuffer v=BufferUtils.createFloatBuffer(72);
        int[] corners={0,1,2,0,2,3};float[][] xy={{-1,-1},{1,-1},{1,1},{-1,1}};
        for(int corner:corners){float x=xy[corner][0],y=xy[corner][1];vertex(v,x,y,z,(x+1)*.5f,(y+1)*.5f,new float[]{.25f,.7f,.9f,alpha},0,0,1);}
        drawVertices(program,v);float[] result=pixels();bounded(result,false,1.25f);depth(sceneDepth);return result;
    }
    static void ambientChecks() {
        for(int program:new int[]{mesh,glow}) {
            float[] visible=ambientQuad(program,.6f,-.3f,.63f,false);int coverage=0;
            for(int i=3;i<visible.length;i+=4)if(visible[i]>.003)coverage++;
            require(coverage>100,"Ambient shader has no visible fragments");
            for(float f:ambientQuad(program,0,-.3f,.63f,false))require(f==0,"Ambient zero alpha is not empty");
            for(float f:ambientQuad(program,.6f,-.3f,.1f,false))require(f==0,"Ambient passes through scene depth");
            if(program==glow) {
                for(int y=0;y<H;y++)for(int x=0;x<W;x++)if(x==0||y==0||x==W-1||y==H-1)require(visible[(y*W+x)*4+3]==0,"Glow quad edge seam");
                for(float f:ambientQuad(program,.6f,-.3f,.63f,true))require(f==0,"Far fog does not hide ambient glow");
            } else {
                for(int i=3;i<visible.length;i+=4)require(Math.abs(visible[i]-.6)<1e-6,"Ambient mesh modifies input opacity");
            }
            System.out.println("PASS "+(program==mesh?"ambient_mesh":"ambient_glow")+": coverage="+coverage+" finite, transparent zero input, scene occlusion and depth unchanged");
        }
        clear(.63f);for(float f:pixels())require(f==0,"Empty atmosphere batch retained pixels");depth(.63f);
    }
    static float[] color(int rgb,double alpha) {return new float[]{(rgb>>16&255)/255f,(rgb>>8&255)/255f,(rgb&255)/255f,(float)alpha};}
    static double[] cross(double[] a,double[] b,double[] c) {
        double x=(b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1]),y=(b[2]-a[2])*(c[0]-a[0])-(b[0]-a[0])*(c[2]-a[2]),z=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
        double length=Math.sqrt(x*x+y*y+z*z);return new double[]{x/length,y/length,z/length};
    }
    static final int[][] OCTAHEDRON={{0,3,2},{0,4,3},{0,5,4},{0,2,5},{1,2,3},{1,3,4},{1,4,5},{1,5,2}};
    static void fixtureDepth() {glDepthMask(true);glClearDepth(1);glClear(GL_DEPTH_BUFFER_BIT);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);}
    static float[] scene(AmbientField.Style style,double seconds) {
        float[] view=rotation(0,-.04),proj=perspective();skyFrame(style.ordinal(),TAU*seconds/240,.7f,0,view,proj);
        transforms(view,proj,new float[]{1,1,1,1},0);fog(18,40);fixtureDepth();
        FloatBuffer ground=BufferUtils.createFloatBuffer(12*6*24*24);
        for(int x=-12;x<12;x++)for(int z=-24;z<0;z++) {
            int rgb=((x+z)&1)==0?0x38463E:0x414C43;float[] c=color(rgb,1);
            for(int corner:new int[]{0,1,2,0,2,3}){double[][] p={{x,-3,z},{x+1,-3,z},{x+1,-3,z+1},{x,-3,z+1}};vertex(ground,p[corner][0],p[corner][1],p[corner][2],0,0,c,0,1,0);}
        }
        drawVertices(fixture,ground);glDepthMask(false);float[] beforeMotes=pixels();
        // Empty-air fixture uses production world-cell sampling, default density .6/size1, cap128.
        // Closed eight-face geometry and blend/cull state mirror AmbientEffectsRenderer; no enlarged cores or post glow.
        List<AmbientField.Mote> motes=new ArrayList<>();double eyeX=2.5,eyeY=3,eyeZ=2.5;
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)for(int y=-3;y<=3;y++)if(AmbientField.occupied(x,y,z,.6)) {
            var m=AmbientField.sample(x,y,z,style,seconds);double alpha=m.opacity()*AmbientField.visibility(m.x()-eyeX,m.y()-eyeY,m.z()-eyeZ);
            if(alpha>=.01&&m.y()>0)motes.add(new AmbientField.Mote(m.x()-eyeX,m.y()-eyeY,m.z()-eyeZ,m.size(),m.yaw(),m.pitch(),alpha,m.color()));
        }
        motes.sort(Comparator.comparingDouble(m->m.x()*m.x()+m.y()*m.y()+m.z()*m.z()));if(motes.size()>128)motes.subList(128,motes.size()).clear();Collections.reverse(motes);
        FloatBuffer solid=BufferUtils.createFloatBuffer(Math.max(1,motes.size())*8*3*12),bloom=BufferUtils.createFloatBuffer(Math.max(1,motes.size())*6*12);
        float[] inverse=transpose(view);double[] right={inverse[0],inverse[1],inverse[2]},up={inverse[4],inverse[5],inverse[6]};
        for(var m:motes) {
            double r=m.size(),stretch=style==AmbientField.Style.CRYSTALS?2.2:style==AmbientField.Style.SNOW?.35:1;
            double[][] p={{0,r*stretch,0},{0,-r*stretch,0},{r,0,0},{0,0,r},{-r,0,0},{0,0,-r}};
            for(double[] v:p){double y=v[1]*Math.cos(m.pitch())-v[2]*Math.sin(m.pitch()),z=v[1]*Math.sin(m.pitch())+v[2]*Math.cos(m.pitch()),x=v[0];v[0]=x*Math.cos(m.yaw())-z*Math.sin(m.yaw())+m.x();v[1]=y+m.y();v[2]=x*Math.sin(m.yaw())+z*Math.cos(m.yaw())+m.z();}
            for(int[] face:OCTAHEDRON){double[] n=cross(p[face[0]],p[face[1]],p[face[2]]);for(int i:face)vertex(solid,p[i][0],p[i][1],p[i][2],0,0,color(m.color(),m.opacity()*.88),n[0],n[1],n[2]);}
            if(style!=AmbientField.Style.SNOW)for(int corner:new int[]{0,1,2,0,2,3}) {
                double[][] uv={{-1,-1},{1,-1},{1,1},{-1,1}};double u=uv[corner][0],v=uv[corner][1],size=r*(style==AmbientField.Style.CRYSTALS?2.8:5.5);
                vertex(bloom,m.x()+size*(right[0]*u+up[0]*v),m.y()+size*(right[1]*u+up[1]*v),m.z()+size*(right[2]*u+up[2]*v),(float)(u+1)/2,(float)(v+1)/2,color(m.color(),m.opacity()*(style==AmbientField.Style.CRYSTALS?.15:.48)),0,0,0);
            }
        }
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);drawVertices(mesh,solid);
        if(style!=AmbientField.Style.SNOW){glDisable(GL_CULL_FACE);glBlendFunc(GL_SRC_ALPHA,GL_ONE);drawVertices(glow,bloom);}
        glDisable(GL_BLEND);glDisable(GL_CULL_FACE);require(glGetError()==GL_NO_ERROR,"Ambient scene error");
        float[] result=pixels();Delta moteDelta=delta(beforeMotes,result);
        // Default snow cores can be subpixel in a small overview; do not enlarge them to manufacture evidence.
        if(style!=AmbientField.Style.SNOW)require(moteDelta.changed>10,"3D ambient fixture is invisible: "+style);
        System.out.println("GPU fixture "+style+": "+motes.size()+" sampled cells; "+moteDelta.changed+" mote-changed channels; default density .6, size1; real shader, closed octahedra");return result;
    }
    static float[] fogScene(String style) {
        var vanilla=new CustomFogMath.Fog(new CustomFogMath.Color(.50,.62,.72,1),0,1024,96,128,192,224);
        var value=CustomFogMath.apply(vanilla,new CustomFogMath.Settings(style,1,64),new CustomFogMath.Conditions(true,true,CustomFogMath.Medium.AIR,false,false,false,false));
        float[] view=rotation(0,.12),proj=perspective();clear(1);fog(value);transforms(view,proj,new float[]{2,.85f,.3f,.7f},0);glUseProgram(sky);glDrawArrays(GL_TRIANGLES,0,3);
        transforms(view,proj,new float[]{1,1,1,1},0);fixtureDepth();FloatBuffer b=BufferUtils.createFloatBuffer(12*6*(40*24+18));
        for(int x=-80;x<80;x+=4)for(int z=-96;z<0;z+=4)fixtureQuad(b,new double[][]{{x,-3,z},{x+4,-3,z},{x+4,-3,z+4},{x,-3,z+4}},((x+z)/4&1)==0?0x485E3D:0x59654D);
        for(double[] box:new double[][]{{-5,-18},{0,-36},{8,-60}}) {
            double x=box[0],z=box[1],s=2;double[][] p={{x-s,-3,z-s},{x+s,-3,z-s},{x+s,2,z-s},{x-s,2,z-s},{x-s,-3,z+s},{x+s,-3,z+s},{x+s,2,z+s},{x-s,2,z+s}};
            for(int[] face:new int[][]{{0,1,2,3},{4,7,6,5},{0,4,5,1},{3,2,6,7},{0,3,7,4},{1,5,6,2}})fixtureQuad(b,new double[][]{p[face[0]],p[face[1]],p[face[2]],p[face[3]]},0x8B8D87);
        }
        drawVertices(fixture,b);glDepthMask(false);float[] result=pixels();bounded(result,true,1.00001f);require(glGetError()==GL_NO_ERROR,"Native custom-fog fixture error");return result;
    }
    static void fixtureQuad(FloatBuffer b,double[][] points,int rgb) {for(int i:new int[]{0,1,2,0,2,3})vertex(b,points[i][0],points[i][1],points[i][2],0,0,color(rgb,1),0,1,0);}
    static BufferedImage image(float[] rgba) {
        BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int at=((H-y-1)*W+x)*4,rgb=0;for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.max(0,Math.min(1,rgba[at+c]))*255);image.setRGB(x,y,rgb);}return image;
    }
    static void contactSheet() throws Exception {
        int header=46,label=31;BufferedImage sheet=new BufferedImage(W*3,H*3+header+label*3,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();
        g.setColor(new Color(18,22,29));g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,17));g.drawString("ATMOSPHERE — actual production shader GPU preview",14,21);
        g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString("Offscreen empty-air fixtures, not Minecraft screenshots. Default brightness .85; middle row: density .6 / size1.",14,39);
        String[] names={"Aurora","Nebula","Sunset"},ambientNames={"Fireflies","Crystal Motes","Snowfall"},fogNames={"Mist","Dusk","Frost"};
        for(int i=0;i<3;i++) {
            g.drawImage(image(skyFrame(i,TAU*12/240,.7f,0,rotation(.15,-.30),perspective())),i*W,header+label,null);
            g.setColor(Color.WHITE);g.drawString(names[i]+" | world-oriented sky, t=12s",i*W+10,header+21);
            g.drawImage(image(scene(AmbientField.Style.values()[i],12)),i*W,header+label*2+H,null);
            g.setColor(Color.WHITE);g.drawString(ambientNames[i]+" | 3D cores + native fog/depth",i*W+10,header+label+H+21);
            g.drawImage(image(fogScene(fogNames[i])),i*W,header+label*3+H*2,null);
            g.setColor(Color.WHITE);g.drawString(fogNames[i]+" | density1 / distance64, native fog",i*W+10,header+label*2+H*2+21);
        }
        g.dispose();Path target=output.resolve("gpu-atmosphere-contact-sheet.png");ImageIO.write(sheet,"PNG",target.toFile());System.out.println("GPU preview: "+target);
    }
    public static void main(String[] args) throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        require(glfwInit(),"GLFW init failed");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Atmosphere hidden GPU QA",0,0);require(window!=0,"Hidden OpenGL context failed");glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3},offsets={0,3,5,9};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            dynamic=buffer(0,BufferUtils.createByteBuffer(160));projection=buffer(1,BufferUtils.createByteBuffer(64));fog=buffer(2,BufferUtils.createByteBuffer(48));
            sky=link("skybox.vsh","skybox.fsh");mesh=link("ambient_mesh.vsh","ambient_mesh.fsh");glow=link("ambient_mesh.vsh","ambient_glow.fsh");
            layout(sky,"ColorModulator",64);layout(sky,"ModelOffset",80);layout(sky,"FogColor",0);layout(mesh,"FogEnvironmentalStart",16);layout(mesh,"FogRenderDistanceEnd",28);
            fixture=linkSource(preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/ambient_mesh.vsh"))),preprocess("#version 330\n#moj_import <minecraft:fog.glsl>\nin vec4 tint;in vec2 fogDistance;out vec4 fragColor;void main(){fragColor=apply_fog(tint,fogDistance.x,fogDistance.y,FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);}"),"fixture native-fog terrain");
            frame=framebuffer(W,H,true);System.out.println("Driver: "+glGetString(GL_RENDERER)+"; actual MC includes; Dynamic160 / Projection64 / Fog48 native bindings validated");
            skyChecks();ambientChecks();contactSheet();
            System.out.println("PASS Atmosphere GPU: "+assertions+" assertions; "+depthPixels+" unchanged depth samples; actual sky/ambient compile, world anchoring, time/seams, opacity and occlusion. Preview is not in-game evidence.");
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
