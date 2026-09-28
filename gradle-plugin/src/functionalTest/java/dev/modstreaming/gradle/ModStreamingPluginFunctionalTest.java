package dev.modstreaming.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.artifact.ArtifactContainer;
import dev.modstreaming.core.artifact.ArtifactVerifier;
import dev.modstreaming.core.artifact.ServiceMetadata;
import dev.modstreaming.core.artifact.VerifiedArtifact;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a synthetic mod project through the plugin: build-split, packaging and publishing.
 */
class ModStreamingPluginFunctionalTest {

    @TempDir
    Path projectDir;

    private KeyPair keys;
    private HttpServer server;
    private final List<JsonObject> uploads = new CopyOnWriteArrayList<>();
    private final List<String> uploadAuth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        keys = Ed25519.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/publish", exchange -> {
            uploadAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            uploads.add(JsonParser.parseString(body).getAsJsonObject());
            byte[] response = "{\"artifactId\":\"from-server\",\"replaced\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        writeProject(false);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void writeProject(boolean leaky) throws IOException {
        String apiJar = System.getProperty("modstreaming.apiJar");
        assertNotNull(apiJar, "modstreaming.apiJar system property");
        Files.writeString(projectDir.resolve("settings.gradle.kts"), "rootProject.name = \"demo-mod\"\n");
        Files.writeString(projectDir.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("dev.modstreaming")
                }

                version = "1.0.0"

                java {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                dependencies {
                    implementation(files("%s"))
                }

                modStreaming {
                    endpoint.set("%s")
                    projectId.set("demo-mod")
                    publicKey.set("%s")
                    minecraftVersion.set("1.21.1")
                    signingKey.set("%s")
                    publishToken.set("publisher-token-123")
                    streamedResources.add("assets/demo/secret/**")
                    strict.set(%s)
                }
                """.formatted(apiJar.replace("\\", "\\\\"), endpoint(), Ed25519.encodePublicKey(keys.getPublic()),
                Ed25519.encodePrivateKey(keys.getPrivate()), leaky ? "false" : "true"));

        Path src = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(src.resolve("impl"));
        Files.writeString(src.resolve("GreetingService.java"), """
                package com.example;
                public interface GreetingService { String greet(String name); }
                """);
        Files.writeString(src.resolve("GreetingHook.java"), """
                package com.example;
                import dev.modstreaming.api.Bridge;
                public final class GreetingHook {
                    public static String onJoin(String name) {
                        return Bridge.get(GreetingService.class).map(s -> s.greet(name)).orElse("");
                    }
                }
                """);
        Files.writeString(src.resolve("impl/SecretGreeting.java"), """
                package com.example.impl;
                import dev.modstreaming.api.Streamed;
                import com.example.GreetingService;
                @Streamed
                public final class SecretGreeting implements GreetingService {
                    static final class Formatter { String format(String n) { return "Welcome, " + n; } }
                    public String greet(String name) {
                        Runnable r = new Runnable() { public void run() { } };
                        r.run();
                        return new Formatter().format(name) + Helper.suffix();
                    }
                }
                """);
        Files.writeString(src.resolve("impl/Helper.java"), """
                package com.example.impl;
                import dev.modstreaming.api.Streamed;
                @Streamed(register = false)
                final class Helper { static String suffix() { return "!"; } }
                """);
        if (leaky) {
            Files.writeString(src.resolve("LeakyHook.java"), """
                    package com.example;
                    public final class LeakyHook {
                        public static String greet(String n) { return new com.example.impl.SecretGreeting().greet(n); }
                    }
                    """);
        }
        Path resources = projectDir.resolve("src/main/resources");
        Files.createDirectories(resources.resolve("assets/demo/secret"));
        Files.writeString(resources.resolve("fabric.mod.json"), "{\"id\":\"demo-mod\"}");
        Files.writeString(resources.resolve("assets/demo/public.txt"), "public");
        Files.writeString(resources.resolve("assets/demo/secret/model.json"), "{\"secret\":true}");
    }

    private GradleRunner runner(String... args) {
        List<String> arguments = new ArrayList<>(List.of(args));
        arguments.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private static Set<String> entries(Path jar) throws IOException {
        Set<String> names = new TreeSet<>();
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    names.add(e.getName());
                }
            }
        }
        return names;
    }

    private static byte[] entry(Path jar, String name) throws IOException {
        try (InputStream in = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        return null;
    }

    @Test
    void buildSplitsPublicJarAndDoesNotUpload() throws Exception {
        BuildResult result = runner("build").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":splitStreamed").getOutcome());
        assertTrue(result.getOutput().contains("moved 4 class(es) and 1 resource(s)"), result.getOutput());

        Path publicJar = projectDir.resolve("build/libs/demo-mod-1.0.0.jar");
        Path unsplitJar = projectDir.resolve("build/libs/demo-mod-1.0.0-unsplit.jar");
        Path streamedJar = projectDir.resolve("build/modstreaming/demo-mod-1.0.0-streamed.jar");
        assertTrue(Files.exists(publicJar), "public jar at the canonical path");
        assertTrue(Files.exists(unsplitJar), "unsplit jar keeps a classifier");
        assertTrue(Files.exists(streamedJar));

        Set<String> pub = entries(publicJar);
        assertTrue(pub.contains("com/example/GreetingService.class"));
        assertTrue(pub.contains("com/example/GreetingHook.class"));
        assertTrue(pub.contains("fabric.mod.json"));
        assertTrue(pub.contains("assets/demo/public.txt"));
        assertTrue(pub.contains(ModStreaming.CONFIG_RESOURCE));
        assertFalse(pub.contains("assets/demo/secret/model.json"));
        assertTrue(pub.stream().noneMatch(n -> n.startsWith("com/example/impl/")), "implementation must not be in the public jar: " + pub);

        Set<String> streamed = entries(streamedJar);
        assertTrue(streamed.containsAll(List.of(
                "com/example/impl/SecretGreeting.class",
                "com/example/impl/SecretGreeting$Formatter.class",
                "com/example/impl/SecretGreeting$1.class",
                "com/example/impl/Helper.class",
                "assets/demo/secret/model.json",
                ModStreaming.SERVICES_RESOURCE)), streamed.toString());
        assertFalse(streamed.contains("com/example/GreetingHook.class"));

        List<ServiceBinding> bindings = ServiceMetadata.fromJson(entry(streamedJar, ModStreaming.SERVICES_RESOURCE));
        assertEquals(List.of(new ServiceBinding("com.example.GreetingService", "com.example.impl.SecretGreeting")), bindings);

        StreamConfig config = StreamConfig.fromJson(new String(entry(publicJar, ModStreaming.CONFIG_RESOURCE), StandardCharsets.UTF_8));
        assertEquals("demo-mod", config.projectId());
        assertEquals("1.0.0", config.coordinates().modVersion());
        assertEquals("1.21.1", config.coordinates().minecraftVersion());
        assertEquals("fabric", config.coordinates().loader());
        assertEquals(List.of("com.example.GreetingService"), config.services());
        assertEquals(Ed25519.encodePublicKey(keys.getPublic()), config.publicKey());
        assertFalse(new String(entry(publicJar, ModStreaming.CONFIG_RESOURCE), StandardCharsets.UTF_8)
                .contains(Ed25519.encodePrivateKey(keys.getPrivate())), "private key must never be embedded");

        assertTrue(uploads.isEmpty(), "build must not upload");
        assertFalse(Files.exists(projectDir.resolve("build/modstreaming/demo-mod-1.0.0-1.21.1-fabric.msa")),
                "build must not package (signing key is not needed for build)");

        // second build is up to date
        BuildResult again = runner("build").build();
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":splitStreamed").getOutcome());
    }

    @Test
    void packageProducesVerifiableArtifactAndPublishUploadsIt() throws Exception {
        BuildResult packaged = runner("packageStreamed").build();
        assertEquals(TaskOutcome.SUCCESS, packaged.task(":packageStreamed").getOutcome());

        Path artifact = projectDir.resolve("build/modstreaming/demo-mod-1.0.0-1.21.1-fabric.msa");
        Path keyFile = projectDir.resolve("build/modstreaming/demo-mod-1.0.0-1.21.1-fabric.msa.key");
        Path streamedJar = projectDir.resolve("build/modstreaming/demo-mod-1.0.0-streamed.jar");
        assertTrue(Files.exists(artifact));
        assertTrue(Files.exists(keyFile));

        ArtifactContainer container = ArtifactContainer.fromBytes(Files.readAllBytes(artifact));
        ArtifactCoordinates coordinates = new ArtifactCoordinates("demo-mod", "1.0.0", "1.21.1", "fabric");
        VerifiedArtifact verified = ArtifactVerifier.forPublicKey(keys.getPublic()).verify(container, coordinates);
        assertEquals(1, verified.manifest().services().size());
        byte[] key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        assertArrayEquals(Files.readAllBytes(streamedJar), verified.decrypt(key));
        assertTrue(uploads.isEmpty());

        BuildResult published = runner("publishStreamed").build();
        assertEquals(TaskOutcome.SUCCESS, published.task(":publishStreamed").getOutcome());
        assertTrue(published.getOutput().contains("published demo-mod-1.0.0-1.21.1-fabric.msa"), published.getOutput());
        assertEquals(1, uploads.size());
        assertEquals("Bearer publisher-token-123", uploadAuth.get(0));
        JsonObject upload = uploads.get(0);
        assertEquals(1, upload.get("protocolVersion").getAsInt());
        assertNotNull(upload.get("artifact"));
        assertNotNull(upload.get("key"));
        // the uploaded container is the freshly packaged one (packageStreamed re-runs before publishing)
        ArtifactContainer uploaded = ArtifactContainer.fromBytes(Base64.getDecoder().decode(upload.get("artifact").getAsString()));
        ArtifactVerifier.forPublicKey(keys.getPublic()).verify(uploaded, coordinates);
        assertEquals(32, Base64.getDecoder().decode(upload.get("key").getAsString()).length);
    }

    @Test
    void strictModeFailsOnDirectReferencesToStreamedClasses() throws Exception {
        writeProject(true);
        // strict = false in the leaky project → warning only
        BuildResult lenient = runner("splitStreamed").build();
        assertTrue(lenient.getOutput().contains("LeakyHook -> com.example.impl.SecretGreeting"), lenient.getOutput());

        String script = Files.readString(projectDir.resolve("build.gradle.kts")).replace("strict.set(false)", "strict.set(true)");
        Files.writeString(projectDir.resolve("build.gradle.kts"), script);
        BuildResult strict = runner("splitStreamed").buildAndFail();
        assertTrue(strict.getOutput().contains("use Bridge.get(Service.class) instead"), strict.getOutput());
        assertTrue(strict.getOutput().contains("com.example.LeakyHook -> com.example.impl.SecretGreeting"), strict.getOutput());
    }

    @Test
    void keyPairTaskWritesKeys() throws Exception {
        BuildResult result = runner("generateStreamedKeyPair").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStreamedKeyPair").getOutcome());
        Path priv = projectDir.resolve("modstreaming-keys/modstreaming.private.key");
        Path pub = projectDir.resolve("modstreaming-keys/modstreaming.public.key");
        assertTrue(Files.exists(priv) && Files.exists(pub));
        Ed25519.decodePrivateKey(Files.readString(priv).trim());
        Ed25519.decodePublicKey(Files.readString(pub).trim());
        assertTrue(result.getOutput().contains("public key (modStreaming.publicKey)"));
        runner("generateStreamedKeyPair").buildAndFail();
        runner("generateStreamedKeyPair", "--force").build();
    }
}
