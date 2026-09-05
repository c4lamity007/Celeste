package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.license.LicenseIdentity;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

public final class AuthBootstrap {
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cst-license-gateway");
        thread.setDaemon(true);
        return thread;
    });
    private static final CredentialStore STORE = new CredentialStore();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean AUTHORIZED = new AtomicBoolean();
    private static final AtomicBoolean LOCKED = new AtomicBoolean();
    private static volatile String status = "Waiting for license";
    private static volatile AuthConfig config;
    private static volatile AuthServiceClient service;
    private static volatile HardwareFingerprint hardware;
    private static volatile InstallCredential sessionCredential;
    private static volatile String sessionGrant;
    private static volatile long lastHeartbeatSuccessNanos;
    private static Runnable authorizedInitializer;
    private static Runnable authorizedShutdown;

    private AuthBootstrap() {
    }

    public static void install(Runnable initializer, Runnable shutdown) {
        authorizedInitializer = Objects.requireNonNull(initializer, "initializer");
        authorizedShutdown = Objects.requireNonNull(shutdown, "shutdown");
        ClientLifecycleEvents.CLIENT_STARTED.register(AuthBootstrap::start);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> WORKER.shutdownNow());
        WORKER.scheduleWithFixedDelay(AuthBootstrap::heartbeat, 30, 30, TimeUnit.SECONDS);
    }

    public static String status() {
        return status;
    }

    public static boolean busy() {
        return BUSY.get();
    }

    public static boolean locked() {
        return LOCKED.get();
    }

    public static boolean authorized() {
        return AUTHORIZED.get() && !LOCKED.get();
    }

    public static boolean hasLocalCredential() {
        return STORE.exists();
    }

    public record CloudResponse(String contentType, byte[] body) {
    }

    public static CloudResponse cloudRequest(String method, String path, String contentType, byte[] body) throws Exception {
        AuthServiceClient currentService = service;
        String currentGrant = sessionGrant;
        if (!authorized() || currentService == null || currentGrant == null || currentGrant.isBlank()) {
            throw new IOException("Cloud configs require an active license session");
        }
        AuthServiceClient.CloudResponse response = currentService.cloudRequest(
            method,
            path,
            contentType,
            body,
            currentGrant
        );
        return new CloudResponse(response.contentType(), response.body());
    }

    public static void submitLicense(String licenseKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (licenseKey == null || licenseKey.strip().length() < 20 || !BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Binding this installation";
        WORKER.execute(() -> {
            try {
                ensureReady();
                AuthServiceClient.Enrollment enrollment = service.enroll(licenseKey, hardware);
                STORE.save(enrollment.credential());
                AuthServiceClient.Authenticated authenticated = service.authenticate(enrollment.credential(), hardware);
                authorize(client, authenticated, enrollment.credential());
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception));
            } finally {
                BUSY.set(false);
            }
        });
    }

    public static void retry() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (!STORE.exists()) {
            show(client, "Enter a license to bind this installation");
            return;
        }
        authenticateStored(client);
    }

    public static void clearLocalBinding() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (BUSY.get() || LOCKED.get()) {
            return;
        }
        try {
            STORE.clear();
            show(client, "Local binding cleared; enter a license");
        } catch (IOException exception) {
            show(client, "Could not clear the local binding");
        }
    }

    private static void start(MinecraftClient client) {
        if (STORE.exists()) {
            authenticateStored(client);
        } else {
            show(client, "Enter a license to bind this installation");
            WORKER.execute(() -> {
                try {
                    ensureReady();
                } catch (Exception exception) {
                    fail(client, friendlyMessage(exception));
                }
            });
        }
    }

    private static void authenticateStored(MinecraftClient client) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Verifying this installation";
        show(client, status);
        WORKER.execute(() -> {
            try {
                ensureReady();
                InstallCredential credential = STORE.load();
                AuthServiceClient.Authenticated authenticated = service.authenticate(credential, hardware);
                authorize(client, authenticated, credential);
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception));
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static synchronized void ensureReady() throws Exception {
        if (service != null && hardware != null) {
            return;
        }
        AuthConfig loaded = AuthConfig.load();
        String observedDigest = ArtifactIntegrity.digest();
        if (!loaded.releaseDigest().equals(observedDigest)) {
            reportStartupIntegrityFailure(loaded, observedDigest);
            throw new SecurityException("Client integrity verification failed");
        }
        HardwareFingerprint observedHardware = HardwareFingerprint.collect();
        config = loaded;
        hardware = observedHardware;
        service = new AuthServiceClient(loaded, GameIdentity.collect());
    }

    private static void reportStartupIntegrityFailure(AuthConfig loaded, String observedDigest) {
        if (!STORE.exists()) {
            return;
        }
        try {
            AuthServiceClient reporter = new AuthServiceClient(loaded, GameIdentity.collect());
            reporter.checkSession(STORE.load(), observedDigest, true);
        } catch (Exception ignored) {
            // The local client still fails closed even if the signed report cannot be delivered.
        }
    }

    private static void authorize(
        MinecraftClient client,
        AuthServiceClient.Authenticated authenticated,
        InstallCredential credential
    ) {
        if (!AUTHORIZED.compareAndSet(false, true)) {
            return;
        }
        sessionCredential = credential;
        sessionGrant = authenticated.grant();
        lastHeartbeatSuccessNanos = System.nanoTime();
        status = "Authorized";
        client.execute(() -> {
            LicenseIdentity.accept(authenticated.uid());
            authorizedInitializer.run();
        });
    }

    private static void heartbeat() {
        if (!AUTHORIZED.get() || LOCKED.get()) {
            return;
        }
        AuthServiceClient currentService = service;
        InstallCredential credential = sessionCredential;
        AuthConfig currentConfig = config;
        if (currentService == null || credential == null || currentConfig == null) {
            lockSession("Session verification failed");
            return;
        }
        try {
            String observedDigest = ArtifactIntegrity.digest();
            if (!currentConfig.releaseDigest().equals(observedDigest)) {
                try {
                    currentService.checkSession(credential, observedDigest, true);
                } finally {
                    lockSession("Client integrity verification failed");
                }
                return;
            }
            sessionGrant = currentService.checkSession(credential, observedDigest, false);
            lastHeartbeatSuccessNanos = System.nanoTime();
        } catch (AuthServiceClient.AuthRejectedException exception) {
            if (exception.statusCode() == 401 || exception.statusCode() == 403 || exception.statusCode() == 409) {
                lockSession("License revoked, expired, or no longer authorized");
            } else {
                lockAfterHeartbeatGrace();
            }
        } catch (SecurityException | GeneralSecurityException exception) {
            lockSession(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            lockAfterHeartbeatGrace();
        }
    }

    private static void lockAfterHeartbeatGrace() {
        long elapsed = System.nanoTime() - lastHeartbeatSuccessNanos;
        if (elapsed >= TimeUnit.SECONDS.toNanos(95)) {
            lockSession("Authorization service could not revalidate this session");
        }
    }

    private static void lockSession(String message) {
        if (!AUTHORIZED.compareAndSet(true, false)) {
            return;
        }
        LOCKED.set(true);
        sessionCredential = null;
        sessionGrant = null;
        status = message;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                authorizedShutdown.run();
            } finally {
                LicenseIdentity.clear();
            }
        });
    }

    private static void fail(MinecraftClient client, String message) {
        status = message;
        show(client, message);
    }

    private static void show(MinecraftClient client, String message) {
        status = message;
    }

    private static String friendlyMessage(Exception exception) {
        if (exception instanceof AuthServiceClient.AuthRejectedException) {
            return exception.getMessage();
        }
        if (exception instanceof SecurityException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "Authentication service is unavailable";
        }
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "Authentication was interrupted";
        }
        return "This installation could not be verified";
    }
}
