package dev.modstreaming.core.publish;

import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.core.client.HttpSupport;
import dev.modstreaming.core.client.JsonWire;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Uploads a packaged artifact (container + key) to the backend. Used by the Gradle task
 * {@code publishStreamed}; never runs as part of {@code build}.
 *
 * <pre>{@code
 * POST {endpoint}/api/v1/publish   Authorization: Bearer <publisherToken>
 * {"protocolVersion": 1, "artifact": "<base64 container>", "key": "<base64 key>"}
 * }</pre>
 */
public final class ArtifactPublisher {

    private final HttpClient client;
    private final URI publishUri;
    private final Duration timeout;

    public ArtifactPublisher(URI endpoint) {
        this(HttpSupport.newClient(Duration.ofSeconds(15)), resolve(endpoint), Duration.ofMinutes(5));
    }

    public ArtifactPublisher(HttpClient client, URI publishUri, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.publishUri = Objects.requireNonNull(publishUri, "publishUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public static URI resolve(URI endpoint) {
        String base = endpoint.toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return URI.create(base + JsonWire.PUBLISH_PATH);
    }

    public PublishResult publish(byte[] container, byte[] key, String publisherToken) throws VerificationException {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(key, "key");
        if (publisherToken == null || publisherToken.isBlank()) {
            throw new VerificationException("publisher token is missing", null, false);
        }
        String body = JsonWire.encodePublishRequest(container, key);
        HttpRequest request = HttpSupport.request(publishUri, timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + publisherToken.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpSupport.send(client, request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "publish");
        int status = response.statusCode();
        if (status == 200 || status == 201) {
            var json = JsonWire.parseObject(response.body());
            String artifactId = json.filter(o -> o.has("artifactId")).map(o -> o.get("artifactId").getAsString()).orElse(null);
            boolean replaced = json.filter(o -> o.has("replaced")).map(o -> o.get("replaced").getAsBoolean()).orElse(false);
            return new PublishResult(artifactId, replaced, json.map(o -> JsonWire.readStringMap(o.get("attributes")))
                    .orElse(java.util.Map.of()));
        }
        String detail = JsonWire.parseObject(response.body())
                .filter(o -> o.has("error"))
                .map(o -> ": " + o.get("error").getAsString())
                .orElse("");
        if (status == 401 || status == 403) {
            throw new VerificationException("publisher authentication failed (HTTP " + status + ")" + detail, null, false);
        }
        throw new VerificationException("publish rejected (HTTP " + status + ")" + detail, null, HttpSupport.isServerError(status));
    }
}
