package dev.c4lam.celeste.beta.probe;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Refuses every process except the fresh, nonce-bound JVM created by ProbeDriver. */
public final class ProbeAgent {
    public static void agentmain(String nonce, Instrumentation instrumentation) throws Exception {
        String expected = System.getProperty("celeste.beta.fixtureNonce", "");
        if (nonce == null || expected.length() != 64 || !MessageDigest.isEqual(
                nonce.getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Not the owned beta test fixture");
        }
        synchronized (System.getProperties()) {
            if (System.getProperties().putIfAbsent("celeste.beta.probe.claimed", "true") != null) {
                System.setProperty("celeste.beta.probe.result", "duplicate-rejected");
                return;
            }
        }
        Class<?> marker = null;
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getName().equals("dev.c4lam.celeste.beta.probe.Marker")) marker = candidate;
        }
        if (marker == null || !instrumentation.isRedefineClassesSupported()
                || !instrumentation.isModifiableClass(marker)) {
            throw new IllegalStateException("Fixture is not ready for class-redefinition checks");
        }
        byte[] original = resource("/fixtures/original.class");
        boolean restored = false;
        try {
            if (value(marker) != 1) throw new AssertionError("Unexpected original fixture behavior");
            instrumentation.redefineClasses(new ClassDefinition(marker, resource("/fixtures/updated.class")));
            if (value(marker) != 2) throw new AssertionError("Late body update did not take effect");
            boolean structuralChangeRejected = false;
            try {
                instrumentation.redefineClasses(new ClassDefinition(marker, resource("/fixtures/structural.class")));
            } catch (UnsupportedOperationException expectedFailure) {
                structuralChangeRejected = true;
            }
            if (!structuralChangeRejected || value(marker) != 2) {
                throw new AssertionError("Structural-change negative control failed");
            }
            instrumentation.redefineClasses(new ClassDefinition(marker, original));
            restored = value(marker) == 1;
            if (!restored) throw new AssertionError("Could not restore fixture behavior");
            System.setProperty("celeste.beta.probe.result", "pass");
            System.setProperty("celeste.beta.probe.executions", "1");
        } finally {
            if (!restored) {
                instrumentation.redefineClasses(new ClassDefinition(marker, original));
                System.setProperty("celeste.beta.probe.result", "failed-restored");
            }
        }
    }

    private static int value(Class<?> marker) throws Exception {
        return (Integer) marker.getMethod("value").invoke(null);
    }

    private static byte[] resource(String path) throws Exception {
        try (var stream = ProbeAgent.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing test fixture " + path);
            return stream.readAllBytes();
        }
    }
}
