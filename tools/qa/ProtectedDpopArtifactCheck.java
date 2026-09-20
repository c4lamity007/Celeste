import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Exercises DPoP bytes from a protected release artifact, using only a published RFC 8032 test key. */
public final class ProtectedDpopArtifactCheck {
    private static final String SIGNER = "dev/c4lam/celeste/auth/DpopProofSigner";
    private static final String BRIDGE = "dev/c4lam/celeste/auth/GatewayBridge";
    private static final String EXPECTED_THUMBPRINT = "kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k";
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static int checks;

    private ProtectedDpopArtifactCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[3].equals("bridge") || args[3].equals("no-bridge"))) {
            throw new IllegalArgumentException("Usage: ProtectedDpopArtifactCheck <artifact.jar> <gson.jar> <mapping.json|-> <bridge|no-bridge>");
        }
        Path artifact = Path.of(args[0]).toRealPath();
        Path gson = Path.of(args[1]).toRealPath();
        Path mapping = args[2].equals("-") ? null : Path.of(args[2]).toRealPath();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{artifact.toUri().toURL(), gson.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> signerClass = Class.forName(mappedName(SIGNER, mapping), true, loader);
            check(signerClass.getProtectionDomain().getCodeSource().getLocation().toURI().equals(artifact.toUri()),
                "signer loaded from supplied artifact");
            byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
            byte[] publicRaw = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(concat(HexFormat.of().parseHex("302e020100300506032b657004220420"), seed)));
            var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(concat(HexFormat.of().parseHex("302a300506032b6570032100"), publicRaw)));
            var fixedClock = Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC);
            Object signer = signerClass.getConstructor(PrivateKey.class, byte[].class, Clock.class)
                .newInstance(privateKey, publicRaw, fixedClock);
            String thumbprint = (String) signerClass.getMethod("thumbprint").invoke(signer);
            check(EXPECTED_THUMBPRINT.equals(thumbprint), "RFC 7638 public-key thumbprint");

            Method proofMethod = signerClass.getMethod("proof", String.class, URI.class, String.class);
            URI target = URI.create("https://AUTH.invalid:443/v1/blob/a?ignored=1#ignored");
            String token = "public-fixture-token";
            String proof = (String) proofMethod.invoke(signer, "GET", target, token);
            String[] parts = proof.split("\\.", -1);
            check(parts.length == 3, "compact signed proof");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            check(verifier.verify(URL_DECODER.decode(parts[2])), "Ed25519 signature verifies independently");
            JsonObject header = decode(parts[0]);
            JsonObject claims = decode(parts[1]);
            check("dpop+jwt".equals(header.get("typ").getAsString()) && "EdDSA".equals(header.get("alg").getAsString()),
                "typed asymmetric proof");
            JsonObject jwk = header.getAsJsonObject("jwk");
            check(jwk.keySet().equals(java.util.Set.of("crv", "kty", "x"))
                && "Ed25519".equals(jwk.get("crv").getAsString())
                && "OKP".equals(jwk.get("kty").getAsString())
                && URL_ENCODER.encodeToString(publicRaw).equals(jwk.get("x").getAsString()), "public-only Ed25519 JWK");
            check("GET".equals(claims.get("htm").getAsString())
                && "https://auth.invalid/v1/blob/a".equals(claims.get("htu").getAsString())
                && claims.get("iat").getAsLong() == 1700000000L, "method, normalized target, and issue time");
            String ath = URL_ENCODER.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
            check(ath.equals(claims.get("ath").getAsString()), "exact access token hash");
            byte[] firstJti = URL_DECODER.decode(claims.get("jti").getAsString());
            check(firstJti.length >= 16, "random replay identifier length");
            String second = (String) proofMethod.invoke(signer, "GET", target, token);
            check(!claims.get("jti").getAsString().equals(decode(second.split("\\.")[1]).get("jti").getAsString()),
                "fresh proof ID for each request");
            String completion = (String) proofMethod.invoke(signer, "POST", target, null);
            check(!decode(completion.split("\\.")[1]).has("ath"), "tokenless completion proof");

            Class<?> targetJsonObject = Class.forName("com.google.gson.JsonObject", true, loader);
            Method requireBinding = signerClass.getMethod("requireBinding", targetJsonObject);
            requireBinding.invoke(signer, targetJson(loader, "{\"cnf\":{\"jkt\":\"" + thumbprint + "\"}}"));
            check(true, "matching certificate binding");
            try {
                requireBinding.invoke(signer, targetJson(loader, "{\"cnf\":{\"jkt\":\"wrong\"}}"));
                throw new AssertionError("wrong certificate binding accepted");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof GeneralSecurityException, "wrong certificate binding rejected");
            }

            if (args[3].equals("bridge")) {
                Class<?> bridgeClass = Class.forName(mappedName(BRIDGE, mapping), false, loader);
                check(bridgeClass.getProtectionDomain().getCodeSource().getLocation().toURI().equals(artifact.toUri()),
                    "gateway loaded from supplied artifact");
                check(bridgeClass.getMethod("dpopThumbprint").getReturnType() == String.class,
                    "public gateway thumbprint ABI survived protection");
                check(bridgeClass.getMethod("dpopProof", String.class, String.class, String.class).getReturnType() == String.class,
                    "public gateway proof ABI survived protection");
            }
        }
        System.out.println("PASS protected DPoP artifact: " + checks + " checks; " + artifact.getFileName());
    }

    private static String mappedName(String original, Path mapping) throws Exception {
        if (mapping == null) return original.replace('/', '.');
        JsonObject classes = JsonParser.parseString(Files.readString(mapping, StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("classes");
        String name = classes.has(original) ? classes.get(original).getAsString() : original;
        return name.replace('/', '.');
    }

    private static Object targetJson(ClassLoader loader, String json) throws Exception {
        Class<?> parser = Class.forName("com.google.gson.JsonParser", true, loader);
        Object value = parser.getMethod("parseString", String.class).invoke(null, json);
        return value.getClass().getMethod("getAsJsonObject").invoke(value);
    }

    private static JsonObject decode(String part) {
        return JsonParser.parseString(new String(URL_DECODER.decode(part), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] output = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, output, a.length, b.length);
        return output;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
