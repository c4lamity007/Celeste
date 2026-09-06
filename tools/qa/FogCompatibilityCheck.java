import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath.*;
import net.minecraft.client.render.fog.FogData;
import org.joml.Vector4f;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Executes the compiled Celeste handlers, Sodium's real capture/record, and Minecraft's real
 * UBO writer in an isolated bytecode fixture. Only world/auth discovery and Mixin Args are
 * substituted; production control flow, shared-field writes and consumer code are retained.
 * Separately checks injection annotations/order/local scope against the exact MC/Sodium jars.
 * This is not a Minecraft launch or a claim that the complete Mixin transformer was booted.
 */
public final class FogCompatibilityCheck implements Opcodes {
    private static final String BASE = "dev/c4lam/celeste/module/impl/visual/expansion/";
    private static final String MIXIN = "dev/c4lam/celeste/mixin/module/CustomFogMixin";
    private static final String FOG = "net/minecraft/client/render/fog/FogRenderer";
    private static final String DATA = "net/minecraft/client/render/fog/FogData";
    private static final String SODIUM = "net/caffeinemc/mods/sodium/mixin/core/render/world/FogRendererMixin";
    private static final String PARAMETERS = "net/caffeinemc/mods/sodium/client/util/FogParameters";
    private static final String WRITER = "(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V";
    private static final String WORLD = "(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;";
    private static final Conditions AIR = new Conditions(true, true, Medium.AIR, false, false, false, false);
    private static int checks, scenarios, expectedOldFailures;
    private static String[] nativeFields;

    public interface LoaderProbe {
        static LoaderProbe getInstance() { return id -> { require(id.equals("sodium"), "Wrong mod detection ID"); loaderCalls++; return sodiumPresent; }; }
        boolean isModLoaded(String id);
    }
    private static boolean sodiumPresent;
    private static int loaderCalls;

    /** Runtime context stand-in; actual CustomFogMath handles enable/density/hazard behavior. */
    public static final class AdjustmentProbe {
        static Settings settings;
        static Conditions conditions;
        static int calls;
        public static Fog adjust(Object camera, Object world, Vector4f color, float start, float end,
                                 float renderStart, float renderEnd, float skyEnd, float cloudEnd) {
            calls++;
            Fog original = new Fog(new Color(color.x, color.y, color.z, color.w), start, end,
                renderStart, renderEnd, skyEnd, cloudEnd);
            Fog adjusted = CustomFogMath.apply(original, settings, conditions);
            return adjusted == original ? null : adjusted;
        }
    }
    public static final class TestArgs {
        public final Object[] values;
        TestArgs(Object[] values) { this.values = values; }
        public Object get(int index) { return values[index]; }
        public void set(int index, Object value) { values[index] = value; }
    }

    static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static void exact(float actual, float expected, String message) {
        require(Float.floatToIntBits(actual) == Float.floatToIntBits(expected), message + ": " + actual + " != " + expected);
    }
    static byte[] entry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var entry = archive.getJarEntry(name);
            if (entry == null) throw new IllegalArgumentException("Missing " + name + " in " + jar);
            return archive.getInputStream(entry).readAllBytes();
        }
    }
    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
    static MethodNode method(ClassNode node, String name, String descriptor) {
        List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name)
            && (descriptor == null || m.desc.equals(descriptor))).toList();
        require(matches.size() == 1, "Expected one method " + node.name + "." + name + descriptor);
        return matches.getFirst();
    }
    static AnnotationNode annotation(List<AnnotationNode> list, String descriptor) {
        if (list == null) return null;
        return list.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElse(null);
    }
    static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
    static AnnotationNode injection(MethodNode method, String kind) {
        AnnotationNode result = annotation(method.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/" + kind + ";");
        if (result == null) result = annotation(method.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/" + kind + ";");
        require(result != null, "Missing compiled @" + kind + " on " + method.name);
        return result;
    }
    static AnnotationNode at(AnnotationNode annotation) {
        Object a = value(annotation, "at");
        if (a instanceof List<?> list) {
            require(list.size() == 1, "Unexpected multiple injection points");
            a = list.getFirst();
        }
        require(a instanceof AnnotationNode, "Missing compiled @At");
        return (AnnotationNode) a;
    }
    static void before(AnnotationNode at) {
        Object shift = value(at, "shift");
        require(shift == null || shift instanceof String[] e && (e[1].equals("NONE") || e[1].equals("BEFORE")),
            "Hook moved after data consumption");
        require("INVOKE".equals(value(at, "value")), "Hook must target invocation");
    }
    static int callIndex(MethodNode method, String owner, String name, String descriptor) {
        int index = -1, count = 0;
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode c && c.owner.equals(owner)
                && c.name.equals(name) && c.desc.equals(descriptor)) { index = i; count++; }
        }
        require(count == 1, "Expected unique target call " + owner + "." + name + " in " + method.name);
        return index;
    }
    static void local(MethodNode target, int point, String descriptor, int expectedSlot, MethodNode handler, int parameter) {
        var live = target.localVariables.stream().filter(v -> v.desc.equals(descriptor)
            && target.instructions.indexOf(v.start) <= point && point < target.instructions.indexOf(v.end)).toList();
        require(live.size() == 1 && live.getFirst().index == expectedSlot,
            "Ambiguous/unavailable @Local " + descriptor + " at hook");
        List<AnnotationNode> annotations = handler.invisibleParameterAnnotations == null ? null
            : handler.invisibleParameterAnnotations[parameter];
        AnnotationNode local = annotation(annotations, "Lcom/llamalad7/mixinextras/sugar/Local;");
        require(local != null, "Missing compiled typed @Local on parameter " + parameter);
        require(value(local, "ordinal") == null && value(local, "index") == null,
            "Fixture expects unique type-based local capture");
        require(Type.getArgumentTypes(handler.desc)[parameter].getDescriptor().equals(descriptor),
            "@Local descriptor does not match target local");
    }

    static void contracts(ClassNode mc, ClassNode celeste, ClassNode sodium, Tiny mappings) {
        MethodNode target = method(mc, "applyFog", WORLD);
        MethodNode early = method(celeste, "celeste$sodiumAtmosphere", null);
        MethodNode late = method(celeste, "celeste$customAtmosphere", null);
        AnnotationNode earlyAnnotation = injection(early, "Inject"), lateAnnotation = injection(late, "ModifyArgs");
        require(List.of("applyFog" + WORLD).equals(value(earlyAnnotation, "method")), "Early target descriptor changed");
        require(List.of("applyFog" + WORLD).equals(value(lateAnnotation, "method")), "Vanilla target descriptor changed");
        AnnotationNode earlyAt = at(earlyAnnotation), lateAt = at(lateAnnotation);
        before(earlyAt); before(lateAt);
        require("Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;".equals(value(earlyAt, "target")),
            "Sodium path is no longer before GPU upload argument evaluation");
        require(Boolean.FALSE.equals(value(earlyAt, "remap")), "Blaze3D getDevice injection must remain unmapped");
        require(("L" + FOG + ";applyFog" + WRITER).equals(value(lateAt, "target")), "Vanilla writer hook changed");
        int earlyIndex = callIndex(target, "com/mojang/blaze3d/systems/RenderSystem", "getDevice", "()Lcom/mojang/blaze3d/systems/GpuDevice;");
        int writeIndex = callIndex(target, FOG, "applyFog", WRITER);
        require(earlyIndex < writeIndex, "Shared-data hook is too late");
        int lastWrite = -1;
        List<String> loadedFields = new ArrayList<>();
        for (int i = 0; i < target.instructions.size(); i++) {
            if (target.instructions.get(i) instanceof FieldInsnNode f && f.owner.equals(DATA)) {
                if (f.getOpcode() == PUTFIELD) lastWrite = i;
                if (f.getOpcode() == GETFIELD && i < writeIndex) {
                    require(i > earlyIndex, "Fog field read precedes shared hook: " + f.name);
                    loadedFields.add(f.name);
                }
            }
        }
        require(lastWrite < earlyIndex, "Vanilla overwrites FogData after shared adjustment");
        require(loadedFields.equals(List.of("environmentalStart", "environmentalEnd", "renderDistanceStart", "renderDistanceEnd", "skyEnd", "cloudEnd")),
            "Native writer field argument order changed");
        nativeFields = loadedFields.toArray(String[]::new);
        local(target, earlyIndex, "L" + DATA + ";", 11, early, 6);
        local(target, earlyIndex, "Lorg/joml/Vector4f;", 7, early, 7);
        require(mc.fields.stream().anyMatch(f -> f.name.equals("fogEnabled") && f.desc.equals("Z") && (f.access & ACC_STATIC) != 0),
            "Debug fog shadow no longer matches Minecraft");
        MethodNode capture = method(sodium, "sodium$storeFogParameters", null);
        AnnotationNode sodiumInjection = injection(capture, "Inject"), sodiumAt = at(sodiumInjection);
        before(sodiumAt);
        String sodiumOwner = mappings.inverseClass(FOG);
        String sodiumWriter = mappings.inverseMethod(sodiumOwner, "applyFog", WRITER);
        String sodiumWorld = mappings.inverseMethod(sodiumOwner, "applyFog", WORLD);
        require(List.of(sodiumWorld).equals(value(sodiumInjection, "method")), "Installed Sodium capture method changed");
        require(("L" + sodiumOwner + ";" + sodiumWriter + WRITER).equals(value(sodiumAt, "target")), "Installed Sodium capture point changed");
        require(Type.getArgumentTypes(capture.desc).length == 8, "Installed Sodium local capture descriptor changed");
        require(celeste.methods.stream().filter(m -> injectionKind(m) != null).count() == 2,
            "Unexpected extra world/GUI fog hooks require explicit coverage");
        String targetInternal = ((Type) ((List<?>) value(annotation(celeste.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;"), "value")).getFirst()).getInternalName();
        require(targetInternal.equals(FOG), "CustomFogMixin target changed");
        System.out.println("PASS Fog compatibility contracts: exact 1.21.11 finalized FogData -> early hook -> six argument reads -> Sodium 0.8.13 snapshot/native writer; unique typed locals 11/7.");
    }
    static String injectionKind(MethodNode method) {
        for (String kind : List.of("Inject", "ModifyArgs")) {
            String desc = "Lorg/spongepowered/asm/mixin/injection/" + kind + ";";
            if (annotation(method.visibleAnnotations, desc) != null || annotation(method.invisibleAnnotations, desc) != null) return kind;
        }
        return null;
    }

    /** Reads the exact cached Yarn Tiny v2 mappings, not hand-maintained intermediary guesses. */
    static final class Tiny {
        final Map<String, String> classes = new HashMap<>(), fields = new HashMap<>(), methods = new HashMap<>();
        Tiny(Path jar) throws Exception {
            String owner = null;
            for (String line : new String(entry(jar, "mappings/mappings.tiny"), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                String[] part = line.split("\t", -1);
                if (part[0].equals("c")) { owner = part[1]; classes.put(owner, part[2]); }
                else if (part.length >= 5 && part[0].isEmpty() && part[1].equals("f")) fields.put(owner + "." + part[3], part[4]);
                else if (part.length >= 5 && part[0].isEmpty() && part[1].equals("m")) methods.put(owner + "." + part[3] + part[2], part[4]);
            }
            require(!classes.isEmpty(), "Missing exact Yarn mappings");
        }
        String inverseClass(String named) { return classes.entrySet().stream().filter(e -> e.getValue().equals(named)).findFirst().orElseThrow().getKey(); }
        String inverseMethod(String owner, String named, String descriptor) {
            Remapper remapper = new Remapper() { public String map(String n) { return classes.getOrDefault(n, n); } };
            return methods.entrySet().stream().filter(e -> e.getKey().startsWith(owner + ".") && e.getValue().equals(named)
                && remapper.mapMethodDesc(e.getKey().substring(e.getKey().indexOf('('))).equals(descriptor))
                .map(e -> e.getKey().substring(owner.length() + 1, e.getKey().indexOf('('))).findFirst().orElseThrow();
        }
    }

    static final class FixtureLoader extends ClassLoader {
        final Map<String, byte[]> definitions;
        final boolean includeSodium;
        int forbiddenAttempts;
        FixtureLoader(Map<String, byte[]> definitions, boolean includeSodium) { super(FogCompatibilityCheck.class.getClassLoader()); this.definitions = definitions; this.includeSodium = includeSodium; }
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!includeSodium && (name.startsWith("net.caffeinemc.") || name.contains("SodiumCapture"))) {
                forbiddenAttempts++; throw new ClassNotFoundException("Sodium intentionally absent: " + name);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && definitions.containsKey(name)) { byte[] bytes = definitions.get(name); found = defineClass(name, bytes, 0, bytes.length); }
                if (found == null) found = super.loadClass(name, false);
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }
    static byte[] remap(ClassNode original, Tiny tiny, Map<String, String> replacements, boolean sodium) {
        ClassNode copy = new ClassNode();
        original.accept(new ClassRemapper(copy, new Remapper() {
            public String map(String internal) {
                String named = sodium ? tiny.classes.getOrDefault(internal, internal) : internal;
                return replacements.getOrDefault(named, named);
            }
            public String mapFieldName(String owner, String name, String descriptor) {
                return sodium ? tiny.fields.getOrDefault(owner + "." + name, name) : name;
            }
        }));
        copy.access = (copy.access | ACC_PUBLIC) & ~ACC_ABSTRACT;
        copy.interfaces.clear(); copy.visibleAnnotations = null; copy.invisibleAnnotations = null;
        for (MethodNode m : copy.methods) {
            m.visibleAnnotations = null; m.invisibleAnnotations = null;
            m.visibleParameterAnnotations = null; m.invisibleParameterAnnotations = null;
        }
        ClassWriter writer = new ClassWriter(0);
        copy.accept(writer);
        return writer.toByteArray();
    }
    static ClassNode extracted(String name, List<MethodNode> methods, List<FieldNode> fields) {
        ClassNode target = new ClassNode(); target.version = V21; target.access = ACC_PUBLIC; target.name = name; target.superName = "java/lang/Object";
        target.methods.addAll(methods); target.fields.addAll(fields);
        return target;
    }
    static MethodNode constructor() {
        MethodNode m = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        m.instructions.add(new InsnNode(RETURN)); m.maxLocals = 1; m.maxStack = 1;
        return m;
    }
    static Map<String, byte[]> definitions(Path compiled, Path mcJar, Path sodiumJar, Tiny tiny, ClassNode mc, ClassNode celeste, ClassNode sodium) throws Exception {
        Map<String, String> replacements = new HashMap<>();
        replacements.put(MIXIN, "FixtureCelesteFogMixin");
        replacements.put(BASE + "CustomFogCompatibility", "FixtureFogCompatibility");
        replacements.put(BASE + "CustomFogRenderer", "FogCompatibilityCheck$AdjustmentProbe");
        replacements.put("net/fabricmc/loader/api/FabricLoader", "FogCompatibilityCheck$LoaderProbe");
        replacements.put("net/minecraft/client/render/Camera", "java/lang/Object");
        replacements.put("net/minecraft/client/world/ClientWorld", "java/lang/Object");
        replacements.put("net/minecraft/client/render/RenderTickCounter", "java/lang/Object");
        replacements.put("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", "java/lang/Object");
        replacements.put("org/spongepowered/asm/mixin/injection/invoke/arg/Args", "FogCompatibilityCheck$TestArgs");
        replacements.put(SODIUM, "FixtureSodiumCapture");
        replacements.put(FOG, "FixtureNativeFogWriter");
        replacements.put("com/mojang/blaze3d/buffers/Std140Builder", "FixtureStd140Builder");
        replacements.put("net/minecraft/util/math/MathHelper", "FixtureAlignMath");
        Map<String, byte[]> result = new HashMap<>();
        result.put("FixtureCelesteFogMixin", remap(celeste, tiny, replacements, false));
        ClassNode compatibility = node(Files.readAllBytes(compiled.resolve(BASE + "CustomFogCompatibility.class")));
        result.put("FixtureFogCompatibility", remap(compatibility, tiny, replacements, false));
        result.put("FixtureSodiumCapture", remap(sodium, tiny, replacements, true));
        result.put(PARAMETERS.replace('/', '.'), entry(sodiumJar, PARAMETERS + ".class"));
        MethodNode nativeWriter = method(mc, "applyFog", WRITER);
        nativeWriter.access = ACC_PUBLIC;
        ClassNode writerClass = extracted(FOG, List.of(constructor(), nativeWriter), List.of());
        result.put("FixtureNativeFogWriter", remap(writerClass, tiny, replacements, false));
        ClassNode std140 = node(entry(mcJar, "com/mojang/blaze3d/buffers/Std140Builder.class"));
        // Keep the exact called writer path, removing unused MemoryStack overloads only.
        std140.methods.removeIf(m -> !(m.name.equals("<init>") || m.name.equals("intoBuffer") || m.name.equals("align")
            || m.name.equals("putFloat") || m.name.equals("putVec4") && m.desc.startsWith("(Lorg/joml/Vector4fc;")));
        result.put("FixtureStd140Builder", remap(std140, tiny, replacements, false));
        ClassNode math = node(entry(mcJar, "net/minecraft/util/math/MathHelper.class"));
        MethodNode align = method(math, "roundUpToMultiple", "(II)I");
        // This exact method's integer helper is also copied if its implementation delegates.
        List<MethodNode> mathMethods = new ArrayList<>(List.of(align));
        for (AbstractInsnNode insn : align.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(math.name))
            mathMethods.add(method(math, call.name, call.desc));
        result.put("FixtureAlignMath", remap(extracted(math.name, mathMethods, List.of()), tiny, replacements, false));
        return result;
    }

    static Object invoke(Object target, String name, Object... arguments) throws Exception {
        Method selected = Arrays.stream(target.getClass().getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        selected.setAccessible(true);
        try { return selected.invoke(target, arguments); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception x) throw x; throw e; }
    }
    static FogData data(Fog source) {
        FogData data = new FogData(); data.environmentalStart = (float) source.environmentalStart(); data.environmentalEnd = (float) source.environmentalEnd();
        data.renderDistanceStart = (float) source.renderDistanceStart(); data.renderDistanceEnd = (float) source.renderDistanceEnd();
        data.skyEnd = (float) source.skyEnd(); data.cloudEnd = (float) source.cloudEnd(); return data;
    }
    static Vector4f color(Fog source) { return new Vector4f((float) source.color().r(), (float) source.color().g(), (float) source.color().b(), (float) source.color().a()); }
    static Object[] writerArguments(FogData data, Vector4f color) throws Exception {
        Object[] args = new Object[9]; args[0] = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder()); args[1] = 0; args[2] = color;
        for (int i = 0; i < nativeFields.length; i++) args[3 + i] = FogData.class.getField(nativeFields[i]).getFloat(data);
        return args;
    }
    static float[] values(Fog fog) {
        return new float[]{(float) fog.color().r(), (float) fog.color().g(), (float) fog.color().b(), (float) fog.color().a(),
            (float) fog.environmentalStart(), (float) fog.environmentalEnd(), (float) fog.renderDistanceStart(),
            (float) fog.renderDistanceEnd(), (float) fog.skyEnd(), (float) fog.cloudEnd()};
    }
    static Fog canonical(Fog fog) {
        float[] v = values(fog);
        return new Fog(new Color(v[0], v[1], v[2], v[3]), v[4], v[5], v[6], v[7], v[8], v[9]);
    }
    static float parameter(Object parameters, String name) throws Exception { return (Float) parameters.getClass().getMethod(name).invoke(parameters); }

    static final class RuntimeFixture {
        final boolean sodium;
        final FixtureLoader loader;
        final Object celeste, nativeWriter, capture;
        final Class<?> compatibility;
        RuntimeFixture(Map<String, byte[]> definitions, boolean sodium) throws Exception {
            this.sodium = sodium; sodiumPresent = sodium; loaderCalls = 0;
            loader = new FixtureLoader(definitions, sodium);
            compatibility = Class.forName("FixtureFogCompatibility", true, loader);
            require((Boolean) compatibility.getMethod("usesSodium").invoke(null) == sodium, "Presence detection chose wrong renderer");
            require(loaderCalls == 1, "Presence detection was not a single cached lookup");
            sodiumPresent = !sodium;
            require((Boolean) compatibility.getMethod("usesSodium").invoke(null) == sodium && loaderCalls == 1, "Presence detection changes or repeats per frame");
            sodiumPresent = sodium;
            celeste = loader.loadClass("FixtureCelesteFogMixin").getConstructor().newInstance();
            nativeWriter = loader.loadClass("FixtureNativeFogWriter").getConstructor().newInstance();
            capture = sodium ? loader.loadClass("FixtureSodiumCapture").getConstructor().newInstance() : null;
        }
        void run(Fog input, Settings settings, Conditions conditions, boolean debugFogEnabled, boolean captureBeforeLate) throws Exception {
            scenarios++;
            Fog original = canonical(input), expected = debugFogEnabled ? CustomFogMath.apply(original, settings, conditions) : original;
            FogData data = data(original); Vector4f color = color(original);
            AdjustmentProbe.settings = settings; AdjustmentProbe.conditions = conditions; AdjustmentProbe.calls = 0;
            Field debug = celeste.getClass().getDeclaredField("fogEnabled"); debug.setAccessible(true); debug.setBoolean(null, debugFogEnabled);
            invoke(celeste, "celeste$sodiumAtmosphere", null, 8, null, 0f, null, null, data, color);
            Object[] arguments = writerArguments(data, color); // real call-site floats are evaluated BEFORE the late hook
            TestArgs args = new TestArgs(arguments);
            if (sodium && captureBeforeLate) invoke(capture, "sodium$storeFogParameters", null, 8, null, 0f, null, null, data, color);
            invoke(celeste, "celeste$customAtmosphere", args, null, 8, null, 0f, null);
            if (sodium && !captureBeforeLate) invoke(capture, "sodium$storeFogParameters", null, 8, null, 0f, null, null, data, color);
            invoke(nativeWriter, "applyFog", arguments);
            float[] expectedValues = values(expected);
            ByteBuffer uploaded = (ByteBuffer) arguments[0];
            require(uploaded.position() == 40, "Native UBO byte size changed");
            for (int i = 0; i < expectedValues.length; i++) exact(uploaded.getFloat(i * 4), expectedValues[i], "Native UBO channel " + i + " sodium=" + sodium);
            require(AdjustmentProbe.calls == (debugFogEnabled ? 1 : 0), "Adjustment ran twice or selected no branch");
            exact(color.w, (float) original.color().a(), "Returned sky/clear alpha changed");
            exact(color.x, expectedValues[0], "Returned sky color not aligned with terrain");
            exact(color.y, expectedValues[1], "Returned sky green not aligned with terrain");
            exact(color.z, expectedValues[2], "Returned sky blue not aligned with terrain");
            exact(data.renderDistanceStart, (float) original.renderDistanceStart(), "Shared render-distance start changed");
            exact(data.renderDistanceEnd, (float) original.renderDistanceEnd(), "Shared render-distance end changed");
            exact(data.skyEnd, (float) original.skyEnd(), "Shared sky range changed");
            exact(data.cloudEnd, (float) original.cloudEnd(), "Shared cloud range changed");
            if (sodium) {
                Object parameters = invoke(capture, "sodium$getFogParameters");
                String[] channels = {"red", "green", "blue", "alpha", "environmentalStart", "environmentalEnd", "renderStart", "renderEnd"};
                for (int i = 0; i < channels.length; i++) exact(parameter(parameters, channels[i]), uploaded.getFloat(i * 4), "Sodium/native mismatch: " + channels[i]);
                exact(parameter(parameters, "cullDistance"), Math.min(expectedValues[5], expectedValues[7]), "Sodium fog culling used stale distance");
                exact(data.environmentalStart, expectedValues[4], "Sodium shared start unchanged");
                exact(data.environmentalEnd, expectedValues[5], "Sodium shared end unchanged");
            } else {
                exact(data.environmentalStart, (float) original.environmentalStart(), "Vanilla path unexpectedly mutates shared start");
                exact(data.environmentalEnd, (float) original.environmentalEnd(), "Vanilla path unexpectedly mutates shared end");
                require(loader.forbiddenAttempts == 0, "Vanilla path attempted loading a Sodium class");
            }
            require(loaderCalls == 1, "Frame processing repeated mod detection");
        }
    }

    static void negativeControl(Map<String, byte[]> definitions, Fog original) throws Exception {
        // Execute the actual late handler with vanilla detection, then Sodium's actual copied fields.
        // This reproduces the prior implementation: updated UBO but unchanged shared FogData.
        RuntimeFixture lateOnly = new RuntimeFixture(definitions, false);
        FixtureLoader sodiumLoader = new FixtureLoader(definitions, true);
        Object capture = sodiumLoader.loadClass("FixtureSodiumCapture").getConstructor().newInstance();
        for (boolean before : List.of(false, true)) {
            FogData data = data(original); Vector4f color = color(original); Object[] args = writerArguments(data, color);
            Field enabled = lateOnly.celeste.getClass().getDeclaredField("fogEnabled"); enabled.setAccessible(true); enabled.setBoolean(null, true);
            AdjustmentProbe.settings = new Settings("Mist", .9, 48); AdjustmentProbe.conditions = AIR;
            if (before) invoke(capture, "sodium$storeFogParameters", null, 8, null, 0f, null, null, data, color);
            invoke(lateOnly.celeste, "celeste$customAtmosphere", new TestArgs(args), null, 8, null, 0f, null);
            if (!before) invoke(capture, "sodium$storeFogParameters", null, 8, null, 0f, null, null, data, color);
            invoke(lateOnly.nativeWriter, "applyFog", args);
            Object copied = invoke(capture, "sodium$getFogParameters");
            require(parameter(copied, "environmentalEnd") != ((ByteBuffer) args[0]).getFloat(20),
                "Negative control stopped detecting the original late-writer Sodium bug");
            expectedOldFailures++;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: FogCompatibilityCheck compiledClasses minecraftJar sodiumJar yarnJar");
        Path compiled = Path.of(args[0]), mcJar = Path.of(args[1]), sodiumJar = Path.of(args[2]), yarnJar = Path.of(args[3]);
        String sodiumMetadata = new String(entry(sodiumJar, "fabric.mod.json"), java.nio.charset.StandardCharsets.UTF_8);
        require(sodiumMetadata.contains("0.8.13") && sodiumMetadata.contains("1.21.11"), "This contract targets installed Sodium 0.8.13 for Minecraft 1.21.11");
        Tiny tiny = new Tiny(yarnJar);
        ClassNode mc = node(entry(mcJar, FOG + ".class"));
        ClassNode celeste = node(Files.readAllBytes(compiled.resolve(MIXIN + ".class")));
        ClassNode sodium = node(entry(sodiumJar, SODIUM + ".class"));
        contracts(mc, celeste, sodium, tiny);
        Map<String, byte[]> definitions = definitions(compiled, mcJar, sodiumJar, tiny, mc, celeste, sodium);
        Fog original = canonical(new Fog(new Color(.42, .57, .66, .73), 0, 1024, 230.4, 256, 512, 2048));
        for (boolean present : List.of(false, true)) {
            RuntimeFixture fixture = new RuntimeFixture(definitions, present);
            for (boolean order : List.of(false, true)) {
                for (String style : List.of("Mist", "Dusk", "Frost")) for (double density : new double[]{0, .01, .45, .9, 1})
                    for (double distance : new double[]{32, 48, 160, 512}) fixture.run(original, new Settings(style, density, distance), AIR, true, order);
                for (int flags = 0; flags < 64; flags++) for (Medium medium : Medium.values()) {
                    Conditions c = new Conditions((flags & 1) != 0, (flags & 2) != 0, medium,
                        (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0);
                    fixture.run(original, new Settings("Dusk", .9, 48), c, true, order);
                }
                fixture.run(original, null, AIR, true, order);
                fixture.run(original, new Settings("Mist", .9, 48), AIR, false, order);
                // Consecutive frames prove disable/reset and re-enable leave no cached custom fog.
                fixture.run(original, new Settings("Frost", .9, 48), AIR, true, order);
                fixture.run(original, null, AIR, true, order);
                fixture.run(original, new Settings("Frost", 0, 48), AIR, true, order);
                fixture.run(original, new Settings("Frost", .9, 48), AIR, true, order);
                fixture.run(new Fog(original.color(), -8, 64, 96, 128, 192, 224), new Settings("Mist", 1, 48), AIR, true, order);
            }
            System.out.println("PASS Fog renderer branch: Sodium " + (present ? "present (real copied parameters and cull distance)" : "absent (zero Sodium class-load attempts)") + "; cached auto-selection, both hook orders, enable/reset/density-zero/hazard/debug safety.");
        }
        negativeControl(definitions, original);
        System.out.println("PASS Fog compatibility: " + checks + " checks; " + scenarios + " executed frame cases; " + expectedOldFailures
            + " expected old-path mismatch controls; actual compiled handler/native UBO/Sodium capture bytecode. In-game runtime remains separate.");
    }
}
