package dev.modstreaming.core.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.modstreaming.api.auth.VerificationDecision;
import dev.modstreaming.api.auth.VerificationRequest;
import dev.modstreaming.api.auth.VerificationResult;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * JSON encoding of the client/backend protocol (version 1).
 *
 * <pre>{@code
 * POST {endpoint}/api/v1/verify
 * {
 *   "protocolVersion": 1,
 *   "projectId": "example-mod",
 *   "artifact": {"modVersion": "1.0.0", "minecraftVersion": "1.21.1", "loader": "fabric"},
 *   "credentials": {"token": "..."},
 *   "client": {"frameworkVersion": "0.1.0", "javaVersion": "21", "os": "linux"}
 * }
 *
 * 200 {"decision": "ALLOW", "downloadToken": "...", "key": "<base64>", "artifactId": "...",
 *      "expiresAt": "2026-01-01T00:00:00Z", "attributes": {}}
 * 403 {"decision": "DENY", "reason": "...", "attributes": {}}
 * }</pre>
 */
public final class JsonWire {

    public static final String VERIFY_PATH = "api/v1/verify";
    public static final String DOWNLOAD_PATH = "api/v1/artifacts/download";
    public static final String PUBLISH_PATH = "api/v1/publish";

    private static final Gson GSON = new Gson();

    private JsonWire() {
    }

    public static String encodeVerificationRequest(VerificationRequest request) {
        JsonObject o = new JsonObject();
        o.addProperty("protocolVersion", VerificationRequest.PROTOCOL_VERSION);
        o.addProperty("projectId", request.projectId());
        JsonObject artifact = new JsonObject();
        artifact.addProperty("modVersion", request.artifact().modVersion());
        artifact.addProperty("minecraftVersion", request.artifact().minecraftVersion());
        artifact.addProperty("loader", request.artifact().loader());
        o.add("artifact", artifact);
        o.add("credentials", stringMap(request.credentials()));
        o.add("client", stringMap(request.clientInfo()));
        return GSON.toJson(o);
    }

    /** Parses a verification response body. Returns empty if the body carries no decision. */
    public static Optional<VerificationResult> decodeVerificationResponse(String body) {
        JsonObject o = parseObject(body).orElse(null);
        if (o == null || !o.has("decision")) {
            return Optional.empty();
        }
        String decisionText = o.get("decision").getAsString().toUpperCase(Locale.ROOT);
        VerificationDecision decision;
        try {
            decision = VerificationDecision.valueOf(decisionText);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Map<String, String> attributes = readStringMap(o.get("attributes"));
        if (decision == VerificationDecision.DENY) {
            String reason = o.has("reason") && !o.get("reason").isJsonNull() ? o.get("reason").getAsString() : "denied";
            return Optional.of(VerificationResult.deny(reason, attributes));
        }
        if (!o.has("downloadToken") || !o.has("key")) {
            throw new IllegalArgumentException("ALLOW response is missing downloadToken or key");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(o.get("key").getAsString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ALLOW response carries a malformed key");
        }
        Instant expiresAt = null;
        if (o.has("expiresAt") && !o.get("expiresAt").isJsonNull()) {
            try {
                expiresAt = Instant.parse(o.get("expiresAt").getAsString());
            } catch (RuntimeException ignored) {
                expiresAt = null;
            }
        }
        String artifactId = o.has("artifactId") && !o.get("artifactId").isJsonNull() ? o.get("artifactId").getAsString() : null;
        return Optional.of(VerificationResult.allow(o.get("downloadToken").getAsString(), key, artifactId, expiresAt, attributes));
    }

    public static String encodePublishRequest(byte[] container, byte[] key) {
        JsonObject o = new JsonObject();
        o.addProperty("protocolVersion", VerificationRequest.PROTOCOL_VERSION);
        o.addProperty("artifact", Base64.getEncoder().encodeToString(container));
        o.addProperty("key", Base64.getEncoder().encodeToString(key));
        return GSON.toJson(o);
    }

    public static Optional<JsonObject> parseObject(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    public static Map<String, String> readStringMap(JsonElement element) {
        Map<String, String> out = new LinkedHashMap<>();
        if (element != null && element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement v = entry.getValue();
                if (v.isJsonPrimitive()) {
                    out.put(entry.getKey(), v.getAsString());
                } else if (!v.isJsonNull()) {
                    out.put(entry.getKey(), v.toString());
                }
            }
        }
        return out;
    }

    private static JsonObject stringMap(Map<String, String> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }
}
