package dev.modstreaming.core.client;

import dev.modstreaming.api.auth.ArtifactDownloader;
import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.api.auth.VerificationResult;
import dev.modstreaming.core.config.StreamConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Downloads the encrypted container with the download token from a successful verification.
 *
 * <pre>{@code GET {endpoint}/api/v1/artifacts/download  Authorization: Bearer <downloadToken>}</pre>
 */
public final class HttpArtifactDownloader implements ArtifactDownloader {

    private final HttpClient client;
    private final URI downloadUri;
    private final Duration timeout;
    private final long maxBytes;

    public HttpArtifactDownloader(StreamConfig config) {
        this(HttpSupport.newClient(config), config.resolve(JsonWire.DOWNLOAD_PATH), config.requestTimeout(),
                config.maxArtifactBytes());
    }

    public HttpArtifactDownloader(HttpClient client, URI downloadUri, Duration timeout, long maxBytes) {
        this.client = Objects.requireNonNull(client, "client");
        this.downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.maxBytes = maxBytes;
    }

    @Override
    public byte[] download(VerificationResult grant) throws VerificationException {
        Objects.requireNonNull(grant, "grant");
        if (!grant.isAllowed() || grant.downloadToken() == null) {
            throw new VerificationException("download requires an ALLOW grant", null, false);
        }
        HttpRequest request = HttpSupport.request(downloadUri, timeout)
                .header("Authorization", "Bearer " + grant.downloadToken())
                .GET()
                .build();
        HttpResponse<InputStream> response = HttpSupport.send(client, request,
                HttpResponse.BodyHandlers.ofInputStream(), "artifact download");
        int status = response.statusCode();
        try (InputStream in = response.body()) {
            if (status == 200) {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > maxBytes) {
                    throw new VerificationException("artifact exceeds the configured size limit", null, false);
                }
                byte[] data = HttpSupport.readLimited(in, maxBytes);
                if (data.length == 0) {
                    throw new VerificationException("artifact download returned an empty body", null, true);
                }
                return data;
            }
            // drain to keep the connection reusable, ignore body content
            in.readAllBytes();
        } catch (IOException e) {
            throw new VerificationException("artifact download failed: " + e.getMessage(), e, true);
        }
        if (status == 401 || status == 403) {
            throw new VerificationException("download token was rejected (HTTP " + status + ")", null, false);
        }
        if (status == 404) {
            throw new VerificationException("artifact not found on the backend (HTTP 404)", null, false);
        }
        throw new VerificationException("unexpected download response (HTTP " + status + ")", null, HttpSupport.isServerError(status));
    }
}
