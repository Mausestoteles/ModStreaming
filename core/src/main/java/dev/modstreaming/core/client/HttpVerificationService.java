package dev.modstreaming.core.client;

import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.api.auth.VerificationRequest;
import dev.modstreaming.api.auth.VerificationResult;
import dev.modstreaming.api.auth.VerificationService;
import dev.modstreaming.core.config.StreamConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link VerificationService} over HTTP/JSON using {@link java.net.http.HttpClient}.
 *
 * <p>The backend is free to implement any authorization logic; this class only speaks the
 * request/response contract. Credentials are sent in the JSON body over TLS and never logged.</p>
 */
public final class HttpVerificationService implements VerificationService {

    private final HttpClient client;
    private final URI verifyUri;
    private final Duration timeout;

    public HttpVerificationService(StreamConfig config) {
        this(HttpSupport.newClient(config), config.resolve(JsonWire.VERIFY_PATH), config.requestTimeout());
    }

    public HttpVerificationService(HttpClient client, URI verifyUri, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.verifyUri = Objects.requireNonNull(verifyUri, "verifyUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public VerificationResult verify(VerificationRequest request) throws VerificationException {
        Objects.requireNonNull(request, "request");
        String body = JsonWire.encodeVerificationRequest(request);
        HttpRequest httpRequest = HttpSupport.request(verifyUri, timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpSupport.send(client, httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "verification");
        int status = response.statusCode();
        Optional<VerificationResult> decoded;
        try {
            decoded = JsonWire.decodeVerificationResponse(response.body());
        } catch (IllegalArgumentException e) {
            throw new VerificationException("verification response is malformed: " + e.getMessage(), null, false);
        }
        if (decoded.isPresent()) {
            VerificationResult result = decoded.get();
            if (result.isAllowed() && status != 200) {
                throw new VerificationException("backend returned ALLOW with unexpected HTTP status " + status, null, false);
            }
            return result;
        }
        if (HttpSupport.isServerError(status)) {
            throw new VerificationException("backend error during verification (HTTP " + status + ")", null, true);
        }
        if (status == 401 || status == 403) {
            return VerificationResult.deny("backend refused the request (HTTP " + status + ")");
        }
        if (status == 404) {
            throw new VerificationException("verification endpoint not found (HTTP 404)", null, false);
        }
        throw new VerificationException("unexpected verification response (HTTP " + status + ")", null, status >= 500);
    }
}
