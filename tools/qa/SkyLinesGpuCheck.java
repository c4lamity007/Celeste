import java.nio.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.ImageOutputStream;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyLinesMath;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyLinesMesh;

/** Exact production mesh + Minecraft position/color shader on the GPU. Not an in-game test. */
public final class SkyLinesGpuCheck extends ImpactCheck {
    static int pipeline, count, indexBuffer;
    static FloatBuffer vertices;
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static void prepare(SkyLinesMath.Pattern pattern, double time, double intensity) {
        vertices = BufferUtils.createFloatBuffer(7 * SkyLinesMesh.MAX_VERTICES);
        SkyLinesMesh.emit(SkyLinesMath.sample(pattern, time, intensity), (x,y,z,color) -> {
            vertices.put((float)x).put((float)y).put((float)z)
                .put((color>>16&255)/255f).put((color>>8&255)/255f).put((color&255)/255f).put((color>>>24)/255f);
        });
        vertices.flip(); count = vertices.remaining()/7;
        check(count <= SkyLinesMesh.MAX_VERTICES && count%4==0, "bounded complete quads");
        // Convert exactly the production quad order to triangle indices for OpenGL core profile.
        IntBuffer indices = BufferUtils.createIntBuffer(count/4*6);
        for (int base=0;base<count;base+=4) for(int i:new int[]{0,1,2,0,2,3}) indices.put(base+i);
        indices.flip();
        glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STREAM_DRAW);
    }
    static float[] draw(float sceneDepth) {
        AtmosphereCheck.clear(sceneDepth);
        float[] perspective=AtmosphereCheck.perspective();perspective[10]=-1.0003907f;perspective[14]=-.20003907f;
        AtmosphereCheck.transforms(AtmosphereCheck.rotation(Math.PI/2,-.5),perspective,new float[]{1,1,1,1},0);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE);
        glUseProgram(pipeline);glDrawElements(GL_TRIANGLES,count/4*6,GL_UNSIGNED_INT,0);
        check(glGetError()==GL_NO_ERROR,"OpenGL draw error");AtmosphereCheck.depth(sceneDepth);
        return AtmosphereCheck.pixels();
    }
    static java.awt.image.BufferedImage preview(double time) {
        prepare(SkyLinesMath.Pattern.LIGHT_RAILS,time,.8);
        AtmosphereCheck.clear(1);
        float[] projection=AtmosphereCheck.perspective();projection[10]=-1.0003907f;projection[14]=-.20003907f;
        float[] view=AtmosphereCheck.rotation(Math.PI/2,-.75);
        AtmosphereCheck.transforms(view,projection,new float[]{1,.85f,(float)(time*Math.PI*2/240),.7f},0);
        AtmosphereCheck.fog(1000,1024);glUseProgram(AtmosphereCheck.sky);glDrawArrays(GL_TRIANGLES,0,3);
        AtmosphereCheck.transforms(view,projection,new float[]{1,1,1,1},0);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE);
        glUseProgram(pipeline);glDrawElements(GL_TRIANGLES,count/4*6,GL_UNSIGNED_INT,0);
        check(glGetError()==GL_NO_ERROR,"actual sky and rails composite");AtmosphereCheck.depth(1);
        return AtmosphereCheck.image(AtmosphereCheck.pixels());
    }
    static IIOMetadataNode child(IIOMetadataNode root,String name){for(int i=0;i<root.getLength();i++)if(root.item(i).getNodeName().equals(name))return(IIOMetadataNode)root.item(i);IIOMetadataNode n=new IIOMetadataNode(name);root.appendChild(n);return n;}
    static void previews()throws Exception {
        ImageIO.write(preview(0),"PNG",output.resolve("light-rails-nebula-preview.png").toFile());
        ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(ImageOutputStream stream=ImageIO.createImageOutputStream(output.resolve("light-rails-animation.gif").toFile())){
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int i=0;i<75;i++){
                var image=preview(i/15.0);var param=writer.getDefaultWriteParam();
                var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
                String format=metadata.getNativeMetadataFormatName();var root=(IIOMetadataNode)metadata.getAsTree(format);
                var control=child(root,"GraphicControlExtension");control.setAttribute("disposalMethod","none");control.setAttribute("userInputFlag","FALSE");control.setAttribute("transparentColorFlag","FALSE");control.setAttribute("delayTime",i%3==0?"6":"7");control.setAttribute("transparentColorIndex","0");
                if(i==0){var ext=new IIOMetadataNode("ApplicationExtension");ext.setAttribute("applicationID","NETSCAPE");ext.setAttribute("authenticationCode","2.0");ext.setUserObject(new byte[]{1,0,0});child(root,"ApplicationExtensions").appendChild(ext);}
                metadata.setFromTree(format,root);writer.writeToSequence(new IIOImage(image,null,metadata),param);
            }
            writer.endWriteSequence();
        }finally{writer.dispose();}
        System.out.println("GPU preview: "+output.resolve("light-rails-nebula-preview.png")+"; 5-second animation: "+output.resolve("light-rails-animation.gif"));
    }
    public static void main(String[] args) throws Exception {
        minecraft=new ZipFile(args[0]);output=Path.of(args[1]);Files.createDirectories(output);
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(AtmosphereCheck.W,AtmosphereCheck.H,"Sky lines hidden GPU check",0,0);
        check(window!=0,"GPU context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();indexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,28,0);
            glEnableVertexAttribArray(2);glVertexAttribPointer(2,4,GL_FLOAT,false,28,12);
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));
            AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));
            AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));
            frame=framebuffer(AtmosphereCheck.W,AtmosphereCheck.H,true);
            String vs,fs;
            try(var input=minecraft.getInputStream(minecraft.getEntry("assets/minecraft/shaders/core/position_color.vsh"))){vs=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            try(var input=minecraft.getInputStream(minecraft.getEntry("assets/minecraft/shaders/core/position_color.fsh"))){fs=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            pipeline=AtmosphereCheck.linkSource(preprocess(vs),preprocess(fs),"native position/color");
            for(var pattern:SkyLinesMath.Pattern.values()) {
                prepare(pattern,12,.65);float[] visible=draw(1);
                int coverage=0;for(int i=0;i<visible.length;i+=4)if(visible[i]+visible[i+1]+visible[i+2]>.001)coverage++;
                check(coverage>20,"visible sky geometry "+pattern);
                for(float v:visible)check(Float.isFinite(v)&&v>=0,"finite colors");
                ImageIO.write(AtmosphereCheck.image(visible),"PNG",output.resolve(pattern.name().toLowerCase()+"-gpu.png").toFile());
                prepare(pattern,13,.65);float[] moving=draw(1);
                double delta=0;for(int i=0;i<visible.length;i+=4)delta+=Math.abs(moving[i]-visible[i])+Math.abs(moving[i+1]-visible[i+1])+Math.abs(moving[i+2]-visible[i+2]);
                check(delta>.01,"actual GPU material motion "+pattern);
                float[] blocked=draw(.5f);for(float v:blocked)check(v==0,"terrain occludes sky lines");
                prepare(pattern,12,0);for(float v:draw(1))check(v==0,"disable clears sky lines");
                System.out.println("PASS "+pattern+": production mesh visible ("+coverage+" pixels), bounded, zero disabled, terrain occlusion, main depth unchanged");
            }
            if(args.length>2){resources=Path.of(args[2]);AtmosphereCheck.sky=AtmosphereCheck.link("skybox.vsh","skybox.fsh");previews();}
            System.out.println("GPU: "+glGetString(GL_RENDERER)+"; Minecraft native position/color shader. Preview is not in-game evidence.");
        } finally {minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
