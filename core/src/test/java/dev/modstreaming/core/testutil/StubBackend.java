package dev.modstreaming.core.testutil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Minimal fake backend built on the JDK's HttpServer. Serves the verify and download endpoints
 * with configurable behaviour so client code can be tested without the real Ktor backend.
 */
public final class StubBackend implements AutoCloseable {

    public record Received(String path, String authorization, JsonObject body) {
    }

    private final HttpServer server;
    private volatile Function<JsonObject, Response> verifyHandler;
    private volatile byte[] artifact;
    private volatile String expectedToken = "download-token-1";
    private volatile int downloadStatus = 200;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public record Response(int status, String body) {
        public static Response allow(String token, byte[] key) {
            JsonObject o = new JsonObject();
            o.addProperty("decision", "ALLOW");
            o.addProperty("downloadToken", token);
            o.addProperty("key", Base64.getEncoder().encodeToString(key));
            o.addProperty("artifactId", "artifact-1");
            o.addProperty("expiresAt", "2099-01-01T00:00:00Z");
            JsonObject attrs = new JsonObject();
            attrs.addProperty("subject", "tester");
            o.add("attributes", attrs);
            return new Response(200, o.toString());
        }

        public static Response deny(String reason) {
            JsonObject o = new JsonObject();
            o.addProperty("decision", "DENY");
            o.addProperty("reason", reason);
            return new Response(403, o.toString());
        }

        public static Response error(int status, String body) {
            return new Response(status, body);
        }
    }

    public StubBackend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/verify", this::handleVerify);
        server.createContext("/api/v1/artifacts/download", this::handleDownload);
        server.start();
        verifyHandler = body -> Response.deny("not configured");
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubBackend onVerify(Function<JsonObject, Response> handler) {
        this.verifyHandler = handler;
        return this;
    }

    public StubBackend allowWith(byte[] key, byte[] artifactBytes) {
        this.artifact = artifactBytes;
        this.verifyHandler = body -> Response.allow(expectedToken, key);
        return this;
    }

    public StubBackend deny(String reason) {
        this.verifyHandler = body -> Response.deny(reason);
        return this;
    }

    public StubBackend artifact(byte[] bytes) {
        this.artifact = bytes;
        return this;
    }

    public StubBackend downloadStatus(int status) {
        this.downloadStatus = status;
        return this;
    }

    public String expectedToken() {
        return expectedToken;
    }

    public List<Received> received() {
        return received;
    }

    private void handleVerify(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        received.add(new Received("/api/v1/verify", exchange.getRequestHeaders().getFirst("Authorization"), json));
        Response response = verifyHandler.apply(json);
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        received.add(new Received("/api/v1/artifacts/download", auth, new JsonObject()));
        exchange.getRequestBody().readAllBytes();
        if (downloadStatus != 200) {
            exchange.sendResponseHeaders(downloadStatus, -1);
            exchange.close();
            return;
        }
        if (auth == null || !auth.equals("Bearer " + expectedToken) || artifact == null) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, artifact.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(artifact);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
