package dev.modstreaming.core.registry;

import dev.modstreaming.api.DefaultBridgeProvider;
import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.core.fixture.FixtureService;
import dev.modstreaming.core.loader.InMemoryClassLoader;
import dev.modstreaming.core.loader.JarContents;
import dev.modstreaming.core.log.StreamLogger;
import dev.modstreaming.core.testutil.TestArtifacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRegistrarTest {

    private static final String GREETER = "dev.modstreaming.core.fixture.streamed.StreamedGreeter";
    private static final String HELPER = "dev.modstreaming.core.fixture.streamed.NoInterfaceHelper";

    private InMemoryClassLoader loader(Map<String, byte[]> entries) throws Exception {
        return new InMemoryClassLoader("a", JarContents.parse(TestArtifacts.jar(entries)), getClass().getClassLoader());
    }

    @Test
    void registersBindingsInBridge() throws Exception {
        DefaultBridgeProvider bridge = new DefaultBridgeProvider();
        InMemoryClassLoader loader = loader(TestArtifacts.streamedEntries());
        ServiceRegistrar registrar = new ServiceRegistrar(bridge, StreamLogger.silent());

        ServiceRegistrar.Result result = registrar.register(TestArtifacts.BINDINGS, loader);
        assertTrue(result.allRegistered());
        assertEquals(1, result.registered().size());
        FixtureService service = bridge.get(FixtureService.class).orElseThrow();
        assertEquals("Hello from the stream, Alex!", service.greet("Alex"));
        assertSame(loader, service.getClass().getClassLoader());

        assertEquals(1, registrar.unregister(result.registered()));
        assertFalse(bridge.get(FixtureService.class).isPresent());
    }

    @Test
    void reportsFailuresWithoutThrowing() throws Exception {
        DefaultBridgeProvider bridge = new DefaultBridgeProvider();
        InMemoryClassLoader loader = loader(TestArtifacts.streamedEntries());
        ServiceRegistrar registrar = new ServiceRegistrar(bridge, StreamLogger.silent());

        List<ServiceBinding> bindings = List.of(
                new ServiceBinding(FixtureService.class.getName(), "dev.modstreaming.core.fixture.streamed.Missing"),
                new ServiceBinding(FixtureService.class.getName(), HELPER),
                new ServiceBinding("dev.modstreaming.core.fixture.NoSuchService", GREETER),
                new ServiceBinding(FixtureService.class.getName(), GREETER));
        ServiceRegistrar.Result result = registrar.register(bindings, loader);
        assertEquals(3, result.failed().size());
        assertEquals(1, result.registered().size());
        assertTrue(result.failed().get(0).reason().contains("not contained"));
        assertTrue(result.failed().get(1).reason().contains("does not implement"));
        assertTrue(result.failed().get(2).reason().contains("class not found"));
        assertTrue(bridge.get(FixtureService.class).isPresent());
    }

    @Test
    void serviceInterfaceMustComeFromThePublicSide() throws Exception {
        DefaultBridgeProvider bridge = new DefaultBridgeProvider();
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>(TestArtifacts.streamedEntries());
        // Smuggle the interface into the streamed jar: the loader owns it now, so registration must fail.
        entries.put("dev/modstreaming/core/fixture/FixtureService.class", TestArtifacts.classBytes(FixtureService.class.getName()));
        InMemoryClassLoader loader = loader(entries);
        ServiceRegistrar.Result result = new ServiceRegistrar(bridge, StreamLogger.silent()).register(TestArtifacts.BINDINGS, loader);
        assertFalse(result.allRegistered());
        assertTrue(result.failed().get(0).reason().contains("does not implement")
                || result.failed().get(0).reason().contains("public jar"), result.failed().get(0).reason());
        assertFalse(bridge.get(FixtureService.class).isPresent());
    }
}
