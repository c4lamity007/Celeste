package dev.c4lam.celeste.auth;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

final class GatewayRuntime {
    private static final CredentialStore STORE = new CredentialStore();
    private static volatile boolean payloadReady;
    private static volatile String failure = "Activate Celeste in the downloader before starting Minecraft";

    private GatewayRuntime() {
    }

    static void preLaunch() {
        try {
            boolean local = STORE.present();
            CredentialStore selected = local ? STORE : CredentialStore.launcherActivation();
            if (!selected.exists()) return;
            InstallCredential credential = selected.load();
            if (!credential.hasGameIdentity()) {
                throw new SecurityException("Install Celeste again to refresh this activation");
            }
            AuthConfig config = AuthConfig.load();
            HardwareFingerprint hardware = HardwareFingerprint.collect();
            GameIdentity identity = new GameIdentity(credential.gameUuid(), credential.gameName());
            AuthServiceClient service = new AuthServiceClient(config, identity);
            // An installed mod may first run days later. Authenticate the saved
            // device afresh on each startup; the install-time grant is not a launch ticket.
            AuthServiceClient.Authenticated authenticated = service.authenticate(credential, hardware);
            // A manually moved mod may run in a new game directory. Import only
            // after fresh server authentication; the downloader writes no game files.
            if (!local) STORE.save(credential);
            byte[] stageTwo = service.download(authenticated.loader().url(), authenticated.grant());
            requireDigest(stageTwo, authenticated.loader().sha256(), "second-stage loader");
            Path stageTwoPath = writeRuntimeFile("stage-two-" + config.releaseId() + ".jar", stageTwo);
            FabricLauncherBase.getLauncher().addToClassPath(stageTwoPath);
            Class<?> entrypoint = FabricLauncherBase.getLauncher().loadIntoTarget(
                "dev.c4lam.celeste.stage2.StageTwoLoader"
            );
            Object accepted = entrypoint.getMethod("load", Object.class).invoke(
                null,
                new GatewayBridge(config, credential, hardware, identity)
            );
            if (!Boolean.TRUE.equals(accepted)) {
                throw new SecurityException("Protected loader chain was not accepted");
            }
            payloadReady = true;
            failure = "";
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            failure = safeMessage(cause == null ? exception : cause);
        } catch (Exception exception) {
            failure = safeMessage(exception);
        }
    }

    static boolean payloadReady() {
        return payloadReady;
    }

    static String failure() {
        return failure;
    }

    static void launchPayload() {
        if (!payloadReady) {
            return;
        }
        try {
            String entrypointName = System.getProperty("celeste.payload.entrypoint", "").strip();
            if (entrypointName.isEmpty()) {
                throw new IllegalStateException("Payload entrypoint metadata is unavailable");
            }
            Class<?> type = FabricLauncherBase.getLauncher().loadIntoTarget(entrypointName);
            Object initializer = type.getConstructor().newInstance();
            type.getMethod("onInitializeClient").invoke(initializer);
        } catch (Exception exception) {
            payloadReady = false;
            failure = "Celeste entrypoint could not start";
            throw new IllegalStateException(failure, exception);
        }
    }

    private static Path writeRuntimeFile(String name, byte[] data) throws Exception {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("runtime");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        Path temporary = directory.resolve(name + ".part");
        Files.write(temporary, data);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    private static void requireDigest(byte[] data, String expected, String label) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        if (!MessageDigest.isEqual(
            actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            throw new SecurityException(label + " failed digest verification");
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "Protected loader chain failed";
        }
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
