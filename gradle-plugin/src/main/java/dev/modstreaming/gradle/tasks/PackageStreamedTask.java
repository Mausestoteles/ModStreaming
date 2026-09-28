package dev.modstreaming.gradle.tasks;

import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.artifact.ArtifactContainer;
import dev.modstreaming.core.artifact.ArtifactPackager;
import dev.modstreaming.core.artifact.ServiceMetadata;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.gradle.split.JarSplitter;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Signs and encrypts the streamed jar into a {@code .msa} container. The AES key is written next
 * to it ({@code .msa.key}) for {@code publishStreamed}; both files are build outputs that must
 * never be committed.
 */
@UntrackedTask(because = "every run generates a fresh artifact id and encryption key")
public abstract class PackageStreamedTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStreamedJar();

    @Input
    public abstract Property<String> getProjectId();

    @Input
    public abstract Property<String> getModVersion();

    @Input
    public abstract Property<String> getMinecraftVersion();

    @Input
    public abstract Property<String> getLoader();

    @Input
    public abstract Property<String> getPublicKey();

    /** Secret; intentionally not an input. */
    @Internal
    public abstract Property<String> getSigningKey();

    @Internal
    public abstract RegularFileProperty getSigningKeyFile();

    @OutputFile
    public abstract RegularFileProperty getArtifactFile();

    @OutputFile
    public abstract RegularFileProperty getKeyFile();

    @OutputFile
    public abstract RegularFileProperty getManifestFile();

    @TaskAction
    public void packageArtifact() throws IOException {
        Path jarPath = getStreamedJar().get().getAsFile().toPath();
        byte[] jar = Files.readAllBytes(jarPath);
        Map<String, byte[]> entries = JarSplitter.readJar(jarPath);
        boolean hasClasses = entries.keySet().stream().anyMatch(n -> n.endsWith(".class") && !n.startsWith("META-INF/"));
        if (!hasClasses) {
            throw new GradleException("modStreaming: " + jarPath.getFileName() + " contains no streamed classes; nothing to package."
                    + " Annotate implementation classes with @Streamed.");
        }
        byte[] servicesJson = entries.get(ModStreaming.SERVICES_RESOURCE);
        List<ServiceBinding> bindings = servicesJson == null ? List.of() : ServiceMetadata.fromJson(servicesJson);

        PrivateKey signingKey = loadSigningKey();
        PublicKey publicKey;
        try {
            publicKey = Ed25519.decodePublicKey(getPublicKey().get());
        } catch (IllegalArgumentException e) {
            throw new GradleException("modStreaming.publicKey is invalid: " + e.getMessage(), e);
        }
        ArtifactCoordinates coordinates = new ArtifactCoordinates(getProjectId().get(), getModVersion().get(),
                getMinecraftVersion().get(), getLoader().get());

        ArtifactPackager.Packaged packaged;
        try {
            packaged = new ArtifactPackager().pack(jar, coordinates, bindings, signingKey, publicKey);
        } catch (IllegalArgumentException e) {
            throw new GradleException("modStreaming: " + e.getMessage(), e);
        }
        try {
            Path artifact = getArtifactFile().get().getAsFile().toPath();
            Files.createDirectories(artifact.getParent());
            Files.write(artifact, packaged.containerBytes());
            Files.writeString(getKeyFile().get().getAsFile().toPath(),
                    Base64.getEncoder().encodeToString(packaged.key()) + System.lineSeparator(), StandardCharsets.UTF_8);
            Files.writeString(getManifestFile().get().getAsFile().toPath(), packaged.manifest().toJson(), StandardCharsets.UTF_8);
            getLogger().lifecycle("modStreaming: packaged {} ({} bytes, artifactId {}) with {} service binding(s)",
                    artifact.getFileName(), packaged.containerBytes().length, packaged.manifest().artifactId(), bindings.size());
        } finally {
            AesGcm.wipe(packaged.key());
        }
    }

    private PrivateKey loadSigningKey() throws IOException {
        String key = null;
        if (getSigningKeyFile().isPresent()) {
            Path file = getSigningKeyFile().get().getAsFile().toPath();
            if (Files.exists(file)) {
                key = Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        }
        if ((key == null || key.isBlank()) && getSigningKey().isPresent()) {
            key = getSigningKey().get().trim();
        }
        if (key == null || key.isBlank()) {
            throw new GradleException("modStreaming: no signing key configured. Set modStreaming.signingKeyFile, "
                    + "modStreaming.signingKey or the MODSTREAMING_SIGNING_KEY environment variable "
                    + "(generate one with the generateStreamedKeyPair task).");
        }
        try {
            return Ed25519.decodePrivateKey(key);
        } catch (IllegalArgumentException e) {
            throw new GradleException("modStreaming: signing key is invalid: " + e.getMessage());
        }
    }

    /** File extension used for containers. */
    public static String artifactFileName(String projectId, String modVersion, String minecraftVersion, String loader) {
        return projectId + "-" + modVersion + "-" + minecraftVersion + "-" + loader + ArtifactContainer.FILE_EXTENSION;
    }
}
