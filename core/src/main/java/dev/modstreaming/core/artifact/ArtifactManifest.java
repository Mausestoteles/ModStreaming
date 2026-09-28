package dev.modstreaming.core.artifact;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.Ed25519;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Signed metadata of a streamed artifact.
 *
 * <p>The manifest is serialized once by the packager; the exact bytes are stored in the container,
 * signed with Ed25519 and used as AES-GCM additional authenticated data. Consumers therefore never
 * need canonical JSON: they verify the stored bytes and parse them afterwards.</p>
 *
 * @param formatVersion        container format version
 * @param artifactId           unique id of this build (UUID)
 * @param coordinates          project/version/minecraft/loader
 * @param createdAt            build timestamp
 * @param payloadSha256        hex SHA-256 of the encrypted payload (checked before decryption)
 * @param contentSha256        hex SHA-256 of the plaintext streamed jar (checked after decryption)
 * @param contentSize          plaintext size in bytes
 * @param publicKeyFingerprint hex SHA-256 of the raw signing public key
 * @param services             service bindings to register after loading
 * @param frameworkVersion     ModStreaming version that produced the artifact
 */
public record ArtifactManifest(int formatVersion,
                               String artifactId,
                               ArtifactCoordinates coordinates,
                               Instant createdAt,
                               String payloadSha256,
                               String contentSha256,
                               long contentSize,
                               String publicKeyFingerprint,
                               List<ServiceBinding> services,
                               String frameworkVersion) {

    public static final int FORMAT_VERSION = 1;

    public ArtifactManifest {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(payloadSha256, "payloadSha256");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(publicKeyFingerprint, "publicKeyFingerprint");
        services = services == null ? List.of() : List.copyOf(services);
        frameworkVersion = frameworkVersion == null ? "unknown" : frameworkVersion;
    }

    public String projectId() {
        return coordinates.projectId();
    }

    /** Serializes to the JSON bytes that get signed. */
    public byte[] toJsonBytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("formatVersion", formatVersion);
        o.addProperty("artifactId", artifactId);
        o.addProperty("projectId", coordinates.projectId());
        o.addProperty("modVersion", coordinates.modVersion());
        o.addProperty("minecraftVersion", coordinates.minecraftVersion());
        o.addProperty("loader", coordinates.loader());
        o.addProperty("createdAt", createdAt.toString());
        JsonObject enc = new JsonObject();
        enc.addProperty("algorithm", AesGcm.ALGORITHM);
        enc.addProperty("keyBits", AesGcm.KEY_BYTES * 8);
        enc.addProperty("nonceBytes", AesGcm.NONCE_BYTES);
        enc.addProperty("tagBits", AesGcm.TAG_BITS);
        o.add("encryption", enc);
        JsonObject sig = new JsonObject();
        sig.addProperty("algorithm", Ed25519.ALGORITHM);
        sig.addProperty("publicKeyFingerprint", publicKeyFingerprint);
        o.add("signature", sig);
        o.addProperty("payloadSha256", payloadSha256);
        o.addProperty("contentSha256", contentSha256);
        o.addProperty("contentSize", contentSize);
        o.add("services", ServiceMetadata.toJsonArray(services));
        o.addProperty("frameworkVersion", frameworkVersion);
        return new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }

    public static ArtifactManifest fromJsonBytes(byte[] bytes) throws ArtifactException {
        return fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    public static ArtifactManifest fromJson(String json) throws ArtifactException {
        JsonObject o;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new ArtifactException("manifest must be a JSON object");
            }
            o = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new ArtifactException("manifest is not valid JSON");
        }
        try {
            int formatVersion = o.get("formatVersion").getAsInt();
            if (formatVersion != FORMAT_VERSION) {
                throw new ArtifactException("unsupported artifact format version " + formatVersion);
            }
            JsonObject enc = o.getAsJsonObject("encryption");
            if (enc == null || !AesGcm.ALGORITHM.equals(str(enc, "algorithm"))
                    || enc.get("keyBits").getAsInt() != AesGcm.KEY_BYTES * 8
                    || enc.get("nonceBytes").getAsInt() != AesGcm.NONCE_BYTES
                    || enc.get("tagBits").getAsInt() != AesGcm.TAG_BITS) {
                throw new ArtifactException("unsupported encryption parameters in manifest");
            }
            JsonObject sig = o.getAsJsonObject("signature");
            if (sig == null || !Ed25519.ALGORITHM.equals(str(sig, "algorithm"))) {
                throw new ArtifactException("unsupported signature algorithm in manifest");
            }
            ArtifactCoordinates coordinates = new ArtifactCoordinates(str(o, "projectId"), str(o, "modVersion"),
                    str(o, "minecraftVersion"), str(o, "loader"));
            return new ArtifactManifest(
                    formatVersion,
                    str(o, "artifactId"),
                    coordinates,
                    Instant.parse(str(o, "createdAt")),
                    str(o, "payloadSha256"),
                    str(o, "contentSha256"),
                    o.get("contentSize").getAsLong(),
                    str(sig, "publicKeyFingerprint"),
                    ServiceMetadata.fromJsonArray(o.getAsJsonArray("services")),
                    o.has("frameworkVersion") ? str(o, "frameworkVersion") : "unknown");
        } catch (ArtifactException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ArtifactException("manifest is incomplete or malformed: " + e.getMessage());
        }
    }

    /** Read-only view for {@link dev.modstreaming.api.verify.ArtifactValidator}s. */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formatVersion", formatVersion);
        m.put("artifactId", artifactId);
        m.put("projectId", coordinates.projectId());
        m.put("modVersion", coordinates.modVersion());
        m.put("minecraftVersion", coordinates.minecraftVersion());
        m.put("loader", coordinates.loader());
        m.put("createdAt", createdAt);
        m.put("payloadSha256", payloadSha256);
        m.put("contentSha256", contentSha256);
        m.put("contentSize", contentSize);
        m.put("publicKeyFingerprint", publicKeyFingerprint);
        m.put("services", services);
        m.put("frameworkVersion", frameworkVersion);
        return Collections.unmodifiableMap(m);
    }

    private static String str(JsonObject o, String field) throws ArtifactException {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            throw new ArtifactException("manifest is missing '" + field + "'");
        }
        return e.getAsString();
    }
}
