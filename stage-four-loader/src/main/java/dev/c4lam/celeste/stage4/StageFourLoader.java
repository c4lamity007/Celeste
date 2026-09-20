package dev.c4lam.celeste.stage4;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import org.spongepowered.asm.mixin.Mixins;

/** Final remote loader. It alone receives payload-part grants and links Celeste into Fabric. */
public final class StageFourLoader {
    private static final Gson GSON = new Gson();
    private static final byte[] ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private StageFourLoader() {
    }

    public static Boolean load(Object rawBridge) throws Exception {
        Bridge bridge = new Bridge(rawBridge);
        URI base = URI.create(bridge.text("baseUrl"));
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
            throw new SecurityException("Payload linker endpoint is not HTTPS");
        }
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        JsonObject startBody = releaseBody(bridge);
        startBody.addProperty("device_id", bridge.text("deviceId"));
        startBody.add("hardware", GSON.fromJson(bridge.text("hardwareJson"), JsonObject.class));
        JsonObject start = post(http, base, "/v1/stage4/start", startBody);
        String message = required(start, "message");

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", required(start, "challenge_id"));
        completeBody.addProperty("install_signature", bridge.sign("signInstall", message));
        completeBody.addProperty("token_signature", bridge.sign("signToken", message));
        JsonObject complete = post(http, base, "/v1/stage4/complete", completeBody,
            bridge.proof("POST", sameOrigin(base, "/v1/stage4/complete"), null));

        String grant = required(complete, "grant");
        JsonObject claims = verifyGrant(grant, bridge);
        if (!bridge.text("deviceId").equals(required(claims, "device"))
            || !bridge.text("releaseId").equals(required(claims, "release"))
            || !bridge.text("releaseDigest").equals(requiredHex(claims, "client_sha256"))) {
            throw new SecurityException("Payload grant is bound to another release");
        }

        JsonObject artifact = requiredObject(complete, "artifact");
        if (!bridge.text("releaseId").equals(required(artifact, "release"))
            || !bridge.text("releaseDigest").equals(requiredHex(artifact, "sha256"))) {
            throw new SecurityException("Payload manifest is not authorized");
        }
        JsonArray chunks = artifact.getAsJsonArray("chunks");
        if (chunks == null || chunks.size() != 2) {
            throw new SecurityException("Payload must contain exactly two parts");
        }

        Path[] parts = new Path[2];
        for (int index = 0; index < parts.length; index++) {
            JsonObject chunk = chunks.get(index).getAsJsonObject();
            byte[] content = get(http, base, required(chunk, "url"), grant, bridge);
            requireDigest(content, requiredHex(chunk, "sha256"), "payload part " + (index + 1));
            parts[index] = writeRuntimeFile(
                "payload-" + bridge.text("releaseId") + "-" + (index + 1) + ".jar",
                content
            );
            FabricLauncherBase.getLauncher().addToClassPath(parts[index]);
        }

        String payloadEntrypoint = registerPayloadMod(parts[0], parts[1]);
        System.setProperty("celeste.payload.parts", parts[0] + java.io.File.pathSeparator + parts[1]);
        System.setProperty("celeste.payload.entrypoint", payloadEntrypoint);
        Mixins.addConfiguration("celeste.mixins.json");
        return Boolean.TRUE;
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

    private static JsonObject post(HttpClient http, URI base, String path, JsonObject body) throws Exception {
        return post(http, base, path, body, null);
    }

    private static JsonObject post(HttpClient http, URI base, String path, JsonObject body, String proof) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(sameOrigin(base, path))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (proof != null) builder.header("DPoP", proof);
        HttpRequest request = builder.build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SecurityException("Payload gate rejected the installation (" + response.statusCode() + ")");
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private static byte[] get(HttpClient http, URI base, String path, String grant, Bridge bridge) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(sameOrigin(base, path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/octet-stream")
            .header("Authorization", "DPoP " + grant)
            .header("DPoP", bridge.proof("GET", sameOrigin(base, path), grant))
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SecurityException("Payload delivery was rejected (" + response.statusCode() + ")");
        }
        return response.body();
    }

    private static URI sameOrigin(URI base, String path) throws IOException {
        URI endpoint = base.resolve(path);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
            || !base.getHost().equalsIgnoreCase(endpoint.getHost())
            || port(base) != port(endpoint)) {
            throw new IOException("Payload manifest referenced another origin");
        }
        return endpoint;
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static JsonObject verifyGrant(String token, Bridge bridge) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new SecurityException("Malformed payload grant");
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
            throw new SecurityException("Payload grant signature is invalid");
        }
        JsonObject claims = GSON.fromJson(new String(decode(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
        if (!bridge.text("dpopThumbprint").equals(required(requiredObject(claims, "cnf"), "jkt"))) {
            throw new SecurityException("Payload grant has the wrong DPoP installation binding");
        }
        long now = Instant.now().getEpochSecond();
        if (!"celeste-stage-four".equals(required(claims, "aud"))
            || claims.get("nbf").getAsLong() > now + 5
            || claims.get("exp").getAsLong() <= now) {
            throw new SecurityException("Payload grant is not valid now");
        }
        return claims;
    }

    private static String registerPayloadMod(Path first, Path second) throws Exception {
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        if (loader.isModLoaded("celeste")) {
            String existing = System.getProperty("celeste.payload.entrypoint", "").strip();
            if (existing.isEmpty()) {
                throw new IOException("Registered payload entrypoint metadata is missing");
            }
            return existing;
        }
        LoaderModMetadata metadata;
        String payloadEntrypoint;
        try (ZipFile archive = new ZipFile(first.toFile())) {
            ZipEntry entry = archive.getEntry("fabric.mod.json");
            if (entry == null) {
                throw new IOException("Payload metadata is missing");
            }
            try (InputStream input = archive.getInputStream(entry)) {
                byte[] rawMetadata = input.readAllBytes();
                metadata = ModMetadataParser.parseMetadata(
                    new ByteArrayInputStream(rawMetadata),
                    first.toString(),
                    new ArrayList<>(),
                    new VersionOverrides(),
                    new DependencyOverrides(first.resolveSibling("no-dependency-overrides.json")),
                    false
                );
                payloadEntrypoint = clientEntrypoint(rawMetadata);
            }
        }
        Method create = ModCandidateImpl.class.getDeclaredMethod(
            "createPlain",
            List.class,
            LoaderModMetadata.class,
            boolean.class,
            Collection.class
        );
        create.setAccessible(true);
        ModCandidateImpl candidate = (ModCandidateImpl) create.invoke(
            null,
            List.of(first, second),
            metadata,
            false,
            List.of()
        );
        ModContainerImpl container = new ModContainerImpl(candidate);
        loader.getModsInternal().add(container);
        Field mapField = FabricLoaderImpl.class.getDeclaredField("modMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) mapField.get(loader);
        modMap.put("celeste", container);
        return payloadEntrypoint;
    }

    private static String clientEntrypoint(byte[] rawMetadata) throws IOException {
        JsonObject root = GSON.fromJson(new String(rawMetadata, StandardCharsets.UTF_8), JsonObject.class);
        JsonObject entrypoints = root == null ? null : root.getAsJsonObject("entrypoints");
        JsonArray clients = entrypoints == null ? null : entrypoints.getAsJsonArray("client");
        if (clients == null || clients.isEmpty()) {
            throw new IOException("Payload client entrypoint metadata is missing");
        }
        JsonElement first = clients.get(0);
        String value = first.isJsonPrimitive()
            ? first.getAsString()
            : first.isJsonObject() && first.getAsJsonObject().has("value")
                ? first.getAsJsonObject().get("value").getAsString()
                : "";
        if (value.isBlank()) {
            throw new IOException("Payload client entrypoint metadata is invalid");
        }
        return value;
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

        private String proof(String method, URI endpoint, String grant) throws Exception {
            return String.valueOf(target.getClass().getMethod("dpopProof", String.class, String.class, String.class)
                .invoke(target, method, endpoint.toString(), grant));
        }
    }
}
