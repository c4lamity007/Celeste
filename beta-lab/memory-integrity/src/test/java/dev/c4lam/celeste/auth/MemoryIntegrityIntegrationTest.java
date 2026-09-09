package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoaderTest;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Generated fixtures connect the real loader to production ArtifactIntegrity/Crypto. */
public final class MemoryIntegrityIntegrationTest {
    private static final String DOMAIN = "Celeste-memory-integrity-fixture-v1";
    private static final String EXCLUDED = "META-INF/.r/4d7a9c2e";
    private static final TwoPartMemoryClassLoader.Namespaces NAMESPACES =
        new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("assets/beta/", "META-INF/"));
    private static int passed;

    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 21 && args.length == 1, "JDK 21 and fixture directory required");
        Fixture f = fixture();
        String reference = reference(f.parts());
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "integration-");
        Path firstPath = directory.resolve("synthetic-first.jar");
        Path secondPath = directory.resolve("synthetic-second.jar");
        // Synthetic disk references only; no real/downloaded payload is written.
        Files.write(firstPath, f.first());
        Files.write(secondPath, f.second());
        String oldProperty = System.getProperty("celeste.payload.parts");
        System.setProperty("celeste.payload.parts", firstPath + java.io.File.pathSeparator + secondPath);
        try {
            run("signed metadata, changed payload rejection, canonical disk/frame equality", () -> {
                check(Crypto.verify(f.key().getPublic(), f.metadata(), f.signature()), "Fixture signature verifies");
                byte[] tampered = f.metadata().clone();
                tampered[tampered.length - 1] ^= 1;
                check(!Crypto.verify(f.key().getPublic(), tampered, f.signature()), "Changed hash metadata fails signature");
                check(!Crypto.verify(Crypto.generateEd25519().getPublic(), f.metadata(), f.signature()), "Wrong trust key fails");
                check(diskReference(List.of(firstPath, secondPath)).equals(reference), "Real disk implementation equals independent framing");
                byte[] damaged = f.first().clone();
                damaged[0] ^= 1;
                expect(IOException.class, () -> openVerified(f, damaged, f.second()));
            });

            run("retained integrity, classes/resources, exact provenance, and provider extraction", () -> {
                byte[] callerFirst = f.first().clone();
                byte[] callerSecond = f.second().clone();
                try (TwoPartMemoryClassLoader loader = openVerified(f, callerFirst, callerSecond)) {
                    ArtifactIntegrity.VerifiedMemorySource source = visitor -> loader.visitRetainedParts(visitor::accept);
                    check(ArtifactIntegrity.digestVerifiedMemory(source).equals(reference), "Real adapter hashes actual retained entries");
                    Class<?> entry = loader.loadClass("demo.payload.First");
                    check(entry.getMethod("value").invoke(null).equals("first+second"), "Cross-part classes work");
                    check(entry.getMethod("asset").invoke(null).equals("asset from part two"), "Cross-part class resource works");
                    Map<String, byte[]> actualIndex = retainedIndex(loader);
                    Map<String, byte[]> provider = new HashMap<>();
                    AtomicInteger count = new AtomicInteger();
                    loader.visitRetainedParts(part -> {
                        int index = count.getAndIncrement();
                        check(Thread.holdsLock(loader), "Visitor holds lifetime monitor");
                        check(part.keySet().equals(f.parts().get(index).keySet()), "Original part order, directories, and metadata");
                        unmodifiable(part);
                        part.forEach((name, bytes) -> {
                            check(bytes == actualIndex.get(name), "Exact loader-owned array identity");
                            check(Arrays.equals(bytes, f.parts().get(index).get(name)), "Exact original content");
                            if (name.startsWith("assets/beta/") && !name.endsWith("/")) provider.put(name, bytes.clone());
                        });
                    });
                    check(count.get() == 2 && provider.size() == 2, "Two complete parts, two selected provider resources");
                    check(new String(provider.get("assets/beta/message.txt"), StandardCharsets.UTF_8).equals("asset from part two"), "Provider input-map extraction");
                    provider.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
                    provider.clear();
                    Arrays.fill(callerFirst, (byte) 0);
                    Arrays.fill(callerSecond, (byte) 0);
                    check(ArtifactIntegrity.digestVerifiedMemory(source).equals(reference), "Provider/caller buffer cleanup cannot alter loader integrity");
                }
            });

            run("checked/unchecked callback errors, nested visits, and reentrant close release guard", () -> {
                TwoPartMemoryClassLoader loader = openVerified(f, f.first(), f.second());
                try {
                    AtomicInteger count = new AtomicInteger();
                    expect(IOException.class, () -> loader.visitRetainedParts(part -> { count.incrementAndGet(); throw new IOException("fixture"); }));
                    check(count.get() == 1, "Callback error stops second visit");
                    expect(IllegalArgumentException.class, () -> loader.visitRetainedParts(part -> { throw new IllegalArgumentException("fixture"); }));
                    expect(AssertionError.class, () -> loader.visitRetainedParts(part -> { throw new AssertionError("fixture"); }));
                    expect(IOException.class, () -> loader.visitRetainedParts(part -> loader.visitRetainedParts(ignored -> {})));
                    expect(IllegalStateException.class, () -> loader.visitRetainedParts(part -> loader.close()));
                    check(!loader.isClosed(), "Reentrant close does not invalidate borrowed bytes");
                    count.set(0);
                    loader.visitRetainedParts(part -> count.incrementAndGet());
                    check(count.get() == 2, "Fresh visits remain usable");
                    check(ArtifactIntegrity.digestVerifiedMemory(visitor -> loader.visitRetainedParts(visitor::accept)).equals(reference), "Integrity still works");
                } finally { loader.close(); }
                check(loader.isClosed(), "Close is not stuck after callback errors");
            });

            run("concurrent close blocks until both callbacks finish", () -> {
                TwoPartMemoryClassLoader loader = openVerified(f, f.first(), f.second());
                CountDownLatch attemptingClose = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                AtomicInteger count = new AtomicInteger();
                Thread closer = Thread.ofPlatform().unstarted(() -> {
                    attemptingClose.countDown();
                    try { loader.close(); } catch (Throwable error) { failure.set(error); }
                });
                try {
                    loader.visitRetainedParts(part -> {
                        if (count.getAndIncrement() == 0) {
                            closer.start();
                            try {
                                check(attemptingClose.await(2, TimeUnit.SECONDS), "Closer starts");
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                                while (closer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
                                check(closer.getState() == Thread.State.BLOCKED, "Close waits on loader monitor");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException(interrupted);
                            }
                        }
                        check(!loader.isClosed() && !part.isEmpty(), "Both callbacks retain live bytes");
                    });
                    closer.join(5000);
                    check(count.get() == 2 && !closer.isAlive() && failure.get() == null && loader.isClosed(), "Close completes after visit");
                    expect(IOException.class, () -> loader.visitRetainedParts(part -> {}));
                } finally { loader.close(); }
            });

            run("all retained maps/arrays clear and closed sources cannot hash", () -> {
                TwoPartMemoryClassLoader loader = openVerified(f, f.first(), f.second());
                List<Map<String, byte[]>> views = new ArrayList<>();
                List<byte[]> arrays = new ArrayList<>();
                // Test-only retention proves ownership; real visitors must not retain borrowed data.
                loader.visitRetainedParts(part -> { views.add(part); arrays.addAll(part.values()); });
                loader.close();
                check(views.stream().allMatch(Map::isEmpty), "Both part backing maps clear");
                for (byte[] bytes : arrays) for (byte value : bytes) check(value == 0, "Borrowed bytes zeroed");
                check(loader.retainedEntryCount() == 0 && loader.retainedByteCount() == 0, "Combined index also clears");
                expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> loader.visitRetainedParts(visitor::accept)));
                expect(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.First"));
            });

            run("root metadata remains unsupported without broadening namespaces", () -> {
                byte[] rootMetadata = TwoPartMemoryClassLoaderTest.jar(Map.of("fabric.mod.json", utf8("{}")));
                expect(IOException.class, () -> TwoPartMemoryClassLoader.open(rootMetadata, Crypto.hex(Crypto.sha256(rootMetadata)),
                    f.second(), Crypto.hex(Crypto.sha256(f.second())), NAMESPACES,
                    TwoPartMemoryClassLoader.Limits.betaDefaults(), MemoryIntegrityIntegrationTest.class.getClassLoader()));
            });

            run("one-shot installed source fails closed without valid-disk fallback", () -> {
                TwoPartMemoryClassLoader loader = openVerified(f, f.first(), f.second());
                ArtifactIntegrity.VerifiedMemorySource source = visitor -> loader.visitRetainedParts(visitor::accept);
                try {
                    ArtifactIntegrity.installVerifiedMemorySource(source);
                    check(ArtifactIntegrity.digest().equals(reference), "Normal production digest uses retained source");
                    expect(IllegalStateException.class, () -> ArtifactIntegrity.installVerifiedMemorySource(source));
                } finally { loader.close(); }
                check(Files.isRegularFile(firstPath) && Files.isRegularFile(secondPath), "Valid fallback fixtures still exist");
                expect(IOException.class, ArtifactIntegrity::digest);
            });
        } finally {
            if (oldProperty == null) System.clearProperty("celeste.payload.parts");
            else System.setProperty("celeste.payload.parts", oldProperty);
        }
        System.out.println("PASS: " + passed + " real memory/integrity integration groups on " + Runtime.version());
    }

    private static TwoPartMemoryClassLoader openVerified(Fixture f, byte[] first, byte[] second) throws Exception {
        if (!Crypto.verify(f.key().getPublic(), f.metadata(), f.signature())) throw new IOException("Untrusted fixture metadata");
        String[] fields = new String(f.metadata(), StandardCharsets.UTF_8).split("\n", -1);
        check(fields.length == 3 && fields[0].equals(DOMAIN), "Signed fixture metadata framing");
        return TwoPartMemoryClassLoader.open(first, fields[1], second, fields[2], NAMESPACES,
            TwoPartMemoryClassLoader.Limits.betaDefaults(), MemoryIntegrityIntegrationTest.class.getClassLoader());
    }

    private record Fixture(byte[] first, byte[] second, byte[] metadata, byte[] signature, KeyPair key,
                           List<Map<String, byte[]>> parts) {}

    private static Fixture fixture() throws Exception {
        Map<String, byte[]> classes = TwoPartMemoryClassLoaderTest.compile(Map.of(
            "demo.payload.First", """
                package demo.payload;
                public final class First {
                    public static String value() { return "first+" + Second.value(); }
                    public static String asset() throws Exception {
                        try (var in = First.class.getResourceAsStream("/assets/beta/message.txt")) {
                            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                """,
            "demo.payload.Second", "package demo.payload; public final class Second { public static String value() { return \"second\"; } }"
        ));
        Map<String, byte[]> first = Map.of("META-INF/MANIFEST.MF", utf8("Manifest-Version: 1.0\r\n\r\n"),
            "demo/payload/", new byte[0], "demo/payload/First.class", classes.get("demo.payload.First"),
            "assets/beta/shaders/fixture.vsh", utf8("// synthetic provider resource\n"));
        Map<String, byte[]> second = Map.of("META-INF/beta-release.properties", utf8("release=fixture\n"),
            "assets/beta/message.txt", utf8("asset from part two"), EXCLUDED, utf8("mutable descriptor fixture"),
            "demo/payload/Second.class", classes.get("demo.payload.Second"));
        byte[] firstJar = TwoPartMemoryClassLoaderTest.jar(first);
        byte[] secondJar = TwoPartMemoryClassLoaderTest.jar(second);
        KeyPair key = Crypto.generateEd25519();
        byte[] metadata = utf8(DOMAIN + "\n" + Crypto.hex(Crypto.sha256(firstJar)) + "\n" + Crypto.hex(Crypto.sha256(secondJar)));
        return new Fixture(firstJar, secondJar, metadata, Crypto.sign(key.getPrivate(), metadata), key, List.of(first, second));
    }

    private static String reference(List<Map<String, byte[]>> parts) throws Exception {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (Map<String, byte[]> part : parts) for (var entry : part.entrySet()) {
            if (!entry.getKey().endsWith("/") && !entry.getKey().equals(EXCLUDED)) entries.add(entry);
        }
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (var entry : entries) {
            byte[] name = utf8(entry.getKey());
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            try (DataOutputStream frame = new DataOutputStream(header)) {
                frame.writeInt(name.length); frame.write(name); frame.writeLong(entry.getValue().length);
            }
            digest.update(header.toByteArray()); digest.update(entry.getValue());
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String diskReference(List<Path> paths) throws Exception {
        // Exercise unchanged disk framing without selecting a live auth source before installation.
        var method = ArtifactIntegrity.class.getDeclaredMethod("digestJars", List.class, MessageDigest.class);
        method.setAccessible(true);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        method.invoke(null, paths, digest);
        return Crypto.hex(digest.digest());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> retainedIndex(TwoPartMemoryClassLoader loader) throws Exception {
        Field field = TwoPartMemoryClassLoader.class.getDeclaredField("entries");
        field.setAccessible(true); // Test evidence only; real callers use the visitor exclusively.
        return (Map<String, byte[]>) field.get(loader);
    }
    private static void unmodifiable(Map<String, byte[]> part) {
        try { part.put("assets/beta/forbidden", new byte[0]); }
        catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Map must be structurally unmodifiable");
    }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void run(String description, CheckedAction action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + description);
    }
    private static void expect(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
