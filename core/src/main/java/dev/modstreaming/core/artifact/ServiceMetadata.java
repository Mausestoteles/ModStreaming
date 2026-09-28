package dev.modstreaming.core.artifact;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modstreaming.api.ServiceBinding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads and writes {@code META-INF/modstreaming/services.json}, the service binding list produced
 * by the Gradle plugin and copied into the signed manifest.
 */
public final class ServiceMetadata {

    public static final int FORMAT_VERSION = 1;

    private ServiceMetadata() {
    }

    public static String toJson(List<ServiceBinding> bindings) {
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", FORMAT_VERSION);
        o.add("services", toJsonArray(bindings));
        return new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }

    public static JsonArray toJsonArray(List<ServiceBinding> bindings) {
        JsonArray arr = new JsonArray();
        for (ServiceBinding b : bindings) {
            JsonObject e = new JsonObject();
            e.addProperty("service", b.service());
            e.addProperty("implementation", b.implementation());
            arr.add(e);
        }
        return arr;
    }

    public static List<ServiceBinding> fromJson(byte[] json) {
        return fromJson(new String(json, StandardCharsets.UTF_8));
    }

    public static List<ServiceBinding> fromJson(String json) {
        Objects.requireNonNull(json, "json");
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("services.json must be a JSON object");
        }
        JsonObject o = parsed.getAsJsonObject();
        int version = o.has("formatVersion") ? o.get("formatVersion").getAsInt() : FORMAT_VERSION;
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported services.json format version " + version);
        }
        return fromJsonArray(o.getAsJsonArray("services"));
    }

    public static List<ServiceBinding> fromJsonArray(JsonArray arr) {
        if (arr == null) {
            return List.of();
        }
        List<ServiceBinding> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            out.add(new ServiceBinding(o.get("service").getAsString(), o.get("implementation").getAsString()));
        }
        return Collections.unmodifiableList(out);
    }
}
