package dev.modstreaming.core.runtime;

import dev.modstreaming.api.Bridge;
import dev.modstreaming.api.DefaultBridgeProvider;
import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.api.auth.VerificationResult;
import dev.modstreaming.core.artifact.ArtifactContainer;
import dev.modstreaming.core.artifact.ArtifactPackager;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.fixture.FixtureService;
import dev.modstreaming.core.log.StreamLogger;
import dev.modstreaming.core.testutil.StubBackend;
import dev.modstreaming.core.testutil.TestArtifacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingRuntimeTest {

    private StubBackend backend;
    private KeyPair keys;
    private ArtifactPackager.Packaged packaged;
    private StreamConfig config;
    private DefaultBridgeProvider bridge;

    @BeforeEach
    void setUp() throws Exception {
        backend = new StubBackend();
        keys = Ed25519.generateKeyPair();
        packaged = TestArtifacts.packaged(keys);
        config = TestArtifacts.config(keys, backend.endpoint());
        bridge = new DefaultBridgeProvider();
        Bridge.setProvider(bridge);
    }

    @AfterEach
    void tearDown() {
        backend.close();
        Bridge.setProvider(new DefaultBridgeProvider());
    }

    private StreamingRuntime runtime(StreamingListener listener) {
        return StreamingRuntime.builder(config)
                .credentials(CredentialsProvider.of("licenseKey", "ABC-123"))
                .bridge(bridge)
                .logger(StreamLogger.silent())
                .listener(listener)
                .parentClassLoader(getClass().getClassLoader())
                .build();
    }

    /** Public "hook" as it would appear in a mod: no-op without a service. */
    private static String hook(String name) {
        return Bridge.get(FixtureService.class).map(s -> s.greet(name)).orElse("<no-op>");
    }

    @Test
    void fullPipelineLoadsArtifactAndRegistersService() {
        backend.allowWith(packaged.key(), packaged.containerBytes());
        List<String> events = new ArrayList<>();
        StreamingListener listener = new StreamingListener() {
            @Override
            public void onVerified(String projectId, VerificationResult result) {
                events.add("verified");
            }

            @Override
            public void onDownloaded(String projectId, int bytes) {
                events.add("downloaded:" + (bytes > 0));
            }

            @Override
            public void onLoaded(String projectId, LoadResult result) {
                events.add("loaded");
            }

            @Override
            public void onFinished(String projectId, LoadResult result) {
                events.add("finished:" + result.status());
            }
        };
        assertEquals("<no-op>", hook("Steve"), "hook is a no-op before loading");

        StreamingRuntime runtime = runtime(listener);
        LoadResult result = runtime.load();

        assertEquals(LoadStatus.LOADED, result.status(), result.message());
        assertEquals(packaged.manifest().artifactId(), result.artifactId());
        assertEquals(List.of(FixtureService.class.getName()), result.services());
        assertEquals("tester", result.attributes().get("subject"));
        assertTrue(result.loader().isPresent());
        assertEquals(List.of("verified", "downloaded:true", "loaded", "finished:LOADED"), events);
        assertEquals("Hello from the stream, Steve!", hook("Steve"));
        assertEquals(42, Bridge.get(FixtureService.class).orElseThrow().innerValue());
        assertEquals(result, runtime.lastResult().orElseThrow());

        // credentials were transmitted, the artifact key was requested with the download token
        assertEquals("ABC-123", backend.received().get(0).body().getAsJsonObject("credentials").get("licenseKey").getAsString());
        assertEquals("Bearer " + backend.expectedToken(), backend.received().get(1).authorization());

        assertEquals(1, runtime.unload());
        assertEquals("<no-op>", hook("Steve"));
    }

    @Test
    void asyncLoadWorks() throws Exception {
        backend.allowWith(packaged.key(), packaged.containerBytes());
        var executor = Executors.newSingleThreadExecutor();
        try {
            LoadResult result = runtime(StreamingListener.none()).loadAsync(executor).get();
            assertEquals(LoadStatus.LOADED, result.status());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void denyKeepsHooksNoOp() {
        backend.deny("no license");
        List<String> denied = new ArrayList<>();
        LoadResult result = runtime(new StreamingListener() {
            @Override
            public void onDenied(String projectId, VerificationResult r) {
                denied.add(r.reason());
            }
        }).load();
        assertEquals(LoadStatus.DENIED, result.status());
        assertEquals("no license", result.message());
        assertEquals(List.of("no license"), denied);
        assertFalse(Bridge.isAvailable(FixtureService.class));
        assertEquals("<no-op>", hook("Steve"));
    }

    @Test
    void unreachableBackendIsUnavailableNotACrash() {
        backend.close();
        LoadResult result = runtime(StreamingListener.none()).load();
        assertEquals(LoadStatus.UNAVAILABLE, result.status());
        assertTrue(result.status().isRetryable());
        assertNotNull(result.cause());
        assertEquals("<no-op>", hook("Steve"));
    }

    @Test
    void tamperedArtifactIsRejected() throws Exception {
        byte[] payload = packaged.container().payload().clone();
        payload[20] ^= 0x01;
        byte[] tampered = new ArtifactContainer(packaged.container().manifestBytes(), packaged.container().signature(), payload).toBytes();
        backend.allowWith(packaged.key(), tampered);
        LoadResult result = runtime(StreamingListener.none()).load();
        assertEquals(LoadStatus.INVALID_ARTIFACT, result.status());
        assertTrue(result.message().contains("hash"), result.message());
        assertFalse(Bridge.isAvailable(FixtureService.class));
    }

    @Test
    void artifactForAnotherProjectIsRejected() throws Exception {
        KeyPair otherKeys = Ed25519.generateKeyPair();
        ArtifactPackager.Packaged foreign = new ArtifactPackager().pack(TestArtifacts.streamedJar(), TestArtifacts.COORDINATES,
                TestArtifacts.BINDINGS, otherKeys.getPrivate(), otherKeys.getPublic());
        backend.allowWith(foreign.key(), foreign.containerBytes());
        LoadResult result = runtime(StreamingListener.none()).load();
        assertEquals(LoadStatus.INVALID_ARTIFACT, result.status());
        assertTrue(result.message().contains("signature"), result.message());
    }

    @Test
    void wrongKeyFromBackendIsRejected() {
        backend.allowWith(new byte[32], packaged.containerBytes());
        LoadResult result = runtime(StreamingListener.none()).load();
        assertEquals(LoadStatus.INVALID_ARTIFACT, result.status());
        assertFalse(Bridge.isAvailable(FixtureService.class));
    }

    @Test
    void downloadFailureIsUnavailable() {
        backend.allowWith(packaged.key(), packaged.containerBytes()).downloadStatus(500);
        LoadResult result = runtime(StreamingListener.none()).load();
        assertEquals(LoadStatus.UNAVAILABLE, result.status());
    }

    @Test
    void failingCredentialsProviderDoesNotThrow() {
        backend.allowWith(packaged.key(), packaged.containerBytes());
        StreamingRuntime runtime = StreamingRuntime.builder(config)
                .credentials(() -> {
                    throw new IllegalStateException("boom");
                })
                .logger(StreamLogger.silent())
                .bridge(bridge)
                .build();
        LoadResult result = runtime.load();
        assertEquals(LoadStatus.FAILED, result.status());
        assertTrue(backend.received().isEmpty());
    }

    @Test
    void listenerExceptionsAreContained() {
        backend.allowWith(packaged.key(), packaged.containerBytes());
        LoadResult result = runtime(new StreamingListener() {
            @Override
            public void onLoaded(String projectId, LoadResult r) {
                throw new RuntimeException("listener bug");
            }
        }).load();
        assertEquals(LoadStatus.LOADED, result.status());
        assertEquals(Map.of("subject", "tester"), result.attributes());
    }
}
