package dev.c4lam.celeste.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.util.Identifier;

/** Exact game texture types, with a controlled GPU-destruction boundary. */
public final class BetaTextureLeaseCheck {
    private static int checks;
    private BetaTextureLeaseCheck() { }
    public static void run() throws Exception {
        Identifier oldId = id("old.png"), freshId = id("new.png"), externalId = id("custom.png");
        Identifier unrelatedId = id("not-in-pack.png"), mismatchId = id("different-source.png");
        Map<Identifier, AbstractTexture> registry = new HashMap<>();
        ResourceTexture old = new ResourceTexture(oldId);
        registry.put(oldId, old);
        int[] destroyed = {0};
        BetaTextureLease lease = new BetaTextureLease(registry, Set.of(oldId, freshId, externalId, mismatchId), (id, expected) -> {
            check(registry.get(id) == expected, "destruction retains exact current identity");
            registry.remove(id); destroyed[0]++;
        });
        registry.put(freshId, new ResourceTexture(freshId));
        ResourceTexture external = new CustomTexture(externalId);
        registry.put(externalId, external);
        ResourceTexture unrelated = new ResourceTexture(unrelatedId);
        registry.put(unrelatedId, unrelated);
        ResourceTexture mismatch = new ResourceTexture(unrelatedId);
        registry.put(mismatchId, mismatch);
        lease.close();
        check(!registry.containsKey(freshId) && destroyed[0] == 1, "new vanilla view for verified asset is retired");
        check(registry.get(oldId) == old, "preexisting texture remains intact");
        check(registry.get(externalId) == external, "custom texture replacement remains intact");
        check(registry.get(unrelatedId) == unrelated && registry.get(mismatchId) == mismatch, "unrelated or different-source texture remains intact");
        lease.close();
        check(destroyed[0] == 1, "repeat texture retirement performs no GPU destruction");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> { try { lease.close(); } catch (Throwable error) { failure.set(error); } });
        other.start(); other.join();
        check(failure.get() instanceof IllegalStateException, "wrong-thread retirement rejected");
        System.out.println("PASS beta texture lease: " + checks + " checks; actual texture types, controlled GPU disposal");
    }
    private static Identifier id(String file) { return Identifier.of("celeste", "textures/lease-test/" + file); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static final class CustomTexture extends ResourceTexture { private CustomTexture(Identifier id) { super(id); } }
}
