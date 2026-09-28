package dev.modstreaming.core.loader;

import dev.modstreaming.core.fixture.FixtureService;
import dev.modstreaming.core.testutil.TestArtifacts;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryClassLoaderTest {

    private static final String GREETER = "dev.modstreaming.core.fixture.streamed.StreamedGreeter";

    private InMemoryClassLoader newLoader() throws Exception {
        JarContents contents = JarContents.parse(TestArtifacts.streamedJar());
        return new InMemoryClassLoader("artifact-1", contents, getClass().getClassLoader());
    }

    @Test
    void definesOwnedClassesSelfFirstAndDelegatesTheRest() throws Exception {
        InMemoryClassLoader loader = newLoader();
        assertTrue(loader.owns(GREETER));
        assertTrue(loader.owns(GREETER + "$Inner"));
        assertEquals(4, loader.classNames().size());

        Class<?> streamed = loader.loadClass(GREETER);
        assertSame(loader, streamed.getClassLoader(), "owned class must be defined in memory");
        assertNotSame(Class.forName(GREETER), streamed, "must not reuse the parent's copy");

        // The service interface is shared with the parent so Bridge callers keep type identity.
        Class<?> service = loader.loadClass(FixtureService.class.getName());
        assertSame(FixtureService.class, service);
        assertTrue(FixtureService.class.isAssignableFrom(streamed));

        FixtureService instance = (FixtureService) streamed.getDeclaredConstructor().newInstance();
        assertEquals("Hello from the stream, Steve!", instance.greet("Steve"));
        assertEquals(42, instance.innerValue(), "nested and anonymous classes resolve through the in-memory loader");
        assertSame(loader, instance.getClass().getClassLoader());
        assertNotNull(streamed.getPackage());
        assertEquals("modstreaming:artifact-1", loader.getName());
        assertEquals(InMemoryClassLoader.URL_PROTOCOL, streamed.getProtectionDomain().getCodeSource().getLocation().getProtocol());
    }

    @Test
    void unknownClassesFailNormally() throws Exception {
        InMemoryClassLoader loader = newLoader();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("does.not.Exist"));
    }

    @Test
    void resourcesAreServedFromMemory() throws Exception {
        InMemoryClassLoader loader = newLoader();
        try (InputStream in = loader.getResourceAsStream(TestArtifacts.RESOURCE_PATH)) {
            assertNotNull(in);
            assertArrayEquals(TestArtifacts.RESOURCE_BYTES, in.readAllBytes());
        }
        URL url = loader.getResource(TestArtifacts.RESOURCE_PATH);
        assertNotNull(url);
        assertEquals(InMemoryClassLoader.URL_PROTOCOL, url.getProtocol());
        try (InputStream in = url.openStream()) {
            assertArrayEquals(TestArtifacts.RESOURCE_BYTES, in.readAllBytes());
        }
        assertNull(loader.getResource("missing/resource.txt"));
        assertTrue(loader.getResources(TestArtifacts.RESOURCE_PATH).hasMoreElements());
        // parent resources remain reachable
        assertNotNull(loader.getResource("dev/modstreaming/core/fixture/FixtureService.class"));
    }

    @Test
    void jarContentsRejectsTraversalAndSizeLimits() throws Exception {
        byte[] evil = TestArtifacts.jar(Map.of("../evil.class", new byte[] {1}));
        assertThrows(java.io.IOException.class, () -> JarContents.parse(evil));
        byte[] big = TestArtifacts.jar(Map.of("big.bin", new byte[4096]));
        assertThrows(java.io.IOException.class, () -> JarContents.parse(big, 1024));
        assertNull(JarContents.classNameOf("META-INF/versions/9/module-info.class"));
        assertNull(JarContents.classNameOf("module-info.class"));
        assertEquals("a.b.C", JarContents.classNameOf("a/b/C.class"));
    }
}
