package dev.c4lam.celeste.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;

/** Narrow capability object passed to the separately loaded second stage. */
public final class GatewayBridge {
    // Stage two invokes this narrow API reflectively. Keeping the contract names
    // as literals lets the obfuscator preserve these public members while still
    // relocating and renaming the bridge class itself.
    private static final String[] STAGE_TWO_CONTRACT = {
        "baseUrl", "serverKey", "releaseId", "releaseDigest", "clientVersion",
        "deviceId", "uid", "gameUuid", "gameName", "hardwareJson",
        "signInstall", "signToken", "contextProof"
    };

    private final AuthConfig config;
    private final InstallCredential credential;
    private final HardwareFingerprint hardware;
    private final GameIdentity identity;

    GatewayBridge(
        AuthConfig config,
        InstallCredential credential,
        HardwareFingerprint hardware,
        GameIdentity identity
    ) {
        this.config = config;
        this.credential = credential;
        this.hardware = hardware;
        this.identity = identity;
    }

    public String baseUrl() {
        return config.baseUri().toString();
    }

    public String serverKey() {
        return Crypto.b64(config.serverKey());
    }

    public String releaseId() {
        return config.releaseId();
    }

    public String releaseDigest() {
        return config.releaseDigest();
    }

    public String clientVersion() {
        return config.clientVersion();
    }

    public String deviceId() {
        return credential.deviceId();
    }

    public long uid() {
        return credential.uid();
    }

    public String gameUuid() {
        return identity.uuid();
    }

    public String gameName() {
        return identity.username();
    }

    public String hardwareJson() {
        JsonObject result = new JsonObject();
        result.addProperty("system", hardware.system());
        result.addProperty("board", hardware.board());
        result.addProperty("processor", hardware.processor());
        JsonArray monitors = new JsonArray();
        hardware.monitors().forEach(monitors::add);
        result.add("monitors", monitors);
        return result.toString();
    }

    public String signInstall(String messageBase64) throws Exception {
        return Crypto.b64(Crypto.sign(credential.signingKey(), Crypto.b64(messageBase64)));
    }

    public String signToken(String messageBase64) throws Exception {
        return Crypto.b64(Crypto.sign(credential.tokenKey(), Crypto.b64(messageBase64)));
    }

    public String contextProof() throws Exception {
        byte[] message = ("celeste-stage-bridge-v1\n" + credential.deviceId() + "\n"
            + config.releaseId() + "\n" + config.releaseDigest()).getBytes(StandardCharsets.UTF_8);
        return Crypto.b64(Crypto.sign(credential.tokenKey(), message));
    }
}
