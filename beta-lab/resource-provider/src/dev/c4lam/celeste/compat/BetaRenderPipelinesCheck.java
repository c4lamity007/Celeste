package dev.c4lam.celeste.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Real mapped registry ownership checks; no GPU, window, shaders or game session created. */
public final class BetaRenderPipelinesCheck {
    private static int checks;
    private BetaRenderPipelinesCheck() { }
    public static void run(String intermediaryJar) throws Exception {
        verifyIntermediary(Path.of(intermediaryJar));
        var field = RenderPipelines.class.getDeclaredField("PIPELINES");
        check(field.getType() == Map.class && Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()), "exact mapped registry shape");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Identifier, RenderPipeline> registry = (Map<Identifier, RenderPipeline>) field.get(null);
        Map<Identifier, RenderPipeline> baseline = new HashMap<>(registry);
        List<Identifier> fixtureIds = new ArrayList<>();
        BetaRenderPipelines.Lease lease = null;
        try {
            RenderPipeline startup = pipeline("celeste", "startup", fixtureIds);
            check(BetaRenderPipelines.register(startup) == startup && registry.get(startup.getLocation()) == startup, "startup mode delegates original registration");
            RenderPipeline previous = pipeline("celeste", "previous", fixtureIds);
            registry.put(previous.getLocation(), previous);
            lease = BetaRenderPipelines.open();
            expect(BetaRenderPipelines::open, "overlapping leases rejected");
            RenderPipeline owned = pipeline("celeste", "owned", fixtureIds);
            check(BetaRenderPipelines.register(owned) == owned, "lease registration returns original pipeline");
            BetaRenderPipelines.register(owned);
            RenderPipeline latest = pipeline("celeste", "owned", fixtureIds);
            BetaRenderPipelines.register(latest);
            check(registry.get(owned.getLocation()) == latest, "duplicate location keeps latest owned registration");
            RenderPipeline replacingPrevious = pipeline("celeste", "previous", fixtureIds);
            BetaRenderPipelines.register(replacingPrevious);
            RenderPipeline lazy = pipeline("celeste", "lazy", fixtureIds);
            BetaRenderPipelines.register(lazy);
            RenderPipeline foreign = pipeline("fixture_other_mod", "foreign", fixtureIds);
            BetaRenderPipelines.register(foreign);
            RenderPipeline untracked = pipeline("celeste", "untracked", fixtureIds);
            registry.put(untracked.getLocation(), untracked);
            RenderPipeline replaced = pipeline("celeste", "replaced", fixtureIds);
            BetaRenderPipelines.register(replaced);
            RenderPipeline external = pipeline("celeste", "replaced", fixtureIds);
            registry.put(external.getLocation(), external);
            RenderPipeline interrupted = pipeline("celeste", "interrupted", fixtureIds);
            BetaRenderPipelines.register(interrupted);
            RenderPipeline intervening = pipeline("celeste", "interrupted", fixtureIds);
            registry.put(intervening.getLocation(), intervening);
            BetaRenderPipelines.register(pipeline("celeste", "interrupted", fixtureIds));
            List<RenderPipeline> beforeReload = RenderPipelines.getAll();
            check(beforeReload.contains(latest) && beforeReload.contains(lazy), "normal registry snapshot contains eager and lazy owned entries");
            // Models retirement after a partial startup as well as normal stop:
            // cleanup cannot depend on a resource host or runtime being created.
            lease.close();
            check(!registry.containsKey(owned.getLocation()) && !registry.containsKey(lazy.getLocation()), "owned registrations removed before next reload snapshot");
            check(registry.get(previous.getLocation()) == previous, "exact pre-existing registration restored");
            check(registry.get(startup.getLocation()) == startup, "pre-lease startup entry preserved");
            check(registry.get(foreign.getLocation()) == foreign && registry.get(untracked.getLocation()) == untracked, "other namespaces and untracked Celeste entries preserved");
            check(registry.get(external.getLocation()) == external, "later external replacement preserved by identity");
            check(registry.get(intervening.getLocation()) == intervening, "external predecessor restored after subsequent owned replacement");
            check(baseline.entrySet().stream().allMatch(entry -> registry.get(entry.getKey()) == entry.getValue()), "all original vanilla and other registry entries unchanged");
            check(!RenderPipelines.getAll().contains(latest) && !RenderPipelines.getAll().contains(lazy), "fresh shader reload snapshot excludes retired entries");
            Map<Identifier, RenderPipeline> retiredSnapshot = new HashMap<>(registry);
            lease.close();
            check(sameIdentities(registry, retiredSnapshot), "repeated close leaves registry unchanged");
            expect(() -> BetaRenderPipelines.register(lazy), "retired payload rejects late lazy registration");
            expect(BetaRenderPipelines::open, "retired payload cannot reopen");
            check(sameIdentities(registry, retiredSnapshot), "rejected late operations leave registry unchanged");
        } finally {
            if (lease != null) lease.close();
            // Only synthetic IDs introduced by this isolated test are removed.
            for (Identifier id : fixtureIds) registry.remove(id);
        }
        check(sameIdentities(registry, baseline), "fixture cleanup restores exact original registry identities");
        System.out.println("PASS beta render pipeline lease: " + checks + " checks; actual mapped registry and exact intermediary schema");
    }
    private static boolean sameIdentities(Map<Identifier, RenderPipeline> first, Map<Identifier, RenderPipeline> second) {
        return first.size() == second.size() && second.entrySet().stream().allMatch(entry -> first.get(entry.getKey()) == entry.getValue());
    }
    private static RenderPipeline pipeline(String namespace, String label, List<Identifier> fixtureIds) {
        Identifier id = Identifier.of(namespace, "pipeline/qa_owner_lease_" + label);
        fixtureIds.add(id);
        return RenderPipeline.builder().withLocation(id).withVertexShader("core/position_color").withFragmentShader("core/position_color")
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS).build();
    }
    private static void verifyIntermediary(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry("net/minecraft/class_10799.class"))) {
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            check(node.fields.stream().filter(field -> field.name.equals("field_56845") && field.desc.equals("Ljava/util/Map;")
                && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)).count() == 1,
                "exact intermediary private registry field");
            check(node.methods.stream().filter(method -> method.name.equals("method_67887")
                && method.desc.equals("(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lcom/mojang/blaze3d/pipeline/RenderPipeline;")
                && (method.access & Opcodes.ACC_STATIC) != 0).count() == 1, "exact intermediary registration method");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void expect(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(message);
    }
}