package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AuthServiceClient {
    record RemoteChunk(String url, String sha256) {
    }

    record RemotePayload(String release, String sha256, List<RemoteChunk> chunks) {
    }

    record RemoteLoader(String url, String sha256) {
    }

    record Authenticated(long uid, String grant, RemotePayload payload, RemoteLoader loader) {
    }

    record CloudResponse(String contentType, byte[] body) {
    }

    record Enrollment(InstallCredential credential) {
    }

    private static final Gson GSON = new Gson();
    private static final byte[] TOKEN_INFO = "celeste-install-token-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AuthConfig config;
    private final HttpClient http;
    private final PublicKey serverKey;
    private final GameIdentity identity;
    private final String expectedBetaCatalog;

    AuthServiceClient(AuthConfig config, GameIdentity identity) throws GeneralSecurityException {
        this(config, identity, null);
    }

    AuthServiceClient(AuthConfig config, GameIdentity identity, String expectedBetaCatalog)
        throws GeneralSecurityException {
        this(config, identity, expectedBetaCatalog, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    /** Package-local transport seam for protocol fixtures; runtime always uses the HTTPS client above. */
    AuthServiceClient(AuthConfig config, GameIdentity identity, String expectedBetaCatalog, HttpClient http)
        throws GeneralSecurityException {
        if (expectedBetaCatalog != null && !expectedBetaCatalog.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected beta catalog hash is invalid");
        }
        this.config = config;
        this.identity = identity;
        this.expectedBetaCatalog = expectedBetaCatalog;
        this.serverKey = Crypto.ed25519Public(config.serverKey());
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    Enrollment enroll(String licenseKey, HardwareFingerprint hardware) throws Exception {
        KeyPair installSigning = Crypto.generateEd25519();
        KeyPair installEncryption = Crypto.generateX25519();

        JsonObject startBody = releaseBody();
        startBody.addProperty("license_key", licenseKey.strip());
        startBody.addProperty("install_signing_key", Crypto.b64(Crypto.rawPublic(installSigning.getPublic())));
        startBody.addProperty("install_encryption_key", Crypto.b64(Crypto.rawPublic(installEncryption.getPublic())));
        startBody.add("hardware", hardwareJson(hardware));
        JsonObject start = post("/v1/enroll/start", startBody);
        byte[] message = requiredB64(start, "message");
        requireProduct(GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class));

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", requiredString(start, "challenge_id"));
        completeBody.addProperty("install_signature", Crypto.b64(Crypto.sign(installSigning.getPrivate(), message)));
        JsonObject complete = post("/v1/enroll/complete", completeBody);

        String deviceId = requiredString(complete, "device_id");
        long uid = requiredLong(complete, "uid");
        String certificate = requiredString(complete, "device_certificate");
        JsonObject certificateClaims = verifyToken(certificate, "celeste-installation");
        if (!deviceId.equals(requiredString(certificateClaims, "device"))
            || uid != Long.parseLong(requiredString(certificateClaims, "sub"))
            || !Crypto.b64(Crypto.rawPublic(installSigning.getPublic())).equals(requiredString(certificateClaims, "install_key"))) {
            throw new GeneralSecurityException("Installation certificate does not match this device");
        }

        byte[] tokenSeed = decryptToken(
            requiredObject(complete, "token_envelope"),
            installEncryption.getPrivate(),
            message
        );
        try {
            InstallCredential credential = new InstallCredential(
                deviceId,
                uid,
                Crypto.b64(installSigning.getPrivate().getEncoded()),
                Crypto.b64(installEncryption.getPrivate().getEncoded()),
                Crypto.b64(tokenSeed),
                certificate,
                identity.uuid(),
                identity.username()
            );
            credential.validate();
            return new Enrollment(credential);
        } finally {
            Arrays.fill(tokenSeed, (byte) 0);
        }
    }

    Authenticated authenticate(InstallCredential credential, HardwareFingerprint hardware) throws Exception {
        credential.validate();
        JsonObject startBody = releaseBody();
        startBody.addProperty("device_id", credential.deviceId());
        if (expectedBetaCatalog != null) {
            startBody.addProperty("beta_catalog_sha256", expectedBetaCatalog);
        }
        startBody.add("hardware", hardwareJson(hardware));
        JsonObject start = post("/v1/auth/start", startBody);
        byte[] message = requiredB64(start, "message");
        requireProduct(GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class));

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", requiredString(start, "challenge_id"));
        completeBody.addProperty("install_signature", Crypto.b64(Crypto.sign(credential.signingKey(), message)));
        completeBody.addProperty("token_signature", Crypto.b64(Crypto.sign(credential.tokenKey(), message)));
        JsonObject complete = post("/v1/auth/complete", completeBody);
        String grant = requiredString(complete, "grant");
        long uid = requiredLong(complete, "uid");
        JsonObject claims = verifyToken(grant, "celeste-client");
        requireProduct(claims);
        if (expectedBetaCatalog != null) {
            requireBetaContext(claims);
        }
        if (uid != credential.uid()
            || uid != Long.parseLong(requiredString(claims, "sub"))
            || !credential.deviceId().equals(requiredString(claims, "device"))
            || !config.releaseId().equals(requiredString(claims, "release"))
            || !config.releaseDigest().equals(requiredString(claims, "client_sha256"))) {
            throw new GeneralSecurityException("Session grant does not match this installation");
        }
        if (expectedBetaCatalog != null) {
            JsonObject runtime = requiredObject(complete, "runtime");
            if (!config.releaseId().equals(requiredString(runtime, "release"))
                || !config.releaseDigest().equals(requiredHex(runtime, "sha256"))
                || !config.clientVersion().equals(requiredString(runtime, "client_version"))
                || !expectedBetaCatalog.equals(requiredHex(runtime, "catalog_sha256"))
                || complete.has("artifact") || complete.has("loader")
                || claims.has("chunks") || claims.has("stage2") || claims.has("stage2_sha256")) {
                throw new GeneralSecurityException("Beta runtime grant does not match the verified catalog");
            }
            // The beta host already supplied both verified parts. This grant
            // must never be usable as a normal bootstrap/download authorization.
            return new Authenticated(uid, grant, null, null);
        }
        if (complete.has("runtime") || claims.has("beta_catalog_sha256")) {
            throw new GeneralSecurityException("Beta authorization requires its verified catalog context");
        }
        JsonObject artifact = requiredObject(complete, "artifact");
        JsonArray rawChunks = artifact.getAsJsonArray("chunks");
        if (rawChunks == null || rawChunks.size() != 2) {
            throw new GeneralSecurityException("Payload manifest is incomplete");
        }
        List<RemoteChunk> chunks = rawChunks.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .map(chunk -> new RemoteChunk(requiredString(chunk, "url"), requiredHex(chunk, "sha256")))
            .toList();
        RemotePayload payload = new RemotePayload(
            requiredString(artifact, "release"),
            requiredHex(artifact, "sha256"),
            chunks
        );
        JsonObject rawLoader = requiredObject(complete, "loader");
        RemoteLoader loader = new RemoteLoader(
            requiredString(rawLoader, "url"),
            requiredHex(rawLoader, "sha256")
        );
        if (!config.releaseId().equals(payload.release()) || !config.releaseDigest().equals(payload.sha256())) {
            throw new GeneralSecurityException("Payload manifest does not match the authorized release");
        }
        return new Authenticated(uid, grant, payload, loader);
    }

    void consumeInstallerTicket(String ticket, InstallCredential credential) throws Exception {
        JsonObject claims = verifyToken(ticket, "celeste-installer");
        requireProduct(claims);
        if (!credential.deviceId().equals(requiredString(claims, "device"))
            || credential.uid() != Long.parseLong(requiredString(claims, "sub"))
            || requiredHex(claims, "client_sha256").isBlank()
            || !config.releaseId().equals(requiredString(claims, "payload_release"))
            || !config.releaseDigest().equals(requiredHex(claims, "payload_sha256"))
            || requiredString(claims, "bootstrap").isBlank()
            || requiredHex(claims, "bootstrap_sha256").isBlank()) {
            throw new GeneralSecurityException("Native launch ticket does not match this installation");
        }
        HttpRequest request = HttpRequest.newBuilder(sameOrigin("/v1/installer/consume"))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + ticket)
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.US_ASCII))
            .build();
        HttpResponse<String> response = http.send(
            request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Native launch authorization was rejected");
        }
        JsonObject accepted = GSON.fromJson(response.body(), JsonObject.class);
        if (accepted == null
            || !accepted.has("accepted")
            || !accepted.get("accepted").getAsBoolean()) {
            throw new GeneralSecurityException("Native launch authorization was not accepted");
        }
    }

    byte[] download(String path, String grant) throws IOException, InterruptedException, AuthRejectedException {
        URI endpoint = sameOrigin(path);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/octet-stream")
            .header("Authorization", "Bearer " + grant)
            .GET()
            .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Protected artifact download failed");
        }
        return response.body();
    }

    String checkSession(InstallCredential credential, String observedDigest, boolean integrityMismatch) throws Exception {
        credential.validate();
        long timestamp = Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[18];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Crypto.b64(nonceBytes);
        String event = integrityMismatch ? "integrity_mismatch" : "heartbeat";
        byte[] message = sessionMessage(
            credential.deviceId(),
            timestamp,
            nonce,
            config.releaseDigest(),
            observedDigest,
            event
        );

        JsonObject body = releaseBody();
        body.addProperty("device_id", credential.deviceId());
        body.addProperty("timestamp", timestamp);
        body.addProperty("nonce", nonce);
        body.addProperty("observed_sha256", observedDigest);
        body.addProperty("event", event);
        if (expectedBetaCatalog != null) {
            body.addProperty("beta_catalog_sha256", expectedBetaCatalog);
        }
        body.addProperty("install_signature", Crypto.b64(Crypto.sign(credential.signingKey(), message)));
        body.addProperty("token_signature", Crypto.b64(Crypto.sign(credential.tokenKey(), message)));
        JsonObject response = post("/v1/session/check", body);
        if (integrityMismatch) {
            throw new GeneralSecurityException("Client integrity verification failed");
        }
        String sessionToken = requiredString(response, "session_token");
        JsonObject claims = verifyToken(sessionToken, "celeste-session");
        requireProduct(claims);
        if (!"active".equals(requiredString(response, "status"))
            || !"active".equals(requiredString(claims, "status"))
            || !nonce.equals(requiredString(claims, "nonce"))
            || !credential.deviceId().equals(requiredString(claims, "device"))
            || credential.uid() != Long.parseLong(requiredString(claims, "sub"))
            || !config.releaseDigest().equals(requiredString(claims, "client_sha256"))) {
            throw new GeneralSecurityException("Session verification response did not match this installation");
        }
        if (expectedBetaCatalog != null) {
            requireBetaContext(claims);
        } else if (claims.has("beta_catalog_sha256")) {
            throw new GeneralSecurityException("Unexpected beta session context");
        }
        return sessionToken;
    }

    private void requireBetaContext(JsonObject claims) throws GeneralSecurityException {
        try {
            if (!expectedBetaCatalog.equals(requiredHex(claims, "beta_catalog_sha256"))
                || !config.releaseId().equals(requiredString(claims, "release"))
                || !config.clientVersion().equals(requiredString(claims, "client_version"))) {
                throw new GeneralSecurityException("Beta session belongs to another verified catalog");
            }
        } catch (IllegalStateException | IllegalArgumentException exception) {
            // A malformed signed context is an authentication failure, not a
            // transient transport outage eligible for the heartbeat grace period.
            throw new GeneralSecurityException("Malformed beta session context", exception);
        }
    }

    CloudResponse cloudRequest(String method, String path, String contentType, byte[] body, String grant)
        throws IOException, InterruptedException, AuthRejectedException {
        if (path == null || !path.startsWith("/v1/configs") || path.contains("..")) {
            throw new IOException("Cloud config path is invalid");
        }
        String verb = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (!verb.equals("GET") && !verb.equals("POST")) {
            throw new IOException("Cloud config method is invalid");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(sameOrigin(path))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, image/png")
            .header("Authorization", "Bearer " + grant);
        if (verb.equals("POST")) {
            builder.header("Content-Type", contentType == null ? "application/json" : contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            builder.GET();
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Cloud config request failed (" + response.statusCode() + ")");
        }
        if (response.body().length > 2_000_000) {
            throw new IOException("Cloud config response is too large");
        }
        return new CloudResponse(response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    private byte[] sessionMessage(
        String deviceId,
        long timestamp,
        String nonce,
        String expectedDigest,
        String observedDigest,
        String event
    ) {
        String message = "celeste-session-v1\n"
            + deviceId + "\n" + timestamp + "\n" + nonce + "\n"
            + expectedDigest + "\n" + observedDigest + "\n" + event + "\n"
            + identity.uuid() + "\n" + identity.username();
        if (expectedBetaCatalog != null) {
            message += "\n" + config.releaseId() + "\n" + config.clientVersion() + "\n" + expectedBetaCatalog;
        }
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private String product() { return expectedBetaCatalog == null ? "celeste" : "celeste_plus"; }

    private void requireProduct(JsonObject claims) throws GeneralSecurityException {
        try {
            if (claims == null || !product().equals(requiredString(claims, "product"))) {
                throw new GeneralSecurityException("Authorization belongs to another product");
            }
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed authorization product", exception);
        }
    }

    private JsonObject releaseBody() {
        JsonObject body = new JsonObject();
        body.addProperty("product", product());
        body.addProperty("release_id", config.releaseId());
        body.addProperty("client_version", config.clientVersion());
        body.addProperty("client_sha256", config.releaseDigest());
        body.addProperty("game_uuid", identity.uuid());
        body.addProperty("game_name", identity.username());
        return body;
    }

    private static JsonObject hardwareJson(HardwareFingerprint hardware) {
        JsonObject result = new JsonObject();
        result.addProperty("system", hardware.system());
        result.addProperty("board", hardware.board());
        result.addProperty("processor", hardware.processor());
        JsonArray monitors = new JsonArray();
        hardware.monitors().forEach(monitors::add);
        result.add("monitors", monitors);
        return result;
    }

    private byte[] decryptToken(JsonObject envelope, PrivateKey privateKey, byte[] message) throws Exception {
        byte[] ephemeralRaw = Crypto.b64(requiredString(envelope, "ephemeral_key"));
        byte[] nonce = Crypto.b64(requiredString(envelope, "nonce"));
        byte[] ciphertext = Crypto.b64(requiredString(envelope, "ciphertext"));
        JsonObject transcript = GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class);
        byte[] salt = Crypto.b64(requiredString(transcript, "nonce"));
        if (ephemeralRaw.length != 32 || nonce.length != 12 || salt.length != 32) {
            throw new GeneralSecurityException("Invalid token envelope");
        }
        KeyAgreement exchange = KeyAgreement.getInstance("X25519");
        exchange.init(privateKey);
        exchange.doPhase(Crypto.x25519Public(ephemeralRaw), true);
        byte[] wrappingKey = Crypto.hkdf(exchange.generateSecret(), salt, TOKEN_INFO, 32);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(message);
            byte[] seed = cipher.doFinal(ciphertext);
            if (seed.length != 32) {
                throw new GeneralSecurityException("Invalid installation token length");
            }
            return seed;
        } finally {
            Arrays.fill(wrappingKey, (byte) 0);
        }
    }

    private JsonObject verifyToken(String token, String audience) throws GeneralSecurityException {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new GeneralSecurityException("Malformed signed grant");
        }
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!Crypto.verify(serverKey, signed, Crypto.b64(parts[2]))) {
            throw new GeneralSecurityException("Signed grant was not issued by this server");
        }
        try {
            JsonObject header = GSON.fromJson(new String(Crypto.b64(parts[0]), StandardCharsets.UTF_8), JsonObject.class);
            JsonObject claims = GSON.fromJson(new String(Crypto.b64(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
            long now = Instant.now().getEpochSecond();
            if (!"EdDSA".equals(requiredString(header, "alg"))
                || !"CST".equals(requiredString(header, "typ"))
                || !audience.equals(requiredString(claims, "aud"))
                || requiredLong(claims, "nbf", 0) > now + 5
                || requiredLong(claims, "exp") <= now) {
                throw new GeneralSecurityException("Signed grant is not valid now");
            }
            return claims;
        } catch (JsonParseException | IllegalStateException exception) {
            throw new GeneralSecurityException("Malformed signed grant", exception);
        }
    }

    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException, AuthRejectedException {
        URI endpoint = sameOrigin(path);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = switch (response.statusCode()) {
                case 401, 403 -> "License or device verification failed";
                case 409 -> expectedBetaCatalog == null
                    ? "This license needs its device binding reset"
                    : "Refresh the beta release before trying again";
                case 429 -> "Too many attempts; wait before retrying";
                default -> "Authentication service returned " + response.statusCode();
            };
            throw new AuthRejectedException(response.statusCode(), message);
        }
        try {
            JsonObject parsed = GSON.fromJson(response.body(), JsonObject.class);
            if (parsed == null) {
                throw new JsonParseException("empty response");
            }
            return parsed;
        } catch (JsonParseException exception) {
            throw new IOException("Authentication service returned invalid data", exception);
        }
    }

    private URI sameOrigin(String path) throws IOException {
        URI endpoint = config.baseUri().resolve(path);
        URI base = config.baseUri();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
            || !base.getHost().equalsIgnoreCase(endpoint.getHost())
            || effectivePort(base) != effectivePort(endpoint)) {
            throw new IOException("Authentication response referenced an untrusted endpoint");
        }
        return endpoint;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return value.getAsString();
    }

    private static long requiredLong(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return value.getAsLong();
    }

    private static long requiredLong(JsonObject object, String name, long fallback) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsLong();
    }

    private static byte[] requiredB64(JsonObject object, String name) {
        return Crypto.b64(requiredString(object, name));
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("Missing object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static String requiredHex(JsonObject object, String name) {
        String value = requiredString(object, name).toLowerCase(java.util.Locale.ROOT);
        if (value.length() != 64 || !value.chars().allMatch(character ->
            character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalStateException("Invalid SHA-256 field: " + name);
        }
        return value;
    }

    static final class AuthRejectedException extends Exception {
        private final int statusCode;

        AuthRejectedException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
