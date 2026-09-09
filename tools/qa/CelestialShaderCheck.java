import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.ImageOutputStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Production GLSL and diagnostic calls to its unmodified celestial helpers. No game claim. */
public final class CelestialShaderCheck extends ImpactCheck {
    static final int W=AtmosphereCheck.W,H=AtmosphereCheck.H;
    static final double TAU=Math.PI*2;
    static final double[] PLANET=normal(-.30,.46,-.84),RING=normal(.42,.83,-.36),HOLE=normal(.40,.44,-.80);
    static int full,diagnostic,baseline;
    static long checks;
    static final Map<String,Double> times=new LinkedHashMap<>();
    static void require(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    static double[] normal(double x,double y,double z){double d=Math.sqrt(x*x+y*y+z*z);return new double[]{x/d,y/d,z/d};}
    static double[] cross(double[]a,double[]b){return normal(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]);}
    static double dot(double[]a,double[]b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    static float[] view(int mode){double[] c=mode==1?PLANET:HOLE;return AtmosphereCheck.rotation(Math.atan2(c[0],-c[2]),-Math.asin(c[1]));}
    static float[] projection(double degrees){float[]p=AtmosphereCheck.perspective();float f=(float)(1/Math.tan(Math.toRadians(degrees)*.5));p[0]=f*H/W;p[5]=f;return p;}
    static void setup(int program,int mode,double phase,double fov,boolean inspect,float background){
        AtmosphereCheck.clear(.63f);
        AtmosphereCheck.transforms(view(mode),projection(fov),new float[]{mode,.85f,(float)phase,.7f},0);
        AtmosphereCheck.fog(1000,1024);
        glUseProgram(program);
        if(inspect){glUniform1f(glGetUniformLocation(program,"TestBackground"),background);glUniform1f(glGetUniformLocation(program,"TestExactCenter"),0);}
    }
    static float[] render(int program,int mode,double phase,double fov,boolean inspect,float background){
        setup(program,mode,phase,fov,inspect,background);glDrawArrays(GL_TRIANGLES,0,3);
        float[] pixels=AtmosphereCheck.pixels();AtmosphereCheck.depth(.63f);
        // Helper output is pre-tone-map radiance; the production main must remain display-bounded.
        for(float c:pixels){checks++;if(!Float.isFinite(c)||c<-.00001||c>(inspect?4:1.0001))throw new AssertionError("bounded celestial output: "+c);}
        return pixels;
    }
    static float[] render(int mode,double phase){return render(full,mode,phase,36,false,0);}
    static int pixel(double[] direction,int mode,double fov){
        float[]m=view(mode),p=projection(fov);double x=m[0]*direction[0]+m[4]*direction[1]+m[8]*direction[2],
            y=m[1]*direction[0]+m[5]*direction[1]+m[9]*direction[2],z=m[2]*direction[0]+m[6]*direction[1]+m[10]*direction[2];
        int ix=(int)Math.floor((x*p[0]/-z+1)*W*.5),iy=(int)Math.floor((y*p[5]/-z+1)*H*.5);
        require(ix>=0&&ix<W&&iy>=0&&iy<H,"sample direction outside diagnostic view");return(iy*W+ix)*4;
    }
    static double luminance(float[]p,int i){return p[i]*.2126+p[i+1]*.7152+p[i+2]*.0722;}
    static double transmission(float[]a,float[]b,int i){return (b[i]-a[i]+b[i+1]-a[i+1]+b[i+2]-a[i+2])/3;}
    static void nebulaChecks(){
        double phase=.7;
        float[]black=render(diagnostic,1,phase,36,true,0),white=render(diagnostic,1,phase,36,true,1),
            later=render(diagnostic,1,phase+TAU/240*5,36,true,0);
        double[]u=cross(RING,PLANET),v=cross(RING,u);
        double bandAlpha=0,gapAlpha=0,motion=0;int bands=0,gaps=0,translucent=0;
        for(int n=0;n<96;n++){
            double angle=n*TAU/96;
            for(double radius:new double[]{.146,.172,.201,.1575,.187}){
                double[]d=normal(PLANET[0]+radius*(u[0]*Math.cos(angle)+v[0]*Math.sin(angle)),
                    PLANET[1]+radius*(u[1]*Math.cos(angle)+v[1]*Math.sin(angle)),
                    PLANET[2]+radius*(u[2]*Math.cos(angle)+v[2]*Math.sin(angle)));
                double impact=Math.sqrt(Math.max(0,1-dot(d,PLANET)*dot(d,PLANET)));
                if(impact<.112||dot(d,RING)<.02)continue;
                int at=pixel(d,1,36);double alpha=1-transmission(black,white,at);
                if(radius==.1575||radius==.187){gapAlpha+=alpha;gaps++;}
                else{bandAlpha+=alpha;bands++;if(alpha>.025&&alpha<.80)translucent++;motion+=Math.abs(luminance(black,at)-luminance(later,at));}
            }
        }
        require(bands>80&&gaps>40,"ring samples covered visible geometry");
        require(translucent>bands*.80,"dust ring bands must transmit background");
        require(gapAlpha/gaps<bandAlpha/bands*.40,"ring gaps must actually reveal sky");
        require(motion/bands>.004,"ring material must rotate visibly over five seconds");
        int center=pixel(PLANET,1,36);
        require(transmission(black,white,center)<.015,"planet must remain opaque behind transparent rings");
        float[]start=render(diagnostic,1,0,36,true,0),loop=render(diagnostic,1,TAU,36,true,0);
        require(AtmosphereCheck.delta(start,loop).mean()<.00005,"Nebula ring loop seam");
        System.out.printf(Locale.ROOT,"PASS Celestial Nebula: %d/%d translucent band samples; band alpha %.4f; gap alpha %.4f; five-second ring motion %.5f; opaque sphere/periodic loop%n",
            translucent,bands,bandAlpha/bands,gapAlpha/gaps,motion/bands);
    }
    static void blackHoleChecks(){
        float[]dark=render(diagnostic,4,.7,36,true,0),light=render(diagnostic,4,.7,36,true,1);
        int center=pixel(HOLE,4,36);
        require(luminance(dark,center)<.025,"black hole center should be visibly black");
        require(transmission(dark,light,center)<.025,"black hole must occlude background");
        int bright=0,core=0;double highest=0;
        for(int i=0;i<dark.length;i+=4){double value=luminance(dark,i);highest=Math.max(highest,value);if(value>.35)bright++;if(value<.025&&transmission(dark,light,i)<.03)core++;}
        require(bright>150&&core>100,"BlackHole requires resolved bright disk and dark core");
        require(highest>.5,"BlackHole accretion glow missing");
        float[]fullFrame=render(4,.7),sub=render(4,.7+TAU/240/60),later=render(4,.7+TAU/240*5);
        var a=AtmosphereCheck.delta(fullFrame,sub);var b=AtmosphereCheck.delta(fullFrame,later);
        require(a.changed()>30&&a.mean()<.004&&b.mean()>a.mean()*4,"BlackHole animation must be frame-based and smooth");
            System.out.printf(Locale.ROOT,"PASS Celestial BlackHole: %d dark core pixels / %d bright disk pixels; max light %.4f; frame delta %.7f; five-second delta %.6f%n",core,bright,highest,a.mean(),b.mean());
    }
    static void exactCenters(){
        for(int mode:new int[]{1,4}){
            setup(diagnostic,mode,.7,36,true,1);glUniform1f(glGetUniformLocation(diagnostic,"TestExactCenter"),1);glDrawArrays(GL_TRIANGLES,0,3);
            float[]image=AtmosphereCheck.pixels();for(float channel:image)require(Float.isFinite(channel)&&channel>=0&&channel<=4,"exact center ray is finite");
            if(mode==4)require(luminance(image,0)<.025,"exact center ray must hit black core");AtmosphereCheck.depth(.63f);
        }
        System.out.println("PASS Celestial exact center rays: planet and BlackHole finite, including atan(0,0) guard");
    }
    static BufferedImage pair(double phase){
        int label=37;BufferedImage image=new BufferedImage(W*2,H+label,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(15,18,24));g.fillRect(0,0,image.getWidth(),label);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,14));
        g.drawString("Nebula — transparent rotating dust rings",12,24);g.drawString("BlackHole — accretion disk / dark core",W+12,24);
        g.drawImage(AtmosphereCheck.image(render(1,phase)),0,label,null);g.drawImage(AtmosphereCheck.image(render(4,phase)),W,label,null);g.dispose();return image;
    }
    static IIOMetadataNode child(IIOMetadataNode root,String name){for(int i=0;i<root.getLength();i++)if(root.item(i).getNodeName().equals(name))return(IIOMetadataNode)root.item(i);IIOMetadataNode item=new IIOMetadataNode(name);root.appendChild(item);return item;}
    static void previews()throws Exception{
        ImageIO.write(pair(.7),"PNG",output.resolve("celestial-detail-gpu-preview.png").toFile());
        Path gif=output.resolve("celestial-detail-gpu-animation.gif");ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(ImageOutputStream stream=ImageIO.createImageOutputStream(gif.toFile())){
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int frameIndex=0;frameIndex<75;frameIndex++){
                BufferedImage image=pair(.7+TAU/240*frameIndex/15.0);ImageWriteParam param=writer.getDefaultWriteParam();
                IIOMetadata metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
                String format=metadata.getNativeMetadataFormatName();IIOMetadataNode root=(IIOMetadataNode)metadata.getAsTree(format);
                IIOMetadataNode control=child(root,"GraphicControlExtension");control.setAttribute("disposalMethod","none");control.setAttribute("userInputFlag","FALSE");control.setAttribute("transparentColorFlag","FALSE");control.setAttribute("delayTime",frameIndex%3==0?"6":"7");control.setAttribute("transparentColorIndex","0");
                if(frameIndex==0){IIOMetadataNode ext=new IIOMetadataNode("ApplicationExtension");ext.setAttribute("applicationID","NETSCAPE");ext.setAttribute("authenticationCode","2.0");ext.setUserObject(new byte[]{1,0,0});child(root,"ApplicationExtensions").appendChild(ext);}
                metadata.setFromTree(format,root);writer.writeToSequence(new IIOImage(image,null,metadata),param);
            }
            writer.endWriteSequence();
        }finally{writer.dispose();}
        System.out.println("GPU preview (not Minecraft): "+output.resolve("celestial-detail-gpu-preview.png"));
        System.out.println("GPU animation (5 seconds / actual shader): "+gif);
    }
    static double timer(int program,int mode){
        setup(program,mode,.7,75,false,0);for(int i=0;i<32;i++)glDrawArrays(GL_TRIANGLES,0,3);glFinish();
        double[]samples=new double[9];int query=glGenQueries();
        try{for(int sample=0;sample<samples.length;sample++){
            glBeginQuery(GL_TIME_ELAPSED,query);for(int i=0;i<64;i++)glDrawArrays(GL_TRIANGLES,0,3);glEndQuery(GL_TIME_ELAPSED);
            samples[sample]=glGetQueryObjectui64(query,GL_QUERY_RESULT)/64.0/1_000_000;
        }}finally{glDeleteQueries(query);}
        Arrays.sort(samples);return samples[samples.length/2];
    }
    static void performance(){
        if(baseline==0){System.out.println("GPU_TIMER comparison skipped: optional previous-release baseline jar is unavailable.");return;}
        // Same offscreen target, projected view and draw count; no readbacks or CPU setup inside GPU queries.
        times.put("previous_nebula_ms",timer(baseline,1));times.put("updated_nebula_ms",timer(full,1));times.put("blackhole_ms",timer(full,4));
        times.put("previous_aurora_ms",timer(baseline,0));times.put("updated_aurora_ms",timer(full,0));
        times.forEach((name,time)->System.out.printf(Locale.ROOT,"GPU_TIMER %s=%.6f%n",name,time));
        require(glGetError()==GL_NO_ERROR,"GPU timer query errors");
        System.out.printf("GPU_TIMER hardware=%s resolution=%dx%d median=9 batches=64 draws; isolated shader time, not Minecraft FPS%n",glGetString(GL_RENDERER),W,H);
    }
    public static void main(String[]args)throws Exception{
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        require(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Celestial offscreen shader QA",0,0);require(window!=0,"OpenGL context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            glBindVertexArray(glGenVertexArrays());AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));frame=framebuffer(W,H,true);
            full=AtmosphereCheck.link("skybox.vsh","skybox.fsh");
            String vertex=preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/skybox.vsh"))),fragment=Files.readString(resources.resolve("assets/celeste/shaders/core/skybox.fsh"));
            require(fragment.contains("void main()"),"production main signature");
            String instrumented=fragment.replace("void main()","void productionMain()")+"\nuniform float TestBackground;uniform float TestExactCenter;\nvoid main(){vec3 d=TestExactCenter>.5?normalize(ColorModulator.x<2.0?vec3(-.30,.46,-.84):vec3(.40,.44,-.80)):normalize(worldDirection);vec3 bg=vec3(TestBackground);fragColor=vec4(ColorModulator.x<2.0?nebulaCelestial(d,ColorModulator.z,bg,vec3(0),0.0):blackHoleCelestial(d,ColorModulator.z,bg),1.0);}\n";
            diagnostic=AtmosphereCheck.linkSource(vertex,preprocess(instrumented),"unmodified celestial helper diagnostics");
            if(args.length>3&&!args[3].isBlank())try(ZipFile old=new ZipFile(args[3])){var entry=old.getEntry("assets/celeste/shaders/core/skybox.fsh");require(entry!=null,"previous release shader missing");try(var input=old.getInputStream(entry)){baseline=AtmosphereCheck.linkSource(vertex,preprocess(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)),"previous release baseline");}}
            nebulaChecks();blackHoleChecks();exactCenters();performance();previews();
            System.out.println("PASS Celestial GPU: "+checks+" assertions, production helper transparency/gaps/rotation/core/disk/animation and unchanged scene depth. Offscreen, not in-game.");
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
