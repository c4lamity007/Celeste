package dev.c4lam.celeste.config;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.c4lam.celeste.auth.AuthBootstrap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class CloudConfigClient {
    record RemoteConfig(
        String id,
        String name,
        String author,
        long updatedAt,
        int downloads,
        List<String> modules,
        boolean owned
    ) {
    }

    private static final Gson GSON = new Gson();

    List<RemoteConfig> list() throws Exception {
        AuthBootstrap.CloudResponse response = AuthBootstrap.cloudRequest("GET", "/v1/configs", null, null);
        JsonObject root = json(response);
        JsonArray rawConfigs = root.getAsJsonArray("configs");
        if (rawConfigs == null) {
            throw new IOException("Cloud config list is invalid");
        }
        List<RemoteConfig> result = new ArrayList<>();
        for (JsonElement element : rawConfigs) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject raw = element.getAsJsonObject();
            String id = text(raw, "id");
            if (!id.matches("c[A-Za-z0-9_-]{16,40}")) {
                continue;
            }
            List<String> modules = new ArrayList<>();
            JsonArray rawModules = raw.getAsJsonArray("modules");
            if (rawModules != null) {
                for (JsonElement module : rawModules) {
                    if (module.isJsonPrimitive() && modules.size() < 128) {
                        String value = module.getAsString().strip();
                        if (!value.isEmpty() && value.length() <= 64) {
                            modules.add(value);
                        }
                    }
                }
            }
            result.add(new RemoteConfig(
                id,
                text(raw, "name"),
                optionalText(raw, "author"),
                number(raw, "updated_at"),
                (int) Math.max(0, number(raw, "downloads")),
                List.copyOf(modules),
                bool(raw, "owned")
            ));
        }
        return List.copyOf(result);
    }

    String publish(String name, byte[] image, List<String> modules) throws Exception {
        if (image == null || image.length == 0) {
            throw new IOException("Save the config before publishing it");
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("image", Base64.getUrlEncoder().withoutPadding().encodeToString(image));
        JsonArray rawModules = new JsonArray();
        modules.forEach(rawModules::add);
        body.add("modules", rawModules);
        AuthBootstrap.CloudResponse response = AuthBootstrap.cloudRequest(
            "POST",
            "/v1/configs",
            "application/json",
            GSON.toJson(body).getBytes(StandardCharsets.UTF_8)
        );
        JsonObject result = json(response);
        return text(result, "name");
    }

    byte[] download(String id) throws Exception {
        if (id == null || !id.matches("c[A-Za-z0-9_-]{16,40}")) {
            throw new IOException("Cloud config ID is invalid");
        }
        AuthBootstrap.CloudResponse response = AuthBootstrap.cloudRequest(
            "GET",
            "/v1/configs/" + id + "/image",
            null,
            null
        );
        if (!response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/png")) {
            throw new IOException("Cloud config did not return a PNG");
        }
        return response.body();
    }

    private static JsonObject json(AuthBootstrap.CloudResponse response) throws IOException {
        if (!response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new IOException("Cloud config service returned invalid data");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Cloud config service returned invalid data");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Cloud config service returned invalid data", exception);
        }
    }

    private static String text(JsonObject object, String name) throws IOException {
        String value = optionalText(object, name).strip();
        if (value.isEmpty()) {
            throw new IOException("Cloud config entry is missing " + name);
        }
        return value;
    }

    private static String optionalText(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? 0L : value.getAsLong();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }
}
