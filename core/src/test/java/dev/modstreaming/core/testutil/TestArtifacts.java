package dev.modstreaming.core.testutil;

import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.artifact.ArtifactPackager;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.fixture.FixtureService;
import dev.modstreaming.core.fixture.streamed.StreamedGreeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds fixture jars and packaged artifacts for tests. */
public final class TestArtifacts {

    public static final ArtifactCoordinates COORDINATES = new ArtifactCoordinates("test-mod", "1.2.3", "1.21.1", "fabric");
    public static final String RESOURCE_PATH = "streamed/hello.txt";
    public static final byte[] RESOURCE_BYTES = "hello from memory".getBytes(StandardCharsets.UTF_8);

    public static final List<ServiceBinding> BINDINGS =
            List.of(new ServiceBinding(FixtureService.class.getName(), StreamedGreeter.class.getName()));

    private TestArtifacts() {
    }

    public static byte[] classBytes(String binaryName) throws IOException {
        String path = binaryName.replace('.', '/') + ".class";
        try (InputStream in = TestArtifacts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("fixture class not found: " + path);
            }
            return in.readAllBytes();
        }
    }

    /** Entries of the streamed fixture jar: the greeter, its nested + anonymous classes, a helper, a resource. */
    public static Map<String, byte[]> streamedEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String cls : List.of(
                "dev.modstreaming.core.fixture.streamed.StreamedGreeter",
                "dev.modstreaming.core.fixture.streamed.StreamedGreeter$Inner",
                "dev.modstreaming.core.fixture.streamed.StreamedGreeter$1",
                "dev.modstreaming.core.fixture.streamed.NoInterfaceHelper")) {
            entries.put(cls.replace('.', '/') + ".class", classBytes(cls));
        }
        entries.put(RESOURCE_PATH, RESOURCE_BYTES);
        return entries;
    }

    public static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    public static byte[] streamedJar() throws IOException {
        return jar(streamedEntries());
    }

    public static ArtifactPackager.Packaged packaged(KeyPair keys) throws IOException {
        return new ArtifactPackager().pack(streamedJar(), COORDINATES, BINDINGS, keys.getPrivate(), keys.getPublic());
    }

    public static StreamConfig config(KeyPair keys, String endpoint) {
        return StreamConfig.builder()
                .endpoint(endpoint)
                .coordinates(COORDINATES)
                .publicKey(Ed25519.encodePublicKey(keys.getPublic()))
                .service(FixtureService.class.getName())
                .build();
    }
}
