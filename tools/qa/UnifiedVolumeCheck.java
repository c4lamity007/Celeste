import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Node;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
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

/** Actual nearest-surface/material/composite GPU regression, not a Minecraft capture. */
public final class UnifiedVolumeCheck extends VolumeShowcase {
    static final int SIZE=ImpactCheck.TILE;
    static int surfaceFbo,surfaceColor,resultFbo;
    static long assertions;
    static int depthPixels,coveredPixels;
    static void require(boolean condition,String message) {
        assertions++;if(!condition)throw new AssertionError(message);
    }
    static void identityView() {
        ByteBuffer data=BufferUtils.createByteBuffer(160);matrix(data,0);matrix(data,96);
        for(int i=0;i<4;i++)data.putFloat(64+i*4,1);
        glBindBuffer(GL_UNIFORM_BUFFER,transformBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        ByteBuffer projection=BufferUtils.createByteBuffer(64);matrix(projection,0);
        glBindBuffer(GL_UNIFORM_BUFFER,projectionBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,projection);
    }
    static void materialTime(float seconds) {
        ByteBuffer values=BufferUtils.createByteBuffer(64);
        values.putFloat(32,SIZE).putFloat(36,SIZE).putFloat(44,seconds/1200f);
        glBindBuffer(GL_UNIFORM_BUFFER,globals);glBufferSubData(GL_UNIFORM_BUFFER,0,values);
    }
    static float[] pixels() {
        FloatBuffer values=BufferUtils.createFloatBuffer(SIZE*SIZE*4);
        glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_FLOAT,values);
        float[] pixels=new float[values.remaining()];values.get(pixels);return pixels;
    }
    static void drawSurface(List<Face> faces,float alpha,boolean reverse,boolean alternate) {
        glUseProgram(volumeProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,prismTexture);
        if(!faces.isEmpty())send(surfaceData(faces,alpha,reverse,alternate));
    }
    static float[] render(List<Face> faces,float alpha,float seconds,boolean reverse,boolean alternate,boolean unified) {
        materialTime(seconds);glViewport(0,0,SIZE,SIZE);glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER,resultFbo);glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(.63);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        if(unified) {
            glBindFramebuffer(GL_FRAMEBUFFER,surfaceFbo);glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(1);
            glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);drawSurface(faces,alpha,reverse,alternate);
            glBindFramebuffer(GL_FRAMEBUFFER,resultFbo);glDisable(GL_DEPTH_TEST);glDepthMask(false);
            glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(volumeComposite);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,surfaceColor);
            glDrawArrays(GL_TRIANGLES,0,3);
        } else {
            // Control: the same current shader/vertices with the old per-face blending strategy.
            glDisable(GL_DEPTH_TEST);glDepthMask(false);glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            drawSurface(faces,alpha,reverse,alternate);
        }
        float[] result=pixels();
        for(float value:result)require(Float.isFinite(value)&&value>=0&&value<=1.000001f,"Nonfinite/unbounded volume output");
        FloatBuffer depth=BufferUtils.createFloatBuffer(SIZE*SIZE);
        glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
        for(int i=0;i<depth.limit();i++)require(Math.abs(depth.get(i)-.63f)<1e-6,"Main scene depth was changed");
        depthPixels+=depth.limit();require(glGetError()==GL_NO_ERROR,"OpenGL render error");
        return result;
    }
    static Face rectangle(double x1,double y1,double x2,double y2,double z,int color) {
        return new Face(new Point(x1,y1,z),new Point(x2,y1,z),new Point(x2,y2,z),new Point(x1,y2,z),color,color,0,1);
    }
    static List<Face> overlap() {
        return List.of(rectangle(-.72,-.66,.72,.66,-.45,0xFF35BEFF),
            rectangle(-.58,-.57,.79,.60,.15,0xFFF54BAC),
            rectangle(-.81,-.47,.58,.77,.48,0xFF9D83FF));
    }
    static List<Face> loops() {
        List<Face> faces=new ArrayList<>();
        for(int loop=0;loop<2;loop++) {
            List<Node> nodes=new ArrayList<>();
            for(int i=0;i<32;i++) {
                double angle=i*Math.PI*2/32,phase=loop*.74;
                Point center=new Point((loop==0?-.14:.18)+Math.cos(angle)*.47,
                    Math.sin(angle)*.47,(loop==0?-.08:.10)+Math.sin(angle+phase)*.18);
                nodes.add(new Node(center,.102,.090,loop==0?0xFF44CBFF:0xFFC384FF));
            }
            faces.addAll(SolidVisualMesh.tube(nodes,new Point(0,0,1),6,192,true));
        }
        return faces;
    }
    static int exactAlpha(float[] image,float alpha,String label) {
        int covered=0;
        for(int i=0;i<image.length;i+=4) {
            if(image[i+3]>0) {covered++;require(Math.abs(image[i+3]-alpha)<2e-6,label+" stacks or changes vertex alpha");}
            else require(image[i]==0&&image[i+1]==0&&image[i+2]==0,label+" leaks color outside coverage");
        }
        require(covered>SIZE*SIZE/20,label+" fixture lost coverage");coveredPixels+=covered;return covered;
    }
    static int compare(float[] first,float[] other,float tolerance,String label) {
        int different=0,alpha=0,peakAt=0;float peak=0;
        for(int i=0;i<first.length;i++) {
            float delta=Math.abs(first[i]-other[i]);
            if(delta>peak){peak=delta;peakAt=i;}
            if(delta>tolerance){different++;if(i%4==3)alpha++;}
        }
        require(different==0,label+": differing channels="+different+", alpha="+alpha+", peak="+peak
            +", peak pixel="+(peakAt/4%SIZE)+","+(peakAt/4/SIZE));return different;
    }
    static void transparency() {
        List<Face> rectangles=overlap(),tubes=loops();
        for(float alpha:new float[]{.15f,.35f,.70f,.90f,1f}) {
            float[] front=render(rectangles,alpha,3,false,false,true);
            exactAlpha(front,alpha,"Overlapping front/back surfaces "+alpha);
            compare(front,render(rectangles,alpha,3,true,false,true),2e-6f,"Distinct-depth order independence");
            float[] curved=render(tubes,alpha,3,false,false,true);exactAlpha(curved,alpha,"Intersecting closed tubes "+alpha);
            compare(curved,render(tubes,alpha,3,true,false,true),2e-5f,"Tube face order (no intentional coplanar ties)");
        }
        for(float value:render(tubes,0,3,false,false,true))require(value==0,"Zero-alpha input leaked");
        for(float value:render(List.of(),.7f,3,false,false,true))require(value==0,"Empty pass retained prior surface");
        // Different input fades must select the nearest one, not combine its alpha with the back surface.
        List<Face> fades=List.of(rectangle(-.7,-.7,.7,.7,.2,0xE6C36AFF),rectangle(-.7,-.7,.7,.7,-.3,0x593FCBFF));
        exactAlpha(render(fades,-1,3,false,false,true),89f/255,"Nearest surface with different feature fades");
        float[] old=render(rectangles,.35f,3,false,false,false);
        float[] unified=render(rectangles,.35f,3,false,false,true);
        int center=(SIZE/2+SIZE*(SIZE/2))*4;
        require(Math.abs(old[center+3]-(1-Math.pow(1-.35f,3)))<2e-6,"Per-face control did not stack three layers");
        require(Math.abs(unified[center+3]-.35f)<2e-6,"Unified center alpha changed");
        System.out.printf(Locale.ROOT,"Volume transparency: input .15/.35/.70/.90/1 preserved once; three-layer .35 control %.6f vs unified %.6f%n",old[center+3],unified[center+3]);
    }
    static void diagonalAndTime() {
        List<Face> plane=List.of(rectangle(-.8,-.8,.8,.8,-.30,0xFF40C7FF));
        float[] first=render(plane,.35f,3,false,false,true);
        // Different raster interpolation paths can differ below .0001 in float
        // RGB (under .026 of an 8-bit level); alpha/coverage must stay exact.
        float[] alternate=render(plane,.35f,3,false,true,true);
        compare(first,alternate,1e-4f,"Planar quad triangulation creates diagonal stripes");
        for(int i=3;i<first.length;i+=4)require(first[i]==alternate[i],"Alternate diagonal changed alpha/coverage");
        float[] later=render(plane,.35f,3.5f,false,false,true),fraction=render(plane,.35f,3+1f/60,false,false,true);
        int changed=0,subframe=0;
        for(int i=0;i<first.length;i+=4) {
            boolean a=false,b=false;
            for(int c=0;c<3;c++){a|=Math.abs(first[i+c]-later[i+c])>1e-4;b|=Math.abs(first[i+c]-fraction[i+c])>1e-5;}
            if(a)changed++;if(b)subframe++;
            require(first[i+3]==later[i+3]&&first[i+3]==fraction[i+3],"Material animation changed alpha");
        }
        require(changed>SIZE*SIZE/10&&subframe>SIZE*SIZE/10,"Fractional shader animation absent");
        System.out.println("Volume animation: fractional pixels="+subframe+", half-second pixels="+changed+"; alternate planar diagonal RGB within1e-4, alpha/coverage exact");
    }
    static List<Face> materialPlane(float uStart,float uEnd) {
        Face face=rectangle(-.8,-.8,.8,.8,-.30,0xFF40C7FF);
        return List.of(new Face(face.a(),face.b(),face.c(),face.d(),face.color(),face.endColor(),uStart,uEnd));
    }
    static final int MATERIAL_GRID=96;
    record Curvature(double mean,double percentile95,double sharpFraction) {
        boolean smooth() { return mean<.004&&percentile95<.018&&sharpFraction<.01; }
    }
    static double sample(float[] image,double x,double y,int channel) {
        int x0=(int)x,y0=(int)y,x1=Math.min(SIZE-1,x0+1),y1=Math.min(SIZE-1,y0+1);
        double fx=x-x0,fy=y-y0;
        double top=image[(y0*SIZE+x0)*4+channel]*(1-fx)+image[(y0*SIZE+x1)*4+channel]*fx;
        double bottom=image[(y1*SIZE+x0)*4+channel]*(1-fx)+image[(y1*SIZE+x1)*4+channel]*fx;
        return top*(1-fy)+bottom*fy;
    }
    static double[] interior(float[] image) {
        double[] grid=new double[MATERIAL_GRID*MATERIAL_GRID*3];
        for(int y=0;y<MATERIAL_GRID;y++)for(int x=0;x<MATERIAL_GRID;x++)for(int c=0;c<3;c++)
            grid[(y*MATERIAL_GRID+x)*3+c]=sample(image,(.14+.72*x/(MATERIAL_GRID-1))*(SIZE-1),
                (.14+.72*y/(MATERIAL_GRID-1))*(SIZE-1),c);
        return grid;
    }
    static Curvature curvature(double[] grid) {
        int n=MATERIAL_GRID;double[] differences=new double[(n-2)*(n-2)*6];int at=0,sharp=0;double sum=0;
        for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++)for(int c=0;c<3;c++) {
            int center=(y*n+x)*3+c;
            for(int stride:new int[]{3,n*3}) {
                double value=Math.abs(grid[center-stride]-2*grid[center]+grid[center+stride]);
                differences[at++]=value;sum+=value;if(value>.035)sharp++;
            }
        }
        Arrays.sort(differences);
        return new Curvature(sum/at,differences[(int)(at*.95)],sharp/(double)at);
    }
    static void metricControls() {
        // Analytical controls validate the detector, not substitute material output.
        // Broad gradients/highlights should pass; visible six-sample ribs should fail.
        double[] broad=new double[MATERIAL_GRID*MATERIAL_GRID*3],ribbed=broad.clone();
        for(int y=0;y<MATERIAL_GRID;y++)for(int x=0;x<MATERIAL_GRID;x++)for(int c=0;c<3;c++) {
            int at=(y*MATERIAL_GRID+x)*3+c;
            broad[at]=.5+.30*Math.sin(x*Math.PI*2/MATERIAL_GRID)+.10*Math.cos(y*Math.PI*2/MATERIAL_GRID);
            ribbed[at]=.5+.08*Math.sin(x*Math.PI*2/6);
        }
        require(curvature(broad).smooth(),"Curvature detector rejects a broad smooth gradient");
        require(!curvature(ribbed).smooth(),"Curvature detector misses narrow periodic ribs");
    }
    static int previousMaterial(Path fragment)throws Exception {
        int vertex=shader(GL_VERTEX_SHADER,preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/world_volume.vsh"))));
        int pixel=shader(GL_FRAGMENT_SHADER,preprocess(Files.readString(fragment)));
        int program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,pixel);
        String[] attributes={"Position","UV0","Color","Normal"};
        for(int i=0;i<attributes.length;i++)glBindAttribLocation(program,i,attributes[i]);
        glLinkProgram(program);require(glGetProgrami(program,GL_LINK_STATUS)!=0,"Previous material control link: "+glGetProgramInfoLog(program));
        String[] blocks={"DynamicTransforms","Projection","Globals"};
        for(int i=0;i<blocks.length;i++){int index=glGetUniformBlockIndex(program,blocks[i]);if(index!=GL_INVALID_INDEX)glUniformBlockBinding(program,index,i);}
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"Sampler0"),0);
        glDeleteShader(vertex);glDeleteShader(pixel);return program;
    }
    static void materialContinuity()throws Exception {
        metricControls();
        // Fixed normalized sampling density makes even/odd framebuffer tests comparable.
        // Silhouette pixels are excluded; alpha1 prevents opacity hiding material noise.
        for(float seconds:new float[]{.25f,1.7f,3f}) {
            float[] image=render(materialPlane(0,1),1,seconds,false,false,true);
            compare(image,render(materialPlane(1,2),1,seconds,false,false,true),2e-4f,"Material does not repeat continuously over one U period");
            Curvature measure=curvature(interior(image));
            require(measure.smooth(),"Narrow high-frequency material striping at "+seconds+"s: "+measure);
            float[] seam=render(materialPlane(-.05f,.05f),1,seconds,false,false,true);
            double largestSeamStep=0;
            for(int y=SIZE/4;y<SIZE*3/4;y++)for(int c=0;c<3;c++)
                largestSeamStep=Math.max(largestSeamStep,Math.abs(seam[(y*SIZE+SIZE/2-1)*4+c]-seam[(y*SIZE+SIZE/2+1)*4+c]));
            require(largestSeamStep<.015,"Abrupt RGB seam at U0/1: "+largestSeamStep);
            System.out.printf(Locale.ROOT,"Volume smoothness %.2fs: mean curvature %.6f, p95 %.6f, sharp fraction %.6f, U-seam step %.6f%n",
                seconds,measure.mean(),measure.percentile95(),measure.sharpFraction(),largestSeamStep);
        }
        String previous=System.getProperty("celeste.test.previousVolumeFragment");
        if(previous!=null&&!previous.isBlank()) {
            int current=volumeProgram,control=previousMaterial(Path.of(previous));int rejected=0;
            try {
                volumeProgram=control;
                for(float seconds:new float[]{.25f,1.7f,3f}) {
                    Curvature measure=curvature(interior(render(materialPlane(0,1),1,seconds,false,false,true)));
                    if(!measure.smooth())rejected++;
                    System.out.println("Previous material negative control "+seconds+"s: "+measure);
                }
            } finally {volumeProgram=current;glDeleteProgram(control);}
            require(rejected>=2,"Smoothness bounds did not distinguish the supplied old striped shader");
            System.out.println("PASS previous actual material rejected by unchanged smoothness bounds at "+rejected+"/3 times");
        }
    }
    static void neutralPalette() {
        List<Face> neutral=List.of(rectangle(-.8,-.8,.8,.8,-.30,0xFF989898));
        float[] image=render(neutral,.35f,1.7f,false,false,true);
        exactAlpha(image,.35f,"Neutral palette input alpha");
        for(int i=0;i<image.length;i+=4) {
            require(Math.abs(image[i]-image[i+1])<2e-6f&&Math.abs(image[i+1]-image[i+2])<2e-6f,
                "Material adds an unwanted colored gloss to a neutral theme");
        }
        System.out.println("Volume palette: neutral theme stays neutral with animated enchantment shading");
    }
    static void overlayCoordinates() {
        // These are actual production fragments, including welded normals and the
        // nearest-surface resolve. Compare the SAME framebuffer pixels, not pixels
        // attached to the mesh: the silhouette is deliberately allowed to move.
        identityView();
        float[] anchor=render(materialPlane(0,1),.70f,1.7f,false,false,true);
        compare(anchor,render(materialPlane(.371f,3.913f),.70f,1.7f,false,false,true),2e-5f,
            "Overlay animation follows arbitrary mesh UV remapping");
        Face moved=rectangle(-.61,-.91,.79,.57,-.30,0xFF40C7FF);
        List<Face> translated=List.of(new Face(moved.a(),moved.b(),moved.c(),moved.d(),
            moved.color(),moved.endColor(),-2.17f,.83f));
        float[] objectMotion=render(translated,.70f,1.7f,false,false,true);
        ByteBuffer transforms=BufferUtils.createByteBuffer(160);matrix(transforms,0);matrix(transforms,96);
        for(int i=0;i<4;i++)transforms.putFloat(64+i*4,1);
        transforms.putFloat(48,.13f).putFloat(52,-.09f);
        glBindBuffer(GL_UNIFORM_BUFFER,transformBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,transforms);
        float[] cameraMotion=render(materialPlane(.11f,1.79f),.70f,1.7f,false,false,true);
        identityView();
        for(float[] image:new float[][]{objectMotion,cameraMotion}) {
            int common=0,coverageChanged=0;float peak=0;
            for(int i=0;i<anchor.length;i+=4) {
                if(anchor[i+3]!=image[i+3])coverageChanged++;
                if(anchor[i+3]==0||image[i+3]==0)continue;
                common++;
                for(int c=0;c<4;c++)peak=Math.max(peak,Math.abs(anchor[i+c]-image[i+c]));
            }
            require(common>SIZE*SIZE/4,"Moving overlay fixture lost its comparison area");
            require(coverageChanged>SIZE*SIZE/50,"Overlay fixture did not actually move its silhouette");
            require(peak<2e-5,"Material pattern moved with object/camera instead of framebuffer pixels: "+peak);
        }
        System.out.println("Volume overlay: arbitrary UVs, object translation/resizing and camera translation keep overlapping framebuffer RGB/alpha fixed at frozen time; silhouettes move normally");
    }
    static void previousOverlayControl()throws Exception {
        String path=System.getProperty("celeste.test.previousOverlayVolumeFragment");
        if(path==null||path.isBlank())return;
        int current=volumeProgram,control=previousMaterial(Path.of(path));boolean rejected=false;
        try {
            volumeProgram=control;
            try {overlayCoordinates();}catch(AssertionError expected){rejected=true;}
        }finally{volumeProgram=current;identityView();glDeleteProgram(control);}
        require(rejected,"Overlay detector accepted the supplied movement-linked previous shader");
        System.out.println("PASS previous actual UV-driven material rejected by the unchanged overlay invariance check");
    }
    static void comparison() {
        int leftTexture=attachment(false,SIZE,SIZE),rightTexture=attachment(false,SIZE,SIZE);
        render(loops(),.35f,3,false,false,false);glBindTexture(GL_TEXTURE_2D,leftTexture);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,SIZE,SIZE);
        render(loops(),.35f,3,false,false,true);glBindTexture(GL_TEXTURE_2D,rightTexture);glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,SIZE,SIZE);
        int sheet=fbo(attachment(false,SIZE*2,SIZE),0);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sheet);
        for(int i=0;i<2;i++) {
            int reader=fbo(i==0?leftTexture:rightTexture,0);glBindFramebuffer(GL_READ_FRAMEBUFFER,reader);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,sheet);
            glBlitFramebuffer(0,0,SIZE,SIZE,i*SIZE,0,(i+1)*SIZE,SIZE,GL_COLOR_BUFFER_BIT,GL_NEAREST);glDeleteFramebuffers(reader);
        }
        glBindFramebuffer(GL_FRAMEBUFFER,sheet);ByteBuffer bytes=BufferUtils.createByteBuffer(SIZE*SIZE*2*4);
        glReadPixels(0,0,SIZE*2,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,bytes);STBImageWrite.stbi_flip_vertically_on_write(true);
        require(STBImageWrite.stbi_write_png(output.resolve("unified-volume-comparison.png").toString(),SIZE*2,SIZE,4,bytes,SIZE*2*4),"Comparison PNG write failed");
        System.out.println("GPU PREVIEW: transparent background; left=current shader with per-face blending control; right=private-depth nearest-surface + single alpha composite; input .35. NOT a game screenshot.");
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);changedResources=resources;Files.createDirectories(output);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();require(glfwInit(),"GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(SIZE,SIZE,"Unified volume GPU regression (hidden)",0,0);require(window!=0,"No GL context");glfwMakeContextCurrent(window);GL.createCapabilities();
        volumeProgram=program("volume");volumeComposite=material("unified_volume_composite",null);
        require(glGetAttribLocation(volumeProgram,"Normal")==3,"Production vertex Normal attribute not active at3");
        prismTexture=texture("assets/celeste/textures/visual/plasma_flow.png");
        transformBuffer=buffer(0,BufferUtils.createByteBuffer(160));projectionBuffer=buffer(1,BufferUtils.createByteBuffer(64));globals=buffer(2,BufferUtils.createByteBuffer(64));identityView();
        glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);int[] sizes={3,2,4,3};int offset=0;
        for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}glDisable(GL_CULL_FACE);
        surfaceColor=attachment(false,SIZE,SIZE);surfaceFbo=fbo(surfaceColor,attachment(true,SIZE,SIZE));resultFbo=fbo(attachment(false,SIZE,SIZE),attachment(true,SIZE,SIZE));
        transparency();diagonalAndTime();materialContinuity();neutralPalette();overlayCoordinates();previousOverlayControl();comparison();require(glGetError()==GL_NO_ERROR,"Final GL error");
        System.out.println("PASS UnifiedVolume: "+assertions+" assertions; "+coveredPixels+" alpha-coverage pixels; "+depthPixels+" unchanged main-depth pixels; actual shaders/normals, alpha once, draw-order, empty/zero, diagonal, fractional animation, screen-overlay invariance, U wrap and broad-material smoothness checks at "+SIZE+"x"+SIZE+".");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
    }
}
