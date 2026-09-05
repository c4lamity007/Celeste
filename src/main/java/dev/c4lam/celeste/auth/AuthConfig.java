package dev.c4lam.celeste.auth;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

record AuthConfig(URI baseUri, byte[] serverKey, String releaseId, String releaseDigest, String clientVersion) {
    private static final String RESOURCE = "/META-INF/.r/4d7a9c2e";
    private static final int MAGIC = 0x6DCA24F1;
    private static final int FORMAT_VERSION = 1;
    private static final int SALT_LENGTH = 16;
    private static final int TAG_LENGTH = 16;
    private static final int MAX_CIPHERTEXT_LENGTH = 4_096;
    private static final byte[] CONTEXT = {
        99, 115, 116, 45, 114, 101, 108, 101, 97, 115, 101, 45, 118, 49
    };
    private static final byte[] PACKING_KEY = {
        (byte) 0x8e, 0x25, (byte) 0xa9, 0x73, 0x4d, (byte) 0xc1, 0x5f, (byte) 0xb8,
        0x37, (byte) 0xe4, 0x62, 0x0b, (byte) 0xd6, 0x79, 0x14, (byte) 0xac,
        0x51, (byte) 0xf2, 0x06, (byte) 0x9b, 0x3c, 0x68, (byte) 0xdd, 0x42,
        (byte) 0xb1, 0x0e, 0x75, (byte) 0xca, 0x29, (byte) 0x93, 0x5a, (byte) 0xef
    };

    static AuthConfig load() throws IOException {
        byte[] packed;
        try (InputStream input = AuthConfig.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Authentication configuration is missing");
            }
            packed = input.readAllBytes();
        }

        byte[] clear = unpack(packed);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clear))) {
            String baseText = readText(input, 2_048);
            byte[] key = input.readNBytes(32);
            if (key.length != 32) {
                throw new IOException("Pinned server key is invalid");
            }
            String release = readText(input, 256);
            byte[] digestBytes = input.readNBytes(32);
            if (digestBytes.length != 32) {
                throw new IOException("Release digest is invalid");
            }
            String version = readText(input, 128);
            if (input.available() != 0) {
                throw new IOException("Authentication configuration contains trailing data");
            }

            URI base;
            try {
                base = URI.create(baseText);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Authentication endpoint is invalid", exception);
            }
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null) {
                throw new IOException("Authentication endpoint must use HTTPS");
            }
            return new AuthConfig(base, key, release, Crypto.hex(digestBytes), version);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] unpack(byte[] packed) throws IOException {
        int fixedLength = Integer.BYTES + 1 + SALT_LENGTH + Integer.BYTES + TAG_LENGTH;
        if (packed.length < fixedLength) {
            throw new IOException("Authentication configuration is truncated");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packed))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) {
                throw new IOException("Authentication configuration format is invalid");
            }
            byte[] salt = input.readNBytes(SALT_LENGTH);
            int ciphertextLength = input.readInt();
            if (salt.length != SALT_LENGTH
                || ciphertextLength <= 0
                || ciphertextLength > MAX_CIPHERTEXT_LENGTH
                || packed.length != fixedLength + ciphertextLength) {
                throw new IOException("Authentication configuration length is invalid");
            }
            byte[] ciphertext = input.readNBytes(ciphertextLength);
            byte[] suppliedTag = input.readNBytes(TAG_LENGTH);
            byte[] expectedTag = authenticationTag(salt, ciphertext);
            if (!MessageDigest.isEqual(suppliedTag, expectedTag)) {
                throw new IOException("Authentication configuration integrity check failed");
            }
            return xorStream(ciphertext, salt);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Authentication configuration could not be decoded", exception);
        }
    }

    private static byte[] authenticationTag(byte[] salt, byte[] ciphertext) throws GeneralSecurityException {
        Mac mac = mac();
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(MAGIC).array());
        mac.update((byte) FORMAT_VERSION);
        mac.update(salt);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(ciphertext.length).array());
        mac.update(ciphertext);
        return Arrays.copyOf(mac.doFinal(), TAG_LENGTH);
    }

    private static byte[] xorStream(byte[] input, byte[] salt) throws GeneralSecurityException {
        byte[] output = new byte[input.length];
        int offset = 0;
        int counter = 0;
        while (offset < input.length) {
            Mac mac = mac();
            mac.update(CONTEXT);
            mac.update(salt);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(counter++).array());
            byte[] block = mac.doFinal();
            int length = Math.min(block.length, input.length - offset);
            for (int index = 0; index < length; index++) {
                output[offset + index] = (byte) (input[offset + index] ^ block[index]);
            }
            offset += length;
        }
        return output;
    }

    private static Mac mac() throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PACKING_KEY, "HmacSHA256"));
        return mac;
    }

    private static String readText(DataInputStream input, int maximumLength) throws IOException {
        int length = input.readUnsignedShort();
        if (length <= 0 || length > maximumLength) {
            throw new IOException("Authentication configuration text length is invalid");
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new IOException("Authentication configuration is truncated");
        }
        String value = new String(encoded, StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            throw new IOException("Authentication configuration contains an empty value");
        }
        return value;
    }
}
