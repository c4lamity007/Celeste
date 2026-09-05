package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

final class CredentialStore {
    private static final byte[] MAGIC = {'C', 'S', 'T', '1'};
    private static final Gson GSON = new Gson();
    private final Path path;

    CredentialStore() {
        this.path = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("auth").resolve("device.bin");
    }

    boolean exists() {
        return Files.isRegularFile(path);
    }

    InstallCredential load() throws IOException, GeneralSecurityException {
        requireWindows();
        byte[] stored = Files.readAllBytes(path);
        if (stored.length <= MAGIC.length || !Arrays.equals(MAGIC, Arrays.copyOf(stored, MAGIC.length))) {
            throw new GeneralSecurityException("Credential envelope is invalid");
        }
        byte[] protectedValue = Arrays.copyOfRange(stored, MAGIC.length, stored.length);
        byte[] plain;
        try {
            plain = Crypt32Util.cryptUnprotectData(protectedValue);
        } catch (RuntimeException exception) {
            throw new GeneralSecurityException("Credential is not valid for this Windows account", exception);
        }
        try {
            InstallCredential credential = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), InstallCredential.class);
            if (credential == null) {
                throw new GeneralSecurityException("Credential is empty");
            }
            credential.validate();
            return credential;
        } catch (JsonParseException exception) {
            throw new GeneralSecurityException("Credential is damaged", exception);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    void save(InstallCredential credential) throws IOException, GeneralSecurityException {
        requireWindows();
        credential.validate();
        byte[] plain = GSON.toJson(credential).getBytes(StandardCharsets.UTF_8);
        byte[] protectedValue;
        try {
            protectedValue = Crypt32Util.cryptProtectData(plain);
        } catch (RuntimeException exception) {
            throw new GeneralSecurityException("Windows could not protect the credential", exception);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] stored = new byte[MAGIC.length + protectedValue.length];
        System.arraycopy(MAGIC, 0, stored, 0, MAGIC.length);
        System.arraycopy(protectedValue, 0, stored, MAGIC.length, protectedValue.length);
        Files.write(temporary, stored);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void clear() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
    }

    private static void requireWindows() throws GeneralSecurityException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new GeneralSecurityException("This build requires Windows credential protection");
        }
    }
}

