import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Executes the actual compiled feature with only world/settings/GPU reset boundaries substituted. */
public final class ShaderFogLifecycleCheck {
    static final String FEATURE="dev/c4lam/celeste/module/impl/visual/expansion/CustomFogFeature";
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public interface ProbeFeature { }
    public static final class ProbeWorld { }
    public static final class ProbeClient { public ProbeWorld world; public Object player=new Object(); }
    public static final class ProbeContext {
        public final ProbeClient client=new ProbeClient();
        public final Map<String,String> modes=new HashMap<>();
        public final Map<String,Double> sliders=new HashMap<>();
        public ProbeClient client(){return client;}
        public String mode(String label,String fallback){return modes.getOrDefault(label,fallback);}
        public double slider(String label,double fallback){return sliders.getOrDefault(label,fallback);}
    }
    public static final class ProbeRenderer { public static int resets;public static void reset(){resets++;} }
    static final class FixtureLoader extends ClassLoader {
        final Path classes;
        FixtureLoader(Path classes){super(ShaderFogLifecycleCheck.class.getClassLoader());this.classes=classes;}
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal=name.replace('.','/');
            if(!internal.equals(FEATURE)&&!internal.startsWith(FEATURE+"$"))throw new ClassNotFoundException(name);
            try {
                ClassReader reader=new ClassReader(Files.readAllBytes(classes.resolve(internal+".class")));
                ClassWriter writer=new ClassWriter(0);
                Map<String,String> substitutions=Map.of(
                    "dev/c4lam/celeste/module/ModuleFeature","ShaderFogLifecycleCheck$ProbeFeature",
                    "dev/c4lam/celeste/module/ModuleContext","ShaderFogLifecycleCheck$ProbeContext",
                    "net/minecraft/client/MinecraftClient","ShaderFogLifecycleCheck$ProbeClient",
                    "net/minecraft/client/world/ClientWorld","ShaderFogLifecycleCheck$ProbeWorld",
                    "net/minecraft/client/network/ClientPlayerEntity","java/lang/Object",
                    "dev/c4lam/celeste/module/impl/visual/expansion/ShaderFogRenderer","ShaderFogLifecycleCheck$ProbeRenderer");
                reader.accept(new ClassRemapper(writer,new Remapper(){
                    @Override public String map(String original){return substitutions.getOrDefault(original,original);}
                }),0);
                byte[] bytes=writer.toByteArray();return defineClass(name,bytes,0,bytes.length);
            }catch(Exception error){throw new ClassNotFoundException(name,error);}
        }
    }
    static CustomFogMath.Settings settings(Method method,ProbeWorld world)throws Exception{
        return (CustomFogMath.Settings)method.invoke(null,world);
    }
    public static void main(String[] args)throws Exception{
        Class<?> feature=new FixtureLoader(Path.of(args[0])).loadClass(FEATURE.replace('/','.'));
        Object module=feature.getConstructor().newInstance();
        Method enable=feature.getMethod("onEnable",ProbeContext.class),tick=feature.getMethod("onStartTick",ProbeContext.class),
            disable=feature.getMethod("onDisable",ProbeContext.class),changed=feature.getMethod("onWorldChanged",ProbeContext.class),
            setting=feature.getDeclaredMethod("settings",ProbeWorld.class);
        setting.setAccessible(true);
        ProbeContext context=new ProbeContext();ProbeWorld first=new ProbeWorld(),second=new ProbeWorld();
        require(settings(setting,first)==null,"initial state must be inactive");
        context.client.world=first;enable.invoke(module,context);
        require(settings(setting,first)!=null&&settings(setting,first).shader(),"default enable selects shader mode");
        require(settings(setting,second)==null,"another world cannot access settings");
        require(settings(setting,first).shaderPreset().equals("Match Sky"),"default preset follows sky");
        context.modes.put("Shader","Abyss");context.sliders.put("Density",.7);context.sliders.put("Distance",256.0);tick.invoke(module,context);
        var snapshot=settings(setting,first);
        require(snapshot.shaderPreset().equals("Abyss")&&snapshot.density()==.7&&snapshot.distance()==256,"tick updates settings");
        int before=ProbeRenderer.resets;context.modes.put("Rendering","Native");tick.invoke(module,context);
        require(!settings(setting,first).shader()&&ProbeRenderer.resets==before+1,"switching native invalidates GPU frames/resources");
        tick.invoke(module,context);require(ProbeRenderer.resets==before+1,"native idle does not thrash resources");
        context.modes.put("Rendering","Shader");tick.invoke(module,context);
        before=ProbeRenderer.resets;changed.invoke(module,context);
        require(settings(setting,first)==null&&ProbeRenderer.resets==before+1,"world transition resets active state and GPU");
        context.client.world=second;tick.invoke(module,context);
        require(settings(setting,first)==null&&settings(setting,second)!=null,"new world identity replaces old world");
        before=ProbeRenderer.resets;context.client.player=null;tick.invoke(module,context);
        require(settings(setting,second)==null&&ProbeRenderer.resets==before+1,"missing player drops stale settings/resources");
        context.client.player=new Object();enable.invoke(module,context);before=ProbeRenderer.resets;
        context.client.world=null;tick.invoke(module,context);
        require(settings(setting,second)==null&&ProbeRenderer.resets==before+1,"disconnect drops stale settings/resources");
        context.client.world=first;
        for(int i=0;i<50;i++) {
            enable.invoke(module,context);require(settings(setting,first)!=null,"repeat enable");
            before=ProbeRenderer.resets;disable.invoke(module,context);
            require(settings(setting,first)==null&&ProbeRenderer.resets==before+1,"disable invalidates frame generation and cleanup");
            disable.invoke(module,context);require(settings(setting,first)==null,"repeated disable remains inactive");
        }
        System.out.println("PASS ShaderFogLifecycle: "+checks+" actual compiled feature assertions; enable/update/native switch/world identity/disconnect/disable reset boundaries. GPU closure itself reviewed separately.");
    }
}
