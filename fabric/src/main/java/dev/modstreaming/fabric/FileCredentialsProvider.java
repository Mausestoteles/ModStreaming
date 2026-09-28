package dev.modstreaming.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modstreaming.api.auth.CredentialsProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads credentials from {@code config/modstreaming/<projectId>.json}:
 *
 * <pre>{@code
 * { "credentials": { "licenseKey": "ABC-123", "customField": "..." } }
 * }</pre>
 *
 * <p>The file is re-read on every request so users can add a key without restarting. A missing
 * or malformed file yields an empty map (the backend then decides what that means).</p>
 */
public final class FileCredentialsProvider implements CredentialsProvider {

    private final Path file;

    public FileCredentialsProvider(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public static FileCredentialsProvider forProject(Path configDir, String projectId) {
        return new FileCredentialsProvider(configDir.resolve("modstreaming").resolve(projectId + ".json"));
    }

    public Path file() {
        return file;
    }

    @Override
    public Map<String, String> credentials() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static Map<String, String> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return Map.of();
        }
        JsonObject o = root.getAsJsonObject();
        JsonElement creds = o.has("credentials") ? o.get("credentials") : o;
        Map<String, String> out = new LinkedHashMap<>();
        if (creds.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : creds.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) {
                    out.put(e.getKey(), v.getAsString());
                }
            }
        }
        return out;
    }

    /** Writes a template file so users know where credentials go. Never overwrites. */
    public void writeTemplateIfMissing() {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\n  \"credentials\": {\n  }\n}\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best effort only
        }
    }
}
