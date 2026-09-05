package dev.c4lam.celeste.auth;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;

/** Optional one-use proof for the first launcher-assisted start after installation. */
final class LaunchTicketStore {
    private static final byte[] MAGIC = {'C', 'L', 'T', '1'};
    private final Path path = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("celeste")
        .resolve("auth")
        .resolve("launch.bin");

    String consumeIfPresent() throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        byte[] stored = Files.readAllBytes(path);
        Files.deleteIfExists(path);
        if (stored.length <= MAGIC.length || !Arrays.equals(MAGIC, Arrays.copyOf(stored, MAGIC.length))) {
            throw new GeneralSecurityException("Native launch ticket is invalid");
        }
        byte[] protectedValue = Arrays.copyOfRange(stored, MAGIC.length, stored.length);
        byte[] plain;
        try {
            plain = Crypt32Util.cryptUnprotectData(protectedValue);
        } catch (RuntimeException exception) {
            throw new GeneralSecurityException("Native launch ticket is not valid for this Windows account", exception);
        }
        try {
            String ticket = new String(plain, StandardCharsets.US_ASCII).strip();
            if (ticket.length() < 100 || ticket.length() > 4096) {
                throw new GeneralSecurityException("Native launch ticket is malformed");
            }
            return ticket;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }
}
