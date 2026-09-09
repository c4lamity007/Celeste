package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.mixin.module.KeyBindingAccessor;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** No game/window/packet creation; real key bindings plus private-member fixtures. */
public final class MinecraftMembersCheck {
    private static int checks;
    private MinecraftMembersCheck() { }

    public static void run(String intermediaryJar) throws Throwable {
        MinecraftAccess.verifyLateMembers();
        check(true, "all ten handles resolve against actual named Minecraft classes");
        KeyBinding binding = new KeyBinding("key.celeste.beta.fixture", 298, KeyBinding.Category.MISC);
        check(MinecraftAccess.boundKey(binding).getCode() == 298, "late getter reads actual bound key");
        InputUtil.Key rebound = InputUtil.Type.KEYSYM.createFromCode(299);
        binding.setBoundKey(rebound);
        KeyBinding.updateKeysByCode();
        check(MinecraftAccess.boundKey(binding) == rebound, "rebind updates the live member read");
        KeyBinding.onKeyPressed(rebound);
        KeyBinding.onKeyPressed(rebound);
        check(MinecraftAccess.queuedPresses(binding) == 2 && MinecraftAccess.queuedPresses(binding) == 2,
            "read-only input arbitration never consumes queued presses");
        check(binding.wasPressed() && MinecraftAccess.queuedPresses(binding) == 1, "normal vanilla consumption still works");
        binding.wasPressed();

        StartupBinding startup = new StartupBinding();
        check(MinecraftAccess.queuedPresses(startup) == 17, "existing startup accessor keeps priority");
        check(MinecraftAccess.boundKey(startup) == startup.answer, "existing startup bound-key accessor remains active");
        check(startup.calls == 2, "startup path calls each accessor once");
        memberResolution();
        verifyIntermediary(Path.of(intermediaryJar));
        System.out.println("PASS late Minecraft member access: " + checks + " checks; actual key bindings and both mapping namespaces");
    }

    private static void memberResolution() throws Throwable {
        Named named = new Named();
        MethodHandle read = NamedOrIntermediaryMembers.getter(Named.class, int.class, "value", "field_1");
        check((int) read.invokeExact(named) == 4, "named private field getter");
        MethodHandle increment = NamedOrIntermediaryMembers.method(Named.class, void.class, "increment", "method_1");
        increment.invokeExact(named);
        check((int) read.invokeExact(named) == 5, "private original method executes exactly once");
        Intermediary intermediary = new Intermediary();
        MethodHandle alternate = NamedOrIntermediaryMembers.getter(Intermediary.class, int.class, "value", "field_1");
        check((int) alternate.invokeExact(intermediary) == 8, "intermediary private field getter");
        MethodHandle alternateMethod = NamedOrIntermediaryMembers.method(Intermediary.class, boolean.class, "test", "method_1", int.class);
        check((boolean) alternateMethod.invokeExact(intermediary, 8), "intermediary overload resolves by exact parameter types");
        expect(() -> NamedOrIntermediaryMembers.getter(Named.class, long.class, "value", "field_1"), "wrong field descriptor rejects");
        expect(() -> NamedOrIntermediaryMembers.getter(Named.class, int.class, "absent", "field_2"), "missing member rejects");
        expect(() -> NamedOrIntermediaryMembers.getter(Ambiguous.class, int.class, "value", "field_1"), "ambiguous alias rejects");
        expect(() -> NamedOrIntermediaryMembers.getter(StaticMember.class, int.class, "value", "field_1"), "static field cannot replace instance field");
        expect(() -> NamedOrIntermediaryMembers.method(Named.class, boolean.class, "increment", "method_1"), "wrong return descriptor rejects");
        expect(() -> NamedOrIntermediaryMembers.method(StaticMember.class, void.class, "increment", "method_1"), "static method cannot replace instance method");
        RuntimeException original = new RuntimeException("fixture");
        check(NamedOrIntermediaryMembers.propagate(original) == original, "runtime exception identity preserved");
        AssertionError error = new AssertionError("fixture error");
        try { NamedOrIntermediaryMembers.propagate(error); throw new AssertionError("Expected original error"); }
        catch (AssertionError caught) { check(caught == error, "original errors are not hidden"); }
    }

    private static void verifyIntermediary(Path jar) throws Exception {
        String[][] sites = {
            {"class_304", "field_1655", "Lnet/minecraft/class_3675$class_306;"},
            {"class_304", "field_1661", "I"},
            {"class_636", "field_3721", "I"},
            {"class_636", "method_2911", "()V"},
            {"class_310", "field_1771", "I"},
            {"class_310", "method_1536", "()Z"},
            {"class_310", "method_1583", "()V"},
            {"class_1309", "field_6273", "I"},
            {"class_465", "field_2787", "Lnet/minecraft/class_1735;"},
            {"class_329", "method_1759", "(Lnet/minecraft/class_332;Lnet/minecraft/class_9779;)V"}
        };
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String[] site : sites) {
                ClassNode node = new ClassNode();
                try (var input = zip.getInputStream(zip.getEntry("net/minecraft/" + site[0] + ".class"))) {
                    new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                long matches = site[1].startsWith("field_")
                    ? node.fields.stream().filter(field -> field.name.equals(site[1]) && field.desc.equals(site[2]) && (field.access & Opcodes.ACC_STATIC) == 0).count()
                    : node.methods.stream().filter(method -> method.name.equals(site[1]) && method.desc.equals(site[2]) && (method.access & Opcodes.ACC_STATIC) == 0).count();
                check(matches == 1, "exact actual intermediary member " + site[0] + "." + site[1]);
            }
        }
    }

    private static final class StartupBinding extends KeyBinding implements KeyBindingAccessor {
        private final InputUtil.Key answer = InputUtil.Type.KEYSYM.createFromCode(300);
        private int calls;
        private StartupBinding() { super("key.celeste.beta.startup_fixture", 301, Category.MISC); }
        public InputUtil.Key celeste$getBoundKey() { calls++; return answer; }
        public int celeste$getTimesPressed() { calls++; return 17; }
    }
    private static final class Named {
        private int value = 4;
        private void increment() { value++; }
    }
    private static final class Intermediary {
        private int field_1 = 8;
        private boolean method_1(int expected) { return field_1 == expected; }
    }
    private static final class Ambiguous { private int value; private int field_1; }
    private static final class StaticMember { private static int value; private static void increment() { value++; } }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void expect(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
}
