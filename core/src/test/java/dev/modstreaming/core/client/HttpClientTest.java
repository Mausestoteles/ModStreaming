package dev.modstreaming.core.client;

import dev.modstreaming.api.auth.VerificationDecision;
import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.api.auth.VerificationRequest;
import dev.modstreaming.api.auth.VerificationResult;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.testutil.StubBackend;
import dev.modstreaming.core.testutil.TestArtifacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpClientTest {

    private StubBackend backend;
    private StreamConfig config;
    private final KeyPair keys = Ed25519.generateKeyPair();

    @BeforeEach
    void start() throws Exception {
        backend = new StubBackend();
        config = TestArtifacts.config(keys, backend.endpoint());
    }

    @AfterEach
    void stop() {
        backend.close();
    }

    private VerificationRequest request() {
        return new VerificationRequest(TestArtifacts.COORDINATES, Map.of("token", "abc", "customField", "x"),
                ClientInfo.collect("fabric"));
    }

    @Test
    void allowResponseIsParsedAndRequestCarriesContract() throws Exception {
        byte[] key = new byte[32];
        key[0] = 7;
        backend.allowWith(key, new byte[] {1});
        VerificationResult result = new HttpVerificationService(config).verify(request());
        assertEquals(VerificationDecision.ALLOW, result.decision());
        assertEquals(backend.expectedToken(), result.downloadToken());
        assertArrayEquals(key, result.artifactKey());
        assertEquals("artifact-1", result.artifactId());
        assertEquals("tester", result.attributes().get("subject"));
        assertNotNull(result.expiresAt());

        StubBackend.Received received = backend.received().get(0);
        assertEquals("test-mod", received.body().get("projectId").getAsString());
        assertEquals(1, received.body().get("protocolVersion").getAsInt());
        assertEquals("1.21.1", received.body().getAsJsonObject("artifact").get("minecraftVersion").getAsString());
        assertEquals("abc", received.body().getAsJsonObject("credentials").get("token").getAsString());
        assertEquals("x", received.body().getAsJsonObject("credentials").get("customField").getAsString());
        assertTrue(received.body().getAsJsonObject("client").has("frameworkVersion"));
        assertFalse(result.toString().contains(backend.expectedToken()), "toString must not leak the token");
    }

    @Test
    void denyResponseIsNotAnException() throws Exception {
        backend.deny("license expired");
        VerificationResult result = new HttpVerificationService(config).verify(request());
        assertEquals(VerificationDecision.DENY, result.decision());
        assertEquals("license expired", result.reason());
        assertNull(result.downloadToken());
        assertNull(result.artifactKey());
    }

    @Test
    void serverErrorsAreRetryableExceptions() {
        backend.onVerify(b -> StubBackend.Response.error(503, "down"));
        VerificationException e = assertThrows(VerificationException.class,
                () -> new HttpVerificationService(config).verify(request()));
        assertTrue(e.isRetryable());
        assertTrue(e.getMessage().contains("503"));
    }

    @Test
    void bareForbiddenIsDeny() throws Exception {
        backend.onVerify(b -> StubBackend.Response.error(403, ""));
        VerificationResult result = new HttpVerificationService(config).verify(request());
        assertEquals(VerificationDecision.DENY, result.decision());
    }

    @Test
    void malformedAllowIsRejected() {
        backend.onVerify(b -> new StubBackend.Response(200, "{\"decision\":\"ALLOW\"}"));
        VerificationException e = assertThrows(VerificationException.class,
                () -> new HttpVerificationService(config).verify(request()));
        assertFalse(e.isRetryable());
    }

    @Test
    void unreachableBackendIsRetryable() {
        backend.close();
        VerificationException e = assertThrows(VerificationException.class,
                () -> new HttpVerificationService(config).verify(request()));
        assertTrue(e.isRetryable());
        assertFalse(e.getMessage().contains("abc"), "credentials must never appear in messages");
    }

    @Test
    void downloadUsesBearerTokenAndHonoursLimits() throws Exception {
        byte[] artifact = new byte[5000];
        artifact[0] = 42;
        backend.allowWith(new byte[32], artifact);
        VerificationResult grant = new HttpVerificationService(config).verify(request());
        byte[] downloaded = new HttpArtifactDownloader(config).download(grant);
        assertArrayEquals(artifact, downloaded);
        assertEquals("Bearer " + backend.expectedToken(), backend.received().get(1).authorization());

        StreamConfig small = StreamConfig.builder().endpoint(backend.endpoint()).coordinates(TestArtifacts.COORDINATES)
                .publicKey(Ed25519.encodePublicKey(keys.getPublic())).maxArtifactBytes(100).build();
        assertThrows(VerificationException.class, () -> new HttpArtifactDownloader(small).download(grant));

        VerificationResult bogus = VerificationResult.allow("wrong-token", new byte[32]);
        VerificationException e = assertThrows(VerificationException.class, () -> new HttpArtifactDownloader(config).download(bogus));
        assertFalse(e.isRetryable());
        assertFalse(e.getMessage().contains("wrong-token"));

        assertThrows(VerificationException.class, () -> new HttpArtifactDownloader(config).download(VerificationResult.deny("no")));
    }
}
