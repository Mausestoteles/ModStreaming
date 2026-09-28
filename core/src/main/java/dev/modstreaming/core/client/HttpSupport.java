package dev.modstreaming.core.client;

import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.config.StreamConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared {@link HttpClient} construction and response handling. */
public final class HttpSupport {

    public static final String USER_AGENT = "ModStreaming/" + ModStreaming.VERSION;

    private HttpSupport() {
    }

    public static HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static HttpClient newClient(StreamConfig config) {
        return newClient(config.connectTimeout());
    }

    public static HttpRequest.Builder request(java.net.URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/octet-stream");
    }

    /** Sends and maps transport failures to {@link VerificationException} without leaking headers. */
    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                          HttpResponse.BodyHandler<T> handler, String what) throws VerificationException {
        try {
            return client.send(request, handler);
        } catch (IOException e) {
            throw new VerificationException(what + " failed: backend unreachable (" + e.getClass().getSimpleName() + ")", e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerificationException(what + " was interrupted", e, true);
        }
    }

    /** Reads at most {@code limit} bytes; fails if the body is larger. */
    public static byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("response exceeds the limit of " + limit + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static boolean isServerError(int status) {
        return status >= 500;
    }
}
