package dev.modstreaming.api.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The generic request contract between client and backend.
 *
 * <pre>{@code
 * {
 *   "protocolVersion": 1,
 *   "projectId": "example-mod",
 *   "artifact": { "modVersion": "1.0.0", "minecraftVersion": "1.21.1", "loader": "fabric" },
 *   "credentials": { "token": "...", "customField": "..." },
 *   "client": { "frameworkVersion": "0.1.0", "javaVersion": "21", "os": "linux" }
 * }
 * }</pre>
 *
 * @param artifact    the artifact the client wants to load
 * @param credentials backend-defined credential fields (sensitive, never logged)
 * @param clientInfo  non-sensitive information about the client environment
 */
public record VerificationRequest(ArtifactCoordinates artifact,
                                  Map<String, String> credentials,
                                  Map<String, String> clientInfo) {

    /** Protocol version spoken by this API. */
    public static final int PROTOCOL_VERSION = 1;

    public VerificationRequest {
        Objects.requireNonNull(artifact, "artifact");
        credentials = copy(credentials);
        clientInfo = copy(clientInfo);
    }

    public VerificationRequest(ArtifactCoordinates artifact, Map<String, String> credentials) {
        this(artifact, credentials, Map.of());
    }

    public String projectId() {
        return artifact.projectId();
    }

    private static Map<String, String> copy(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** Never prints credentials. */
    @Override
    public String toString() {
        return "VerificationRequest[" + artifact.key() + ", credentials=" + credentials.size() + " field(s)]";
    }
}
