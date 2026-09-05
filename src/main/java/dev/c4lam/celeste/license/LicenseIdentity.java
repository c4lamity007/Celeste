package dev.c4lam.celeste.license;

/**
 * Presents the numeric UID from the server-verified license grant, never an
 * in-game account or local user counter.
 */
public final class LicenseIdentity {
    private static volatile long uid;

    private LicenseIdentity() {
    }

    public static long uid() {
        return uid;
    }

    public static void accept(long verifiedUid) {
        if (verifiedUid <= 0) {
            throw new IllegalArgumentException("License UID must be positive");
        }
        uid = verifiedUid;
    }

    public static void clear() {
        uid = 0;
    }
}
