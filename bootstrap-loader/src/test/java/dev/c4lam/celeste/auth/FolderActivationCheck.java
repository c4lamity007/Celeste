package dev.c4lam.celeste.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Offline fixture credentials only; no real account, server, or game access. */
public final class FolderActivationCheck {
    public static void main(String[] args) throws Exception {
        portableEnvelopeFixture();
        Path root = Files.createTempDirectory("celeste-folder-activation-");
        Path source = root.resolve("source.bin");
        Path target = root.resolve("target.bin");
        try {
            Path shared = CredentialStore.launcherActivationPath(root);
            require(shared.equals(root.resolve("Celeste/auth/a39fbbf8e5c344edb39ee82d/celeste-active.bin")),
                "shared path must match the native launcher's normal-product selection");
            try {
                CredentialStore.launcherActivationPath(Path.of("relative"));
                throw new AssertionError("relative user path accepted");
            } catch (IllegalArgumentException expected) { }
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
            InstallCredential fixture = new InstallCredential("nonsecret-fixture-device-0000000000", 7,
                key, key, key, "fixture-not-a-server-certificate", "fixture-game", "Fixture");
            CredentialStore sharedStore = new CredentialStore(source);
            CredentialStore localStore = new CredentialStore(target);
            require(!localStore.present(), "empty game has no local activation");
            sharedStore.save(fixture);
            byte[] original = Files.readAllBytes(source);
            InstallCredential loaded = sharedStore.load();
            require(loaded.equals(fixture), "existing protected envelope round trips");
            localStore.save(loaded);
            require(localStore.load().equals(fixture), "runtime can persist its imported identity");
            require(java.util.Arrays.equals(original, Files.readAllBytes(source)), "shared activation remains unchanged");
            Files.write(target, new byte[] {1, 2, 3});
            require(localStore.present(), "damaged local activation retains selection priority");
            try {
                localStore.load();
                throw new AssertionError("damaged credential accepted");
            } catch (java.security.GeneralSecurityException expected) { }
            System.out.println("PASS folder activation: shared channel path, encrypted round trip, import, no shared mutation, damaged-local rejection");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(target);
            Files.deleteIfExists(root);
        }
    }

    private static void portableEnvelopeFixture() throws Exception {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 32);
        byte[] plain = "nonsecret portable credential fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sealed = CredentialStore.portableProtectWithKey(plain, key, nonce);
        require(java.util.HexFormat.of().formatHex(sealed).equals(
            "43535032202122232425262728292a2bbc55c80309fb686b6e5c32a1b36c959bbc2cccfff5e5078b0282057325aa38600dfa9076eb9ec02829d82e3ff1fd07a85741d550c2"),
            "portable format must match the Rust AES-GCM envelope fixture");
        require(sealed[0] == 'C' && sealed[1] == 'S' && sealed[2] == 'P' && sealed[3] == '2',
            "portable envelope version must be stable");
        require(java.util.Arrays.equals(plain, CredentialStore.portableUnprotectWithKey(sealed, key)),
            "portable AES-GCM envelope must round trip");
        sealed[sealed.length - 1] ^= 1;
        try {
            CredentialStore.portableUnprotectWithKey(sealed, key);
            throw new AssertionError("tampered portable credential accepted");
        } catch (java.security.GeneralSecurityException expected) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
