package dev.c4lam.celeste.stage2;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

/** First remote gate: proves the installation again and authorizes only stage three. */
public final class StageTwoLoader {
    private static final Gson GSON = new Gson();
    private static final byte[] ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private StageTwoLoader() {
    }

    public static Boolean load(Object rawBridge) throws Exception {
        Bridge bridge = new Bridge(rawBridge);
        URI base = secureBase(bridge.text("baseUrl"));
        HttpClient http = client();

        JsonObject startBody = releaseBody(bridge);
        startBody.addProperty("device_id", bridge.text("deviceId"));
        startBody.add("hardware", GSON.fromJson(bridge.text("hardwareJson"), JsonObject.class));
        JsonObject start = post(http, base, "/v1/stage2/start", startBody);
        String message = required(start, "message");

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", required(start, "challenge_id"));
        completeBody.addProperty("install_signature", bridge.sign("signInstall", message));
        completeBody.addProperty("token_signature", bridge.sign("signToken", message));
        JsonObject complete = post(http, base, "/v1/stage2/complete", completeBody);

        String grant = required(complete, "grant");
        JsonObject claims = verifyGrant(grant, bridge, "celeste-stage-two");
        JsonObject loader = requiredObject(complete, "loader");
        String digest = requiredHex(loader, "sha256");
        if (!bridge.text("deviceId").equals(required(claims, "device"))
            || !bridge.text("releaseId").equals(required(claims, "release"))
            || !required(claims, "stage3").equals(lastPath(required(loader, "url")))
            || !digest.equals(requiredHex(claims, "stage3_sha256"))) {
            throw new SecurityException("Third-stage authorization is not bound to this installation");
        }

        byte[] content = get(http, base, required(loader, "url"), grant);
        requireDigest(content, digest, "third-stage loader");
        Path path = writeRuntimeFile("stage-three-" + bridge.text("releaseId") + ".jar", content);
        FabricLauncherBase.getLauncher().addToClassPath(path);
        Class<?> entrypoint = FabricLauncherBase.getLauncher().loadIntoTarget(
            "dev.c4lam.celeste.stage3.StageThreeLoader"
        );
        Object accepted = entrypoint.getMethod("load", Object.class).invoke(null, rawBridge);
        return Boolean.TRUE.equals(accepted);
    }

    private static JsonObject releaseBody(Bridge bridge) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("release_id", bridge.text("releaseId"));
        body.addProperty("client_version", bridge.text("clientVersion"));
        body.addProperty("client_sha256", bridge.text("releaseDigest"));
        body.addProperty("game_uuid", bridge.text("gameUuid"));
        body.addProperty("game_name", bridge.text("gameName"));
        return body;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    private static URI secureBase(String value) {
        URI base = URI.create(value);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
            throw new SecurityException("Loader endpoint is not HTTPS");
        }
        return base;
    }

    private static JsonObject post(HttpClient http, URI base, String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(sameOrigin(base, path))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SecurityException("Third-stage gate rejected the installation (" + response.statusCode() + ")");
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private static byte[] get(HttpClient http, URI base, String path, String grant) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(sameOrigin(base, path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/octet-stream")
            .header("Authorization", "Bearer " + grant)
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SecurityException("Third-stage delivery was rejected (" + response.statusCode() + ")");
        }
        return response.body();
    }

    private static URI sameOrigin(URI base, String path) throws IOException {
        URI endpoint = base.resolve(path);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
            || !base.getHost().equalsIgnoreCase(endpoint.getHost())
            || port(base) != port(endpoint)) {
            throw new IOException("Loader manifest referenced another origin");
        }
        return endpoint;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static JsonObject verifyGrant(String token, Bridge bridge, String audience) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new SecurityException("Malformed staged grant");
        }
        byte[] rawKey = decode(bridge.text("serverKey"));
        byte[] encoded = new byte[ED25519_PREFIX.length + rawKey.length];
        System.arraycopy(ED25519_PREFIX, 0, encoded, 0, ED25519_PREFIX.length);
        System.arraycopy(rawKey, 0, encoded, ED25519_PREFIX.length, rawKey.length);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decode(parts[2]))) {
            throw new SecurityException("Staged grant signature is invalid");
        }
        JsonObject claims = GSON.fromJson(new String(decode(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
        long now = Instant.now().getEpochSecond();
        if (!audience.equals(required(claims, "aud"))
            || claims.get("nbf").getAsLong() > now + 5
            || claims.get("exp").getAsLong() <= now) {
            throw new SecurityException("Staged grant is not valid now");
        }
        return claims;
    }

    private static Path writeRuntimeFile(String name, byte[] content) throws IOException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("runtime");
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        Path temporary = directory.resolve(name + ".part");
        Files.write(temporary, content);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    private static void requireDigest(byte[] content, String expected, String label) throws Exception {
        byte[] actual = MessageDigest.getInstance("SHA-256").digest(content);
        if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(expected))) {
            throw new SecurityException(label + " failed digest verification");
        }
    }

    private static String lastPath(String path) {
        int split = path.lastIndexOf('/');
        return split < 0 ? path : path.substring(split + 1);
    }

    private static String required(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return value.getAsString();
    }

    private static String requiredHex(JsonObject object, String name) {
        String value = required(object, name).toLowerCase(java.util.Locale.ROOT);
        if (value.length() != 64 || !value.chars().allMatch(character ->
            character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalStateException("Invalid digest field: " + name);
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("Missing object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static final class Bridge {
        private final Object target;

        private Bridge(Object target) {
            this.target = target;
        }

        private String text(String method) throws Exception {
            return String.valueOf(target.getClass().getMethod(method).invoke(target));
        }

        private String sign(String method, String message) throws Exception {
            return String.valueOf(target.getClass().getMethod(method, String.class).invoke(target, message));
        }
    }
}
