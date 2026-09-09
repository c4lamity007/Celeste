import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.ImageOutputStream;
import com.google.gson.Gson;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.media.TimedLyrics;
import dev.c4lam.celeste.render.font.FontData;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyLyricFont;

/** Real production atlas, layout, vertex payload and GLSL. Offscreen detail, not Minecraft. */
public final class SkyLyricsShaderCheck extends ImpactCheck {
    static final int W=960,H=320,STRIDE=36;
    static long checks;
    static int program,atlas,indices,shaderFog,sceneDepth;
    static SkyLyricFont font;
    static SkyLyricFont.Layout layout;
    static TimedLyrics.WordCue cue;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void cpu() {
        var simple=font.layout("Clean custom typography");
        check(!simple.truncated() && simple.width()>1,"real SF Pro layout");
        var fallback=font.layout("A\uD83E\uDE90B");
        check(fallback.glyphs().size()==3,"missing Unicode is a visible fallback, not dropped");
        check(fallback.glyphs().get(1).sourceChar()==1 && fallback.glyphs().get(2).sourceChar()==3,"UTF16 offsets survive supplementary fallback");
        check(font.layout("x ".repeat(150)).truncated(),"long line gets bounded ellipsis");
        check(font.layout("").glyphs().isEmpty(),"empty line emits no layout");
        var random=new Random(6142);
        for(int test=0;test<150;test++){
            var s=new StringBuilder();for(int i=0;i<test*2;i++)s.append((char)(32+random.nextInt(93)));
            var l=font.layout(s.toString());check(l.width()<=SkyLyricFont.MAX_WIDTH,"bounded apparent world width");
            check(l.glyphs().size()<=SkyLyricFont.MAX_CHARACTERS,"bounded atlas glyph count");
            for(var g:l.glyphs()){
                check(g.minU()>=0&&g.maxU()<=1&&g.minV()>=0&&g.maxV()<=1,"atlas UV bounds");
                check(Float.isFinite(g.left())&&Float.isFinite(g.top())&&g.left()<=g.right()&&g.top()<=g.bottom(),"finite glyph geometry");
                check(Math.abs(g.left())<=SkyLyricFont.MAX_WIDTH/2+.01&&Math.abs(g.right())<=SkyLyricFont.MAX_WIDTH/2+.01,"centered singleline bounds");
            }
        }
        check(SkyLyricFont.mix(0x336699,0xFFEEAA,0)==0x336699,"solid color exact");
        check(SkyLyricFont.mix(0x336699,0xFFEEAA,1)==0xFFEEAA,"gradient endpoint exact");
        int[] count={0};SkyLyricFont.emit(layout,cue,0,0,0,0,0,0,(x,y,u,v,c,lx,ly,p,s,l)->count[0]++);
        check(count[0]==0,"opacity zero is inert");
        var word=new TimedLyrics.Word(0,5,1000,2000,false);
        check(SkyLyricFont.evaporation(word,0)==0 && SkyLyricFont.evaporation(word,1000)==0,"future words stable");
        check(SkyLyricFont.evaporation(word,1500)==.5,"word interval phase");
        check(SkyLyricFont.evaporation(word,2000)==1 && SkyLyricFont.evaporation(word,2650)==2,"bounded upward residual");
        for(long t=0;t<6000;t+=13){
            int[] emitted={0};for(int layer=0;layer<2;layer++)SkyLyricFont.emit(layout,cue,t,.9f,0x99EEFF,0xDDAAFF,1,layer,
                (x,y,u,v,c,lx,ly,p,s,l)->{emitted[0]++;check(Float.isFinite(x)&&Float.isFinite(y)&&lx>=0&&lx<=32767&&ly>=0&&ly<=32767&&p>=0&&p<=32767,"packed mesh range");});
            check(emitted[0]<=SkyLyricFont.MAX_VERTICES,"two bounded batches");
        }
        System.out.println("PASS Sky Lyrics layout: "+checks+" checks; SF Pro metadata, Unicode fallback, offset retention, truncation, word phases, colors, bounded mesh");
    }
    static int link()throws Exception{
        int vs=shader(GL_VERTEX_SHADER,preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/sky_lyrics.vsh"))));
        int fs=shader(GL_FRAGMENT_SHADER,preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/sky_lyrics.fsh"))));
        int p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);
        String[] names={"Position","Color","UV0","UV1","UV2","Normal"};for(int i=0;i<names.length;i++)glBindAttribLocation(p,i,names[i]);
        glLinkProgram(p);check(glGetProgrami(p,GL_LINK_STATUS)!=0,glGetProgramInfoLog(p));glDeleteShader(vs);glDeleteShader(fs);
        for(int i=0;i<2;i++){int block=glGetUniformBlockIndex(p,i==0?"DynamicTransforms":"Projection");check(block!=GL_INVALID_INDEX,"native transform blocks");glUniformBlockBinding(p,block,i);}
        glUseProgram(p);glUniform1i(glGetUniformLocation(p,"Sampler0"),0);return p;
    }
    static int upload(long time,int gradient,float opacity,int layer) {
        ByteBuffer data=BufferUtils.createByteBuffer(SkyLyricFont.MAX_VERTICES*STRIDE);
        SkyLyricFont.emit(layout,cue,time,opacity,0xB6F1FF,0xD3A8FF,gradient,layer,(x,y,u,v,color,lx,ly,phase,seed,vapor)->{
            data.putFloat(x).putFloat(-y).putFloat(0).put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)(color>>>24));
            data.putFloat(u).putFloat(v).putShort((short)lx).putShort((short)ly).putShort((short)phase).putShort((short)seed);
            data.put((byte)(vapor*127)).put((byte)0).put((byte)0).put((byte)0);
        });
        int count=data.position()/STRIDE;check(count%4==0,"complete native quads");data.flip();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,data,GL_STREAM_DRAW);return count;
    }
    static float[] lyricProjection() {
        float f=(float)(1/Math.tan(Math.toRadians(75)/2));float[] projection=new float[16];
        projection[0]=f*H/W;projection[5]=f;projection[10]=-1.002f;projection[11]=-1;projection[14]=-.2002f;
        return projection;
    }
    static void lyricTransforms(boolean realScale) {
        float[] projection=AtmosphereCheck.identity(),view=AtmosphereCheck.identity();
        if(realScale){projection=lyricProjection();view[0]=view[5]=view[10]=SkyLyricFont.EM_SCALE;view[14]=-18;}
        else {float span=Math.max(layout.width()+2,10);projection[0]=2/span;projection[5]=2/(span*H/W);projection[10]=-1;}
        AtmosphereCheck.transforms(view,projection,new float[]{1,1,1,1},0);
    }
    static void drawLyrics(long time,int gradient,float opacity,int onlyLayer,boolean realScale) {
        lyricTransforms(realScale);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,atlas);
        for(int layer=1;layer>=0;layer--){if(onlyLayer>=0&&layer!=onlyLayer)continue;int count=upload(time,gradient,opacity,layer);glDrawElements(GL_TRIANGLES,count/4*6,GL_UNSIGNED_INT,0);}
    }
    static void clear(float depth) {
        glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,W,H);glDepthMask(true);glClearColor(0,0,0,0);glClearDepth(depth);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    static float[] pixels(float depth) {
        FloatBuffer z=BufferUtils.createFloatBuffer(W*H);glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,z);for(int i=0;i<z.limit();i++)check(Math.abs(z.get(i)-depth)<1e-6,"scene depth remains untouched");
        FloatBuffer rgba=BufferUtils.createFloatBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,rgba);float[] result=new float[rgba.remaining()];rgba.get(result);
        for(float v:result)check(Float.isFinite(v)&&v>=0&&v<=1.00001f,"bounded shader RGBA");check(glGetError()==GL_NO_ERROR,"native GPU pipeline error");return result;
    }
    static float[] render(long time,int gradient,float opacity,float depth,int onlyLayer,boolean realScale) {
        clear(depth);drawLyrics(time,gradient,opacity,onlyLayer,realScale);return pixels(depth);
    }
    static float depthAt(float distance) {
        float[] projection=lyricProjection();return ((-projection[10]*distance+projection[14])/distance)*.5f+.5f;
    }
    static void fog(int mode,float density) {
        // Exactly the production fog shader and private depth copy, not a flat
        // color stand-in. Glyph depth writes remain disabled in both draw orders.
        glBindFramebuffer(GL_READ_FRAMEBUFFER,frame);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,sceneDepth);
        glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,W,H);
        AtmosphereCheck.transforms(AtmosphereCheck.identity(),lyricProjection(),new float[]{mode,density,32,.7f},0);
        glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,W,H);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(shaderFog);glBindTexture(GL_TEXTURE_2D,sceneDepth);glDrawArrays(GL_TRIANGLES,0,3);
    }
    static float[] fogComposite(int mode,float density,float depth,long time,float opacity,boolean lyricsAfterFog) {
        clear(depth);
        if(!lyricsAfterFog)drawLyrics(time,1,opacity,-1,true);
        fog(mode,density);
        if(lyricsAfterFog)drawLyrics(time,1,opacity,-1,true);
        return pixels(depth);
    }
    static void fogChecks() {
        float depth=depthAt(64);
        for(long time:new long[]{0,500}) {
            float[] glyph=render(time,1,.9f,depth,-1,true);
            for(int mode=0;mode<5;mode++) {
                float[] background=fogComposite(mode,1,depth,time,0,true);
                float[] late=fogComposite(mode,1,depth,time,.9f,true);
                float[] early=fogComposite(mode,1,depth,time,.9f,false);
                double oldError=0;int visible=0;
                for(int p=0;p<glyph.length;p+=4) {
                    float alpha=glyph[p+3];
                    for(int channel=0;channel<4;channel++) {
                        float expected=glyph[p+channel]+background[p+channel]*(1-alpha);
                        check(Math.abs(late[p+channel]-expected)<.001f,"post-fog glyph/vapor uses its own color and opacity exactly once");
                        if(channel<3&&alpha>.05f)oldError+=Math.abs(early[p+channel]-expected);
                    }
                    if(alpha>.05f)visible++;
                    if(alpha==0)for(int channel=0;channel<4;channel++)
                        check(late[p+channel]==background[p+channel],"lyrics leave fog outside glyph coverage unchanged");
                }
                check(visible>3&&oldError/(visible*3)>.01,"old pre-fog ordering visibly attenuates nearby lyrics using far terrain depth");
            }
        }
        float near=depthAt(6);
        check(difference(fogComposite(0,1,near,0,.9f,true),fogComposite(0,1,near,0,0,true))==0,
            "post-fog text remains hidden behind foreground terrain");
        check(difference(fogComposite(0,0,depth,0,.9f,true),render(0,1,.9f,depth,-1,true))==0,
            "disabled fog does not alter lyric color or opacity");
        System.out.println("PASS Sky Lyrics/fog GPU: five actual fog presets, text/vapor after-composite legibility, unchanged world fog/depth, foreground occlusion, disabled identity");
    }
    static double energy(float[] frame){double sum=0;for(int i=3;i<frame.length;i+=4)sum+=frame[i];return sum;}
    static double difference(float[] a,float[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/a.length;}
    static BufferedImage image(float[] rgba){var image=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);for(int y=0;y<H;y++)for(int x=0;x<W;x++){int at=((H-y-1)*W+x)*4,rgb=0;for(int c=0;c<3;c++)rgb=rgb<<8|Math.round(Math.clamp(rgba[at+c],0,1)*255);image.setRGB(x,y,rgb);}return image;}
    static void gpuChecks() {
        float[] start=render(0,1,.9f,1,-1,false),middle=render(2100,1,.9f,1,-1,false),end=render(5650,1,.9f,1,-1,false);
        check(energy(start)>100,"MSDF line visibly rendered");check(energy(middle)>1&&energy(middle)<energy(start),"finished words evaporate while future text remains");check(energy(end)==0,"all words and residuals eventually disappear");
        check(energy(render(0,1,0,1,-1,false))==0,"disabled opacity leaves scene unchanged");
        check(energy(render(0,1,.9f,.25f,-1,false))==0,"terrain occludes text and vapor");
        check(difference(start,render(0,1,.9f,1,-1,false))==0,"paused identical media time gives identical pixels");
        check(difference(start,render(0,0,.9f,1,-1,false))>.00001,"horizontal gradient differs from solid");
        check(difference(start,render(0,2,.9f,1,-1,false))>.00001,"vertical gradient differs from horizontal");
        check(energy(render(0,1,.9f,1,-1,true))>5,"preserved apparent scale readable at distance18");
        check(energy(render(500,1,.9f,1,1,false))>0,"actual evaporation pass produces wisps");
        check(difference(render(490,1,.9f,1,1,false),render(507,1,.9f,1,1,false))>0,"upward material evolves within frame time");
        System.out.println("PASS Sky Lyrics GPU: custom MTSDF visible, both gradients, smooth word evaporation, paused identity, depth occlusion/unchanged depth, exact18-block scale");
    }
    static IIOMetadataNode child(IIOMetadataNode root,String name){for(int i=0;i<root.getLength();i++)if(root.item(i).getNodeName().equals(name))return(IIOMetadataNode)root.item(i);var n=new IIOMetadataNode(name);root.appendChild(n);return n;}
    static void previews()throws Exception{
        ImageIO.write(image(render(0,1,.9f,1,-1,false)),"PNG",output.resolve("sky-lyrics-msdf-detail.png").toFile());
        ImageIO.write(image(render(500,1,.9f,1,-1,false)),"PNG",output.resolve("sky-lyrics-evaporation-detail.png").toFile());
        ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(ImageOutputStream stream=ImageIO.createImageOutputStream(output.resolve("sky-lyrics-evaporation.gif").toFile())){
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int i=0;i<75;i++){
                var image=image(render(Math.round(i*1000/15.0),1,.9f,1,-1,false));var param=writer.getDefaultWriteParam();var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
                String format=metadata.getNativeMetadataFormatName();var root=(IIOMetadataNode)metadata.getAsTree(format);var control=child(root,"GraphicControlExtension");
                control.setAttribute("disposalMethod","none");control.setAttribute("userInputFlag","FALSE");control.setAttribute("transparentColorFlag","FALSE");control.setAttribute("delayTime",i%3==0?"6":"7");control.setAttribute("transparentColorIndex","0");
                if(i==0){var ext=new IIOMetadataNode("ApplicationExtension");ext.setAttribute("applicationID","NETSCAPE");ext.setAttribute("authenticationCode","2.0");ext.setUserObject(new byte[]{1,0,0});child(root,"ApplicationExtensions").appendChild(ext);}
                metadata.setFromTree(format,root);writer.writeToSequence(new IIOImage(image,null,metadata),param);
            }writer.endWriteSequence();
        }finally{writer.dispose();}
        System.out.println("GPU preview (offscreen magnified detail, notMinecraft): "+output.resolve("sky-lyrics-msdf-detail.png")+" and "+output.resolve("sky-lyrics-evaporation.gif"));
    }
    public static void main(String[]args)throws Exception{
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        try(var reader=Files.newBufferedReader(resources.resolve("assets/celeste/fonts/sf_pro/sf_semibold.json"))){font=new SkyLyricFont(new Gson().fromJson(reader,FontData.class));}
        cue=TimedLyrics.parse("[00:00.00]Let the music turn into starlight\n[00:05.00] ").wordCue(0,5000);layout=font.layout(cue.text());cpu();
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Sky Lyrics offscreen QA",0,0);check(window!=0,"GPU context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,STRIDE,0);glEnableVertexAttribArray(1);glVertexAttribPointer(1,4,GL_UNSIGNED_BYTE,true,STRIDE,12);
            glEnableVertexAttribArray(2);glVertexAttribPointer(2,2,GL_FLOAT,false,STRIDE,16);glEnableVertexAttribArray(3);glVertexAttribIPointer(3,2,GL_SHORT,STRIDE,24);glEnableVertexAttribArray(4);glVertexAttribIPointer(4,2,GL_SHORT,STRIDE,28);glEnableVertexAttribArray(5);glVertexAttribPointer(5,3,GL_BYTE,true,STRIDE,32);
            indices=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);IntBuffer elements=BufferUtils.createIntBuffer(SkyLyricFont.MAX_VERTICES/4*6);for(int i=0;i<SkyLyricFont.MAX_VERTICES;i+=4)for(int j:new int[]{0,1,2,0,2,3})elements.put(i+j);elements.flip();glBufferData(GL_ELEMENT_ARRAY_BUFFER,elements,GL_STATIC_DRAW);
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));frame=framebuffer(W,H,true);program=link();atlas=texture("assets/celeste/fonts/sf_pro/sf_semibold.png");
            shaderFog=AtmosphereCheck.link("shader_fog.vsh","shader_fog.fsh");glUseProgram(shaderFog);int depthSampler=glGetUniformLocation(shaderFog,"SceneDepth");check(depthSampler>=0,"production fog depth sampler");glUniform1i(depthSampler,0);sceneDepth=attachment(true,W,H);
            gpuChecks();fogChecks();previews();System.out.println("PASS Sky Lyrics complete: "+checks+" bounded pixel/behavior checks; "+glGetString(GL_RENDERER));
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
