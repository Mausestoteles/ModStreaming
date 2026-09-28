package dev.modstreaming.fabric;

import dev.modstreaming.api.auth.CredentialsProvider;
import dev.modstreaming.core.config.LoadMode;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.log.StreamLogger;
import dev.modstreaming.core.runtime.LoadResult;
import dev.modstreaming.core.runtime.LoadStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingBootstrapTest {

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static StreamConfig config(LoadMode mode) throws Exception {
        return StreamConfig.builder()
                .endpoint("http://127.0.0.1:" + freePort())
                .projectId("demo")
                .publicKey(Ed25519.encodePublicKey(Ed25519.generateKeyPair().getPublic()))
                .modVersion("1.0.0")
                .minecraftVersion("1.21.1")
                .loadMode(mode)
                .blockingTimeout(Duration.ofSeconds(10))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Test
    void unreachableBackendNeverThrowsAndNotifiesEntrypoints() throws Exception {
        List<LoadResult> results = new CopyOnWriteArrayList<>();
        ModStreamingEntrypoint entrypoint = new ModStreamingEntrypoint() {
            @Override
            public CredentialsProvider credentials() {
                return CredentialsProvider.of("token", "x");
            }

            @Override
            public void onResult(LoadResult result) {
                results.add(result);
                throw new IllegalStateException("entrypoint bug must be contained");
            }
        };
        StreamedMod mod = new StreamedMod("demo-mod", config(LoadMode.BLOCKING), entrypoint.credentials(), List.of(entrypoint));
        StreamingBootstrap bootstrap = new StreamingBootstrap(StreamLogger.silent(), getClass().getClassLoader());
        try {
            bootstrap.start(List.of(mod));
            bootstrap.awaitAll(10, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertEquals(LoadStatus.UNAVAILABLE, results.get(0).status());
            assertFalse(bootstrap.isLoaded("demo"));
            assertEquals(LoadStatus.UNAVAILABLE, bootstrap.result("demo").orElseThrow().status());

            LoadResult reloaded = bootstrap.reload("demo").get(10, TimeUnit.SECONDS);
            assertEquals(LoadStatus.UNAVAILABLE, reloaded.status());
            assertEquals(2, results.size());
            assertEquals(LoadStatus.INVALID_CONFIG, bootstrap.reload("unknown").get().status());
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void asyncModeReturnsImmediately() throws Exception {
        StreamedMod mod = new StreamedMod("demo-mod", config(LoadMode.ASYNC), null, List.of());
        StreamingBootstrap bootstrap = new StreamingBootstrap(StreamLogger.silent(), getClass().getClassLoader());
        try {
            bootstrap.start(List.of(mod));
            assertTrue(bootstrap.mods().containsKey("demo"));
            bootstrap.awaitAll(10, TimeUnit.SECONDS);
            assertEquals(LoadStatus.UNAVAILABLE, bootstrap.result("demo").orElseThrow().status());
        } finally {
            bootstrap.shutdown();
        }
    }

    @Test
    void fileCredentialsAreReadLazily(@TempDir Path dir) throws Exception {
        FileCredentialsProvider provider = FileCredentialsProvider.forProject(dir, "demo");
        assertEquals(Map.of(), provider.credentials());
        provider.writeTemplateIfMissing();
        assertTrue(Files.exists(provider.file()));
        assertEquals(Map.of(), provider.credentials());
        Files.writeString(provider.file(), "{\"credentials\": {\"licenseKey\": \"ABC\", \"uuid\": \"1-2-3\", \"nested\": {\"x\": 1}}}");
        assertEquals(Map.of("licenseKey", "ABC", "uuid", "1-2-3"), provider.credentials());
        Files.writeString(provider.file(), "{\"licenseKey\": \"flat\"}");
        assertEquals(Map.of("licenseKey", "flat"), provider.credentials());
        Files.writeString(provider.file(), "not json");
        assertEquals(Map.of(), provider.credentials());
        provider.writeTemplateIfMissing();
        assertEquals("not json", Files.readString(provider.file()), "template never overwrites");
    }
}
