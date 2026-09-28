package dev.modstreaming.core.config;

import dev.modstreaming.core.crypto.Ed25519;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamConfigTest {

    private static final KeyPair KEYS = Ed25519.generateKeyPair();
    private static final String PUB = Ed25519.encodePublicKey(KEYS.getPublic());

    @Test
    void parsesJsonAndRoundTrips() {
        String json = """
                {
                  "configVersion": 1,
                  "endpoint": "https://stream.example.com/base",
                  "projectId": "example-mod",
                  "publicKey": "%s",
                  "modVersion": "1.0.0",
                  "minecraftVersion": "1.21.1",
                  "loader": "Fabric",
                  "services": ["com.example.GreetingService"],
                  "loadMode": "blocking",
                  "connectTimeoutMillis": 5000,
                  "requestTimeoutMillis": 15000,
                  "maxArtifactBytes": 1048576
                }
                """.formatted(PUB);
        StreamConfig config = StreamConfig.fromJson(json);
        assertEquals(URI.create("https://stream.example.com/base"), config.endpoint());
        assertEquals("example-mod", config.projectId());
        assertEquals("fabric", config.coordinates().loader());
        assertEquals("example-mod:1.0.0:1.21.1:fabric", config.coordinates().key());
        assertEquals(LoadMode.BLOCKING, config.loadMode());
        assertEquals(Duration.ofSeconds(5), config.connectTimeout());
        assertEquals(Duration.ofSeconds(15), config.requestTimeout());
        assertEquals(1048576, config.maxArtifactBytes());
        assertEquals(java.util.List.of("com.example.GreetingService"), config.services());
        assertEquals(URI.create("https://stream.example.com/base/api/v1/verify"), config.resolve("api/v1/verify"));
        assertEquals(KEYS.getPublic(), config.decodedPublicKey());

        StreamConfig again = StreamConfig.fromJson(config.toJson());
        assertEquals(config.toJson(), again.toJson());
    }

    @Test
    void rejectsInsecureEndpointsUnlessLoopbackOrExplicitlyAllowed() {
        StreamConfig.Builder b = StreamConfig.builder().projectId("p").publicKey(PUB).modVersion("1").minecraftVersion("1.21.1");
        assertThrows(IllegalArgumentException.class, () -> b.endpoint("http://stream.example.com").build());
        assertEquals("http", b.endpoint("http://localhost:8080").build().endpoint().getScheme());
        assertEquals("http", b.endpoint("http://127.0.0.1:8080").build().endpoint().getScheme());
        assertEquals("http", b.endpoint("http://stream.example.com").allowInsecureEndpoint(true).build().endpoint().getScheme());
        assertThrows(IllegalArgumentException.class, () -> b.allowInsecureEndpoint(false).endpoint("ftp://x").build());
        assertThrows(IllegalArgumentException.class, () -> b.endpoint("https://x.example.com/?q=1").build());
    }

    @Test
    void rejectsMissingOrInvalidFields() {
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.fromJson("{}"));
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.fromJson("""
                {"endpoint":"https://x.example.com","projectId":"p","publicKey":"AAAA","modVersion":"1","minecraftVersion":"1.21.1"}
                """));
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.fromJson("""
                {"configVersion": 99, "endpoint":"https://x.example.com","projectId":"p","publicKey":"%s","modVersion":"1","minecraftVersion":"1.21.1"}
                """.formatted(PUB)));
        assertThrows(IllegalArgumentException.class, () -> StreamConfig.fromJson("""
                {"endpoint":"https://x.example.com","projectId":"../evil","publicKey":"%s","modVersion":"1","minecraftVersion":"1.21.1"}
                """.formatted(PUB)));
    }

    @Test
    void defaultsAreSensible() {
        StreamConfig config = StreamConfig.builder().endpoint("https://x.example.com").projectId("p")
                .publicKey(PUB).modVersion("1.0").minecraftVersion("1.21.1").build();
        assertEquals(LoadMode.ASYNC, config.loadMode());
        assertEquals("fabric", config.coordinates().loader());
        assertFalse(config.allowInsecureEndpoint());
        assertTrue(config.maxArtifactBytes() > 0);
        assertTrue(config.toJson().contains("\"publicKey\""));
        assertFalse(config.toJson().contains("privateKey"));
    }
}
