package dev.celestebeta.linkage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

record Request(long pid, long startedAt, String nonce, Path workspace, Path output, Path bridge, String bridgeHash) {
    String encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(1);
            data.writeLong(pid);
            data.writeLong(startedAt);
            for (String value : new String[] {nonce, workspace.toString(), output.toString(), bridge.toString(), bridgeHash}) {
                data.writeUTF(value);
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    static Request decode(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 24_000) throw new IOException("Invalid probe request length");
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded)))) {
            if (data.readInt() != 1) throw new IOException("Unsupported probe request");
            Request request = new Request(data.readLong(), data.readLong(), data.readUTF(), Path.of(data.readUTF()),
                Path.of(data.readUTF()), Path.of(data.readUTF()), data.readUTF());
            if (data.available() != 0 || request.pid <= 0 || request.startedAt <= 0
                    || !request.nonce.matches("[a-f0-9]{48}") || !request.bridgeHash.matches("[a-f0-9]{64}")) {
                throw new IOException("Invalid probe request fields");
            }
            return request;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid probe request encoding", failure);
        }
    }
}
