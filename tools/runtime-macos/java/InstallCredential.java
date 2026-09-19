package dev.c4lam.celeste.auth;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;

record InstallCredential(
    String deviceId,
    long uid,
    String signingPrivate,
    String encryptionPrivate,
    String tokenSeed,
    String certificate,
    String gameUuid,
    String gameName
) {
    PrivateKey signingKey() throws GeneralSecurityException {
        byte[] encoded = Crypto.b64(signingPrivate);
        return encoded.length == 32 ? Crypto.ed25519Seed(encoded) : Crypto.privateKey("Ed25519", encoded);
    }

    PrivateKey encryptionKey() throws GeneralSecurityException {
        byte[] encoded = Crypto.b64(encryptionPrivate);
        return encoded.length == 32 ? Crypto.x25519Seed(encoded) : Crypto.privateKey("X25519", encoded);
    }

    PrivateKey tokenKey() throws GeneralSecurityException {
        byte[] seed = Crypto.b64(tokenSeed);
        if (seed.length != 32) {
            throw new GeneralSecurityException("Invalid installation token");
        }
        return Crypto.ed25519Seed(seed);
    }

    void validate() throws GeneralSecurityException {
        if (deviceId == null || deviceId.length() < 32 || uid <= 0 || certificate == null || certificate.isBlank()) {
            throw new GeneralSecurityException("Invalid installation credential");
        }
        signingKey();
        encryptionKey();
        tokenKey();
    }

    boolean hasGameIdentity() {
        return gameUuid != null && !gameUuid.isBlank() && gameName != null && !gameName.isBlank();
    }

    InstallCredential withGameIdentity(GameIdentity identity) {
        return new InstallCredential(
            deviceId,
            uid,
            signingPrivate,
            encryptionPrivate,
            tokenSeed,
            certificate,
            identity.uuid(),
            identity.username()
        );
    }
}
