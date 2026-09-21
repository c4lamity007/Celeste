package dev.c4lam.celeste.auth;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/** Proves that a default native-created item is not silently readable cross-process. */
public final class MacPortableCredentialConsentBoundaryCheck {
    private MacPortableCredentialConsentBoundaryCheck() { }

    public static void main(String[] arguments) throws Exception {
        Process process = new ProcessBuilder(PortableCredentialProtection.keyCommand("macOS"))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        byte[] key = null;
        try {
            key = PortableCredentialProtection.readKey(process, 2_500);
            throw new AssertionError(
                "A default native-created Keychain item was readable without consent"
            );
        } catch (GeneralSecurityException expected) {
            System.out.println(
                "PASS default native-created item requires macOS user consent for the Java reader"
            );
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
