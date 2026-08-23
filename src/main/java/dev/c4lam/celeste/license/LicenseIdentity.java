package dev.c4lam.celeste.license;

/**
 * Presents the UID assigned to a license, never an in-game account. Licensing
 * is not wired yet, so development builds deliberately use the first numeric
 * license UID. A future license service should replace this one source of
 * truth with its server-assigned value.
 */
public final class LicenseIdentity {
    private static final long DEVELOPMENT_LICENSE_UID = 1L;

    private LicenseIdentity() {
    }

    public static long uid() {
        return DEVELOPMENT_LICENSE_UID;
    }
}
