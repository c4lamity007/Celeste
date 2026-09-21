package dev.c4lam.celeste.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Mac-only CI fixture: production Java reader opens a production Rust envelope. */
public final class MacPortableCredentialInteropCheck {
    private static final int SYNTHETIC_BYTES = 257;

    private MacPortableCredentialInteropCheck() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected protected-envelope and plaintext paths");
        }
        byte[] envelope = Files.readAllBytes(Path.of(arguments[0]));
        byte[] expected = Files.readAllBytes(Path.of(arguments[1]));
        byte[] opened = null;
        byte[] damaged = null;
        try {
            if (expected.length != SYNTHETIC_BYTES || envelope.length > 1024 * 1024 + 64) {
                throw new AssertionError("Fixture files have unexpected bounds");
            }
            // This invokes /usr/bin/security through the exact production helper.
            opened = PortableCredentialProtection.unprotect(envelope);
            if (!MessageDigest.isEqual(expected, opened)) {
                throw new AssertionError("Rust/Java portable credential mismatch");
            }
            damaged = envelope.clone();
            damaged[damaged.length - 1] ^= 1;
            try {
                PortableCredentialProtection.unprotect(damaged);
                throw new AssertionError("Tampered portable credential was accepted");
            } catch (java.security.GeneralSecurityException expectedFailure) {
                // The same authorized reader succeeds, then AES-GCM rejects damage.
            }
            System.out.println("PASS Java /usr/bin/security reader opened the native Keychain envelope");
            System.out.println("PASS tampered synthetic envelope remains fail-closed");
        } finally {
            Arrays.fill(envelope, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            if (opened != null) Arrays.fill(opened, (byte) 0);
            if (damaged != null) Arrays.fill(damaged, (byte) 0);
        }
    }
}
