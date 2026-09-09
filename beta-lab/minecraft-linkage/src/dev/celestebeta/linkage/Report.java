package dev.celestebeta.linkage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class Report {
    private Report() { }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static Path checkedOutput(Request request) throws IOException {
        Path workspace = request.workspace().toRealPath();
        Path output = request.output().toRealPath();
        if (output.equals(workspace) || !output.startsWith(workspace) || !Files.isDirectory(output)) {
            throw new IOException("Probe output must be a dedicated directory inside the explicit workspace");
        }
        return output;
    }

    static void write(Path output, Map<String, Object> report) throws IOException {
        Files.writeString(output.resolve("report.json"), json(report) + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) return list.stream().map(Report::json).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
}
