package dev.modstreaming.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.crypto.Ed25519;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Public, non-secret configuration embedded into the public mod jar at
 * {@code META-INF/modstreaming/stream-config.json}.
 *
 * <p>It only contains information that is safe to ship to every player: the backend endpoint,
 * the project id, the artifact coordinates and the Ed25519 <em>public</em> key used to verify
 * artifact signatures.</p>
 */
public final class StreamConfig {

    public static final int CONFIG_VERSION = 1;

    private final URI endpoint;
    private final String publicKey;
    private final ArtifactCoordinates coordinates;
    private final List<String> services;
    private final LoadMode loadMode;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration blockingTimeout;
    private final boolean allowInsecureEndpoint;
    private final long maxArtifactBytes;

    private StreamConfig(Builder b) {
        this.endpoint = normalizeEndpoint(b.endpoint, b.allowInsecureEndpoint);
        this.publicKey = validatePublicKey(b.publicKey);
        this.coordinates = new ArtifactCoordinates(b.projectId, b.modVersion, b.minecraftVersion, b.loader);
        this.services = Collections.unmodifiableList(new ArrayList<>(b.services));
        this.loadMode = b.loadMode;
        this.connectTimeout = requirePositive(b.connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(b.requestTimeout, "requestTimeout");
        this.blockingTimeout = requirePositive(b.blockingTimeout, "blockingTimeout");
        this.allowInsecureEndpoint = b.allowInsecureEndpoint;
        if (b.maxArtifactBytes <= 0) {
            throw new IllegalArgumentException("maxArtifactBytes must be positive");
        }
        this.maxArtifactBytes = b.maxArtifactBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Parses the JSON representation. */
    public static StreamConfig fromJson(String json) {
        Objects.requireNonNull(json, "json");
        JsonObject o;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("stream config must be a JSON object");
            }
            o = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("stream config is not valid JSON: " + e.getMessage(), e);
        }
        int version = o.has("configVersion") ? o.get("configVersion").getAsInt() : CONFIG_VERSION;
        if (version != CONFIG_VERSION) {
            throw new IllegalArgumentException("unsupported stream config version " + version);
        }
        Builder b = builder()
                .endpoint(requireString(o, "endpoint"))
                .projectId(requireString(o, "projectId"))
                .publicKey(requireString(o, "publicKey"))
                .modVersion(requireString(o, "modVersion"))
                .minecraftVersion(requireString(o, "minecraftVersion"))
                .loader(optionalString(o, "loader").orElse("fabric"));
        if (o.has("services") && o.get("services").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("services")) {
                b.service(e.getAsString());
            }
        }
        optionalString(o, "loadMode").ifPresent(m -> b.loadMode(LoadMode.valueOf(m.toUpperCase(Locale.ROOT))));
        if (o.has("connectTimeoutMillis")) {
            b.connectTimeout(Duration.ofMillis(o.get("connectTimeoutMillis").getAsLong()));
        }
        if (o.has("requestTimeoutMillis")) {
            b.requestTimeout(Duration.ofMillis(o.get("requestTimeoutMillis").getAsLong()));
        }
        if (o.has("blockingTimeoutMillis")) {
            b.blockingTimeout(Duration.ofMillis(o.get("blockingTimeoutMillis").getAsLong()));
        }
        if (o.has("allowInsecureEndpoint")) {
            b.allowInsecureEndpoint(o.get("allowInsecureEndpoint").getAsBoolean());
        }
        if (o.has("maxArtifactBytes")) {
            b.maxArtifactBytes(o.get("maxArtifactBytes").getAsLong());
        }
        return b.build();
    }

    /** Reads the embedded config resource from {@code loader}, if present. */
    public static Optional<StreamConfig> fromResource(ClassLoader loader) throws IOException {
        return fromResource(loader, ModStreaming.CONFIG_RESOURCE);
    }

    public static Optional<StreamConfig> fromResource(ClassLoader loader, String resource) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    public static StreamConfig fromStream(InputStream in) throws IOException {
        return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Serializes the configuration to JSON (the format written by the Gradle plugin). */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("configVersion", CONFIG_VERSION);
        o.addProperty("endpoint", endpoint.toString());
        o.addProperty("projectId", coordinates.projectId());
        o.addProperty("publicKey", publicKey);
        o.addProperty("modVersion", coordinates.modVersion());
        o.addProperty("minecraftVersion", coordinates.minecraftVersion());
        o.addProperty("loader", coordinates.loader());
        JsonArray arr = new JsonArray();
        services.forEach(arr::add);
        o.add("services", arr);
        o.addProperty("loadMode", loadMode.name());
        o.addProperty("connectTimeoutMillis", connectTimeout.toMillis());
        o.addProperty("requestTimeoutMillis", requestTimeout.toMillis());
        o.addProperty("blockingTimeoutMillis", blockingTimeout.toMillis());
        o.addProperty("allowInsecureEndpoint", allowInsecureEndpoint);
        o.addProperty("maxArtifactBytes", maxArtifactBytes);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }

    public URI endpoint() {
        return endpoint;
    }

    public String projectId() {
        return coordinates.projectId();
    }

    /** Base64 encoded Ed25519 public key (raw 32 bytes or X.509 SubjectPublicKeyInfo). */
    public String publicKey() {
        return publicKey;
    }

    public PublicKey decodedPublicKey() {
        return Ed25519.decodePublicKey(publicKey);
    }

    public ArtifactCoordinates coordinates() {
        return coordinates;
    }

    /** Service interfaces the public jar expects to be provided by the streamed artifact. */
    public List<String> services() {
        return services;
    }

    public LoadMode loadMode() {
        return loadMode;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration blockingTimeout() {
        return blockingTimeout;
    }

    public boolean allowInsecureEndpoint() {
        return allowInsecureEndpoint;
    }

    public long maxArtifactBytes() {
        return maxArtifactBytes;
    }

    /** Resolves an API path such as {@code api/v1/verify} against the endpoint. */
    public URI resolve(String path) {
        String base = endpoint.toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        String p = path.startsWith("/") ? path.substring(1) : path;
        return URI.create(base + p);
    }

    @Override
    public String toString() {
        return "StreamConfig[" + coordinates.key() + " @ " + endpoint + "]";
    }

    private static URI normalizeEndpoint(String endpoint, boolean allowInsecure) {
        Objects.requireNonNull(endpoint, "endpoint");
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("endpoint is not a valid URI: " + endpoint, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("endpoint must be an absolute URL with a host: " + endpoint);
        }
        if (!scheme.equals("https")) {
            boolean loopback = uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1")
                    || uri.getHost().equals("[::1]");
            if (!scheme.equals("http") || !(loopback || allowInsecure)) {
                throw new IllegalArgumentException("endpoint must use https (http is only allowed for "
                        + "loopback addresses or with allowInsecureEndpoint=true): " + endpoint);
            }
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("endpoint must not contain a query or fragment: " + endpoint);
        }
        return uri;
    }

    private static String validatePublicKey(String key) {
        Objects.requireNonNull(key, "publicKey");
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("publicKey must not be empty");
        }
        Ed25519.decodePublicKey(trimmed); // throws on malformed keys
        return trimmed;
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return d;
    }

    private static String requireString(JsonObject o, String field) {
        return optionalString(o, field)
                .orElseThrow(() -> new IllegalArgumentException("stream config is missing '" + field + "'"));
    }

    private static Optional<String> optionalString(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull()) {
            return Optional.empty();
        }
        String s = e.getAsString();
        return s.isBlank() ? Optional.empty() : Optional.of(s.trim());
    }

    /** Builder for {@link StreamConfig}. */
    public static final class Builder {
        private String endpoint;
        private String projectId;
        private String publicKey;
        private String modVersion;
        private String minecraftVersion;
        private String loader = "fabric";
        private final List<String> services = new ArrayList<>();
        private LoadMode loadMode = LoadMode.ASYNC;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration blockingTimeout = Duration.ofSeconds(20);
        private boolean allowInsecureEndpoint;
        private long maxArtifactBytes = 64L * 1024 * 1024;

        private Builder() {
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Builder modVersion(String modVersion) {
            this.modVersion = modVersion;
            return this;
        }

        public Builder minecraftVersion(String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
            return this;
        }

        public Builder loader(String loader) {
            this.loader = loader;
            return this;
        }

        public Builder coordinates(ArtifactCoordinates c) {
            return projectId(c.projectId()).modVersion(c.modVersion())
                    .minecraftVersion(c.minecraftVersion()).loader(c.loader());
        }

        public Builder service(String serviceInterface) {
            this.services.add(Objects.requireNonNull(serviceInterface, "serviceInterface"));
            return this;
        }

        public Builder services(List<String> serviceInterfaces) {
            this.services.clear();
            this.services.addAll(serviceInterfaces);
            return this;
        }

        public Builder loadMode(LoadMode loadMode) {
            this.loadMode = Objects.requireNonNull(loadMode, "loadMode");
            return this;
        }

        public Builder connectTimeout(Duration d) {
            this.connectTimeout = d;
            return this;
        }

        public Builder requestTimeout(Duration d) {
            this.requestTimeout = d;
            return this;
        }

        public Builder blockingTimeout(Duration d) {
            this.blockingTimeout = d;
            return this;
        }

        public Builder allowInsecureEndpoint(boolean allow) {
            this.allowInsecureEndpoint = allow;
            return this;
        }

        public Builder maxArtifactBytes(long max) {
            this.maxArtifactBytes = max;
            return this;
        }

        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}
