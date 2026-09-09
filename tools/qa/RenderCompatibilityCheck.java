import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import org.joml.Vector4f;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** Executes the real optional renderer helper with absent/live/broken public-API boundaries. */
public final class RenderCompatibilityCheck {
    private static final String HELPER = "dev/c4lam/celeste/compat/RenderCompatibility";
    private static final String IRIS = "net/irisshaders/iris/api/v0/IrisApi";
    public static boolean present, pack, shadow, failQuery;
    public static int detections, queries, checks;

    public interface LoaderProbe {
        static LoaderProbe getInstance() { return id -> { require(id.equals("iris"), "Wrong optional mod ID"); detections++; return present; }; }
        boolean isModLoaded(String id);
    }
    public static final class FakeIris {
        public static FakeIris getInstance() { return new FakeIris(); }
        public boolean isShaderPackInUse() { queries++; if (failQuery) throw new IllegalStateException("test query failure"); return pack; }
        public boolean isRenderingShadowPass() { queries++; if (failQuery) throw new IllegalStateException("test query failure"); return shadow; }
    }
    public static final class IrisCaptureProbe {
        public static final IrisCaptureProbe INSTANCE = new IrisCaptureProbe();
        public final float[] color = new float[3];
        public void setFogColor(float r, float g, float b) { color[0] = r; color[1] = g; color[2] = b; }
    }
    public static final class TestCallback {
        private final Object value;
        public TestCallback(Object value) { this.value = value; }
        public Object getReturnValue() { return value; }
    }
    static void irisFogCapture(JarFile jar) throws Exception {
        String name = "net/irisshaders/iris/mixin/MixinFogRenderer";
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(jar.getJarEntry(name + ".class")).readAllBytes()).accept(node, 0);
        MethodNode capture = node.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        List<AnnotationNode> annotations = capture.visibleAnnotations != null ? capture.visibleAnnotations : capture.invisibleAnnotations;
        require(annotations != null && annotations.stream().anyMatch(a -> a.desc.endsWith("/Inject;")), "Installed Iris return capture is no longer injected");
        node.methods.removeIf(m -> !(m.name.equals("<init>") || m == capture));
        capture.access = Opcodes.ACC_PUBLIC;
        node.visibleAnnotations = node.invisibleAnnotations = null;
        for (MethodNode m : node.methods) { m.visibleAnnotations = m.invisibleAnnotations = null; }
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        byte[] bytes = remap(writer.toByteArray(), Map.of(
            name, "IrisFogCaptureFixture",
            "net/minecraft/class_4184", "java/lang/Object",
            "net/minecraft/class_9779", "java/lang/Object",
            "net/minecraft/class_638", "java/lang/Object",
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", "RenderCompatibilityCheck$TestCallback",
            "net/irisshaders/iris/uniforms/CapturedRenderingState", "RenderCompatibilityCheck$IrisCaptureProbe"));
        Class<?> fixture = new ClassLoader(RenderCompatibilityCheck.class.getClassLoader()) {
            Class<?> fixture() { return defineClass("IrisFogCaptureFixture", bytes, 0, bytes.length); }
        }.fixture();
        Object instance = fixture.getConstructor().newInstance();
        java.lang.reflect.Method method = fixture.getMethod("render", Object.class, int.class, Object.class, float.class, Object.class, TestCallback.class);
        for (Vector4f color : List.of(new Vector4f(.42f,.57f,.66f,.73f), new Vector4f(0,0,0,1), new Vector4f(1,1,1,0))) {
            method.invoke(instance, null, 8, null, 0f, null, new TestCallback(color));
            require(IrisCaptureProbe.INSTANCE.color[0] == color.x && IrisCaptureProbe.INSTANCE.color[1] == color.y
                && IrisCaptureProbe.INSTANCE.color[2] == color.z, "Installed Iris did not preserve adjusted native fog color");
        }
    }
    static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static byte[] remap(byte[] bytes, Map<String,String> names) {
        ClassWriter out = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(out, new Remapper() {
            public String map(String value) { return names.getOrDefault(value, value); }
        }), 0);
        return out.toByteArray();
    }
    static final class Fixture extends ClassLoader {
        final Map<String,byte[]> definitions = new HashMap<>();
        int irisLoads;
        Fixture(Path compiled, boolean apiAvailable) throws Exception {
            super(RenderCompatibilityCheck.class.getClassLoader());
            Map<String,String> names = Map.of("net/fabricmc/loader/api/FabricLoader", "RenderCompatibilityCheck$LoaderProbe");
            for (String name : List.of(HELPER, HELPER + "$IrisQueries")) {
                definitions.put(name.replace('/','.'), remap(Files.readAllBytes(compiled.resolve(name + ".class")), names));
            }
            if (apiAvailable) {
                byte[] bytes = RenderCompatibilityCheck.class.getResourceAsStream("/RenderCompatibilityCheck$FakeIris.class").readAllBytes();
                definitions.put(IRIS.replace('/','.'), remap(bytes, Map.of("RenderCompatibilityCheck$FakeIris", IRIS)));
            }
        }
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    if (name.equals(IRIS.replace('/','.'))) {
                        irisLoads++;
                        if (!definitions.containsKey(name)) throw new ClassNotFoundException("Iris API intentionally unavailable");
                    }
                    byte[] bytes = definitions.get(name);
                    found = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(found);
                return found;
            }
        }
        Class<?> helper() throws Exception { return Class.forName(HELPER.replace('/','.'), true, this); }
    }
    static boolean call(Class<?> helper, String method) throws Exception { return (boolean) helper.getMethod(method).invoke(null); }
    static void reset(boolean installed) { present = installed; pack = shadow = failQuery = false; detections = queries = 0; }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("compiledClasses installedIrisJar");
        Path compiled = Path.of(args[0]);
        try (JarFile jar = new JarFile(args[1])) {
            irisFogCapture(jar);
            ClassNode api = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getJarEntry(IRIS + ".class")).readAllBytes()).accept(api, 0);
            for (String name : List.of("isShaderPackInUse", "isRenderingShadowPass"))
                require(api.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals("()Z")), "Installed Iris public method changed: " + name);
            require(api.methods.stream().anyMatch(m -> m.name.equals("getInstance") && m.desc.equals("()L" + IRIS + ";") && (m.access & Opcodes.ACC_STATIC) != 0), "Installed Iris API factory changed");
        }
        reset(false);
        Fixture absent = new Fixture(compiled, false); Class<?> helper = absent.helper();
        for (int i = 0; i < 10000; i++) require(!call(helper, "isShadowPass") && !call(helper, "isShaderPackActive"), "Absent Iris changed rendering");
        require(absent.irisLoads == 0 && detections == 1 && queries == 0, "Absent path does work beyond cached detection");
        reset(true);
        Fixture live = new Fixture(compiled, true); helper = live.helper();
        for (boolean p : List.of(false,true)) for (boolean s : List.of(false,true)) {
            pack = p; shadow = s;
            require(call(helper, "isShaderPackActive") == p && call(helper, "isShadowPass") == s, "Live pack/pass switch was cached incorrectly");
        }
        require(live.irisLoads == 1 && detections == 1 && queries == 8, "Optional API reflection repeated in frame path");
        failQuery = true;
        require(call(helper, "isShadowPass"), "Failed query allows shadow framebuffer writes");
        int afterFailure = queries;
        for (int i = 0; i < 1000; i++) require(call(helper, "isShadowPass") && call(helper, "isShaderPackActive"), "Failed API did not remain conservative");
        require(queries == afterFailure, "Failed API repeats exceptions each frame");
        reset(true);
        Fixture missing = new Fixture(compiled, false); helper = missing.helper();
        require(call(helper, "isShadowPass") && call(helper, "isShaderPackActive") && missing.irisLoads == 1, "Incompatible installed API did not preserve renderer");
        System.out.println("PASS Render compatibility: " + checks + " checks; installed Iris fog capture bytecode/API contract, absent zero-load path, dynamic pack/shadow state, single lookup, failed-query suppression. Not a game render test.");
    }
}
