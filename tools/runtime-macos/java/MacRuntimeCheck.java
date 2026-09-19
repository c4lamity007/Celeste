package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MacRuntimeCheck {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("hardware")) {
            System.out.println(new Gson().toJson(HardwareFingerprint.collect()));
            return;
        }
        if (args[0].equals("path")) {
            Field path = CredentialStore.class.getDeclaredField("path");
            path.setAccessible(true);
            System.out.println(path.get(CredentialStore.launcherActivation()));
            return;
        }
        Path fixtures = Path.of(System.getenv("CELESTE_FIXTURE_DIR"));
        Method unprotect = CredentialStore.class.getDeclaredMethod("portableUnprotect", byte[].class);
        Method protect = CredentialStore.class.getDeclaredMethod("portableProtect", byte[].class);
        unprotect.setAccessible(true);
        protect.setAccessible(true);
        byte[] plain = "celeste-macos-nonsecret-fixture".getBytes(StandardCharsets.US_ASCII);
        byte[] opened = (byte[]) unprotect.invoke(null, (Object) Files.readAllBytes(fixtures.resolve("rust.bin")));
        if (!Arrays.equals(opened, plain)) throw new AssertionError("Rust/Java envelope mismatch");
        Files.write(fixtures.resolve("java.bin"), (byte[]) protect.invoke(null, (Object) plain));
        System.out.println("Java JNA opened Rust macOS Keychain envelope and created Java envelope");
    }
}
