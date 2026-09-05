package dev.c4lam.celeste.stage3;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.net.ssl.HttpsURLConnection;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

/** Second remote gate: independently validates the release and authorizes only the linker stage. */
public final class StageThreeLoader {
    private static final Gson JSON = new Gson();
    private static final byte[] KEY_HEADER = HexFormat.of().parseHex("302a300506032b6570032100");

    private StageThreeLoader() {
    }

    public static Boolean load(Object bridgeObject) throws Exception {
        ReflectiveBridge bridge = new ReflectiveBridge(bridgeObject);
        URI origin = checkedOrigin(bridge.get("baseUrl"));

        JsonObject request = identity(bridge);
        request.addProperty("device_id", bridge.get("deviceId"));
        request.add("hardware", JSON.fromJson(bridge.get("hardwareJson"), JsonObject.class));
        JsonObject challenge = exchange(origin, "/v1/stage3/start", request, null, false);

        String proof = text(challenge, "message");
        JsonObject response = new JsonObject();
        response.addProperty("challenge_id", text(challenge, "challenge_id"));
        response.addProperty("install_signature", bridge.sign("signInstall", proof));
        response.addProperty("token_signature", bridge.sign("signToken", proof));
        JsonObject completion = exchange(origin, "/v1/stage3/complete", response, null, false);

        String grant = text(completion, "grant");
        JsonObject claims = claims(grant, bridge);
        JsonObject loader = object(completion, "loader");
        String pathValue = text(loader, "url");
        String digest = digest(loader, "sha256");
        String id = pathValue.substring(pathValue.lastIndexOf('/') + 1);
        if (!bridge.get("deviceId").equals(text(claims, "device"))
            || !bridge.get("releaseId").equals(text(claims, "release"))
            || !id.equals(text(claims, "stage4"))
            || !digest.equals(digest(claims, "stage4_sha256"))) {
            throw new SecurityException("Linker-stage authorization does not match the active release");
        }

        byte[] loaderBytes = bytes(origin, pathValue, grant);
        verifySha(loaderBytes, digest);
        Path runtime = persist("stage-four-" + bridge.get("releaseId") + ".jar", loaderBytes);
        FabricLauncherBase.getLauncher().addToClassPath(runtime);
        Class<?> linker = FabricLauncherBase.getLauncher().loadIntoTarget(
            "dev.c4lam.celeste.stage4.StageFourLoader"
        );
        return Boolean.TRUE.equals(linker.getMethod("load", Object.class).invoke(null, bridgeObject));
    }

    private static JsonObject identity(ReflectiveBridge bridge) throws Exception {
        JsonObject value = new JsonObject();
        value.addProperty("release_id", bridge.get("releaseId"));
        value.addProperty("client_version", bridge.get("clientVersion"));
        value.addProperty("client_sha256", bridge.get("releaseDigest"));
        value.addProperty("game_uuid", bridge.get("gameUuid"));
        value.addProperty("game_name", bridge.get("gameName"));
        return value;
    }

    private static JsonObject exchange(
        URI origin,
        String path,
        JsonObject body,
        String bearer,
        boolean binary
    ) throws Exception {
        HttpURLConnection connection = connection(origin, path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", binary ? "application/octet-stream" : "application/json");
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        byte[] encoded = JSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(encoded.length);
        connection.getOutputStream().write(encoded);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new SecurityException("Release gate rejected the request (" + status + ")");
        }
        return JSON.fromJson(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
    }

    private static byte[] bytes(URI origin, String path, String bearer) throws Exception {
        HttpURLConnection connection = connection(origin, path);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("Authorization", "Bearer " + bearer);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new SecurityException("Linker stage was not delivered (" + status + ")");
        }
        return connection.getInputStream().readAllBytes();
    }

    private static HttpURLConnection connection(URI origin, String path) throws Exception {
        URI endpoint = origin.resolve(path);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
            || !origin.getHost().equalsIgnoreCase(endpoint.getHost())
            || effectivePort(origin) != effectivePort(endpoint)) {
            throw new SecurityException("Release gate referenced an untrusted host");
        }
        HttpsURLConnection connection = (HttpsURLConnection) endpoint.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(20_000);
        return connection;
    }

    private static URI checkedOrigin(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new SecurityException("Release gate requires HTTPS");
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private static JsonObject claims(String token, ReflectiveBridge bridge) throws Exception {
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) {
            throw new SecurityException("Release gate returned a malformed grant");
        }
        byte[] raw = Base64.getUrlDecoder().decode(bridge.get("serverKey"));
        byte[] encoded = new byte[KEY_HEADER.length + raw.length];
        System.arraycopy(KEY_HEADER, 0, encoded, 0, KEY_HEADER.length);
        System.arraycopy(raw, 0, encoded, KEY_HEADER.length, raw.length);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(segments[2]))) {
            throw new SecurityException("Release gate grant has an invalid signature");
        }
        JsonObject claims = JSON.fromJson(
            new String(Base64.getUrlDecoder().decode(segments[1]), StandardCharsets.UTF_8),
            JsonObject.class
        );
        long now = Instant.now().getEpochSecond();
        if (!"celeste-stage-three".equals(text(claims, "aud"))
            || claims.get("nbf").getAsLong() > now + 5
            || claims.get("exp").getAsLong() <= now) {
            throw new SecurityException("Release gate grant is expired or has the wrong audience");
        }
        return claims;
    }

    private static Path persist(String name, byte[] value) throws IOException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("runtime");
        Files.createDirectories(directory);
        Path destination = directory.resolve(name);
        Path pending = directory.resolve(name + ".pending");
        Files.write(pending, value);
        try {
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        destination.toFile().deleteOnExit();
        return destination;
    }

    private static void verifySha(byte[] value, String expected) throws Exception {
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(value);
        if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(expected))) {
            throw new SecurityException("Linker-stage digest does not match the signed manifest");
        }
    }

    private static JsonObject object(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("Missing object: " + name);
        }
        return element.getAsJsonObject();
    }

    private static String text(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return element.getAsString();
    }

    private static String digest(JsonObject value, String name) {
        String digest = text(value, name).toLowerCase(java.util.Locale.ROOT);
        if (digest.length() != 64 || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid digest: " + name);
        }
        return digest;
    }

    private static final class ReflectiveBridge {
        private final Object delegate;

        private ReflectiveBridge(Object delegate) {
            this.delegate = delegate;
        }

        private String get(String method) throws Exception {
            return String.valueOf(delegate.getClass().getMethod(method).invoke(delegate));
        }

        private String sign(String method, String value) throws Exception {
            return String.valueOf(delegate.getClass().getMethod(method, String.class).invoke(delegate, value));
        }
    }
}
