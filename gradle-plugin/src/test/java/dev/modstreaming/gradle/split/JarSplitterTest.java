package dev.modstreaming.gradle.split;

import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.artifact.ServiceMetadata;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarSplitterTest {

    private static final String CONFIG = StreamConfig.builder()
            .endpoint("https://stream.example.com")
            .projectId("demo")
            .publicKey(Ed25519.encodePublicKey(Ed25519.generateKeyPair().getPublic()))
            .modVersion("1.0.0")
            .minecraftVersion("1.21.1")
            .build()
            .toJson();

    @TempDir
    Path dir;

    @Test
    void movesStreamedClassesWithNestedOnesAndGeneratesMetadata() throws Exception {
        Path input = dir.resolve("input.jar");
        JarSplitter.writeJar(input, FixtureJars.entries(FixtureJars.ALL));
        Path publicJar = dir.resolve("public.jar");
        Path streamedJar = dir.resolve("streamed.jar");

        SplitResult result = JarSplitter.split(input, publicJar, streamedJar,
                new JarSplitter.Options(CONFIG, List.of("assets/demo/secret/**")));

        Map<String, byte[]> pub = JarSplitter.readJar(publicJar);
        Map<String, byte[]> streamed = JarSplitter.readJar(streamedJar);

        // public jar keeps interface, hook, resources and manifest; gets the stream config
        assertTrue(pub.containsKey(FixtureJars.PKG + "PublicService.class"));
        assertTrue(pub.containsKey(FixtureJars.PKG + "PublicHook.class"));
        assertTrue(pub.containsKey("fabric.mod.json"));
        assertTrue(pub.containsKey("assets/demo/public.txt"));
        assertTrue(pub.containsKey("META-INF/MANIFEST.MF"));
        assertTrue(pub.containsKey(ModStreaming.CONFIG_RESOURCE));
        assertFalse(pub.containsKey("assets/demo/secret/model.json"));
        for (String name : pub.keySet()) {
            assertFalse(name.contains("/secret/") && name.endsWith(".class"), "leaked: " + name);
            assertFalse(name.contains("/pkg/"), "leaked: " + name);
        }

        // streamed jar has every secret class, nested/anonymous/local classes, the package and the resource
        for (String expected : List.of("SecretImpl", "SecretImpl$Nested", "SecretImpl$InnerMember", "SecretImpl$1",
                "SecretImpl$1$1Local", "MovedOnly", "ExplicitImpl")) {
            assertTrue(streamed.containsKey(FixtureJars.PKG + "secret/" + expected + ".class"), expected);
        }
        assertTrue(streamed.containsKey(FixtureJars.PKG + "pkg/PkgClass.class"));
        assertTrue(streamed.containsKey(FixtureJars.PKG + "pkg/package-info.class"));
        assertTrue(streamed.containsKey("assets/demo/secret/model.json"));
        assertTrue(streamed.containsKey(ModStreaming.SERVICES_RESOURCE));
        assertTrue(streamed.containsKey("META-INF/MANIFEST.MF"));
        assertFalse(streamed.containsKey(FixtureJars.PKG + "PublicHook.class"));

        List<ServiceBinding> bindings = ServiceMetadata.fromJson(streamed.get(ModStreaming.SERVICES_RESOURCE));
        assertEquals(List.of(
                new ServiceBinding("dev.modstreaming.gradle.fixture.PublicService", "dev.modstreaming.gradle.fixture.secret.ExplicitImpl"),
                new ServiceBinding("dev.modstreaming.gradle.fixture.PublicService", "dev.modstreaming.gradle.fixture.secret.SecretImpl")),
                new ArrayList<>(bindings).stream().sorted(java.util.Comparator.comparing(ServiceBinding::implementation)).toList());
        assertEquals(bindings, result.bindings());
        assertTrue(result.leaks().isEmpty());
        assertEquals(List.of("assets/demo/secret/model.json"), result.streamedResources());
        assertEquals(9, result.streamedClasses().size(), result.streamedClasses().toString());

        StreamConfig embedded = StreamConfig.fromJson(new String(pub.get(ModStreaming.CONFIG_RESOURCE), StandardCharsets.UTF_8));
        assertEquals("demo", embedded.projectId());
        assertTrue(result.toJson().contains("streamedClasses"));
    }

    @Test
    void reportsPublicReferencesToStreamedClasses() throws Exception {
        List<String> withLeak = new ArrayList<>(FixtureJars.ALL);
        withLeak.add(FixtureJars.PKG + "Leaky.class");
        Path input = dir.resolve("leaky.jar");
        JarSplitter.writeJar(input, FixtureJars.entries(withLeak));

        SplitResult result = JarSplitter.split(input, dir.resolve("p.jar"), dir.resolve("s.jar"),
                new JarSplitter.Options(CONFIG, List.of()));
        assertEquals(1, result.leaks().size());
        ReferenceChecker.Leak leak = result.leaks().iterator().next();
        assertEquals("dev/modstreaming/gradle/fixture/Leaky", leak.publicClass());
        assertEquals("dev/modstreaming/gradle/fixture/secret/SecretImpl", leak.streamedClass());
        assertTrue(JarSplitter.readJar(dir.resolve("p.jar")).containsKey(FixtureJars.PKG + "Leaky.class"));
    }

    @Test
    void jarWithoutStreamedClassesOnlyGetsConfig() throws Exception {
        Path input = dir.resolve("plain.jar");
        JarSplitter.writeJar(input, FixtureJars.entries(List.of(FixtureJars.PKG + "PublicService.class", FixtureJars.PKG + "PublicHook.class")));
        SplitResult result = JarSplitter.split(input, dir.resolve("p.jar"), dir.resolve("s.jar"),
                new JarSplitter.Options(CONFIG, List.of()));
        assertFalse(result.hasStreamedContent());
        assertTrue(result.bindings().isEmpty());
        Map<String, byte[]> streamed = JarSplitter.readJar(dir.resolve("s.jar"));
        assertEquals(2, streamed.size(), streamed.keySet().toString());
    }

    @Test
    void globPatterns() {
        assertTrue(JarSplitter.globToRegex("assets/demo/secret/**").matcher("assets/demo/secret/a/b.json").matches());
        assertTrue(JarSplitter.globToRegex("**/secret.txt").matcher("secret.txt").matches());
        assertTrue(JarSplitter.globToRegex("**/secret.txt").matcher("a/b/secret.txt").matches());
        assertFalse(JarSplitter.globToRegex("assets/*.json").matcher("assets/x/y.json").matches());
        assertTrue(JarSplitter.globToRegex("assets/*.json").matcher("assets/y.json").matches());
        assertTrue(JarSplitter.globToRegex("data/?.txt").matcher("data/a.txt").matches());
        assertFalse(JarSplitter.globToRegex("data/?.txt").matcher("data/ab.txt").matches());
    }
}
