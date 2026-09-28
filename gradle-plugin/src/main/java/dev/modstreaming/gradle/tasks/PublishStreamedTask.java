package dev.modstreaming.gradle.tasks;

import dev.modstreaming.api.auth.VerificationException;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.publish.ArtifactPublisher;
import dev.modstreaming.core.publish.PublishResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Uploads the packaged artifact and its key to the backend. This task is never part of
 * {@code build}; it only runs when invoked explicitly.
 */
@UntrackedTask(because = "publishing is a side effect on a remote system")
public abstract class PublishStreamedTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArtifactFile();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getKeyFile();

    @Input
    public abstract Property<String> getEndpoint();

    /** Secret; intentionally not an input. */
    @Internal
    public abstract Property<String> getPublishToken();

    @TaskAction
    public void publish() throws IOException {
        if (!getPublishToken().isPresent() || getPublishToken().get().isBlank()) {
            throw new GradleException("modStreaming: no publish token configured. Set modStreaming.publishToken "
                    + "or the MODSTREAMING_PUBLISH_TOKEN environment variable.");
        }
        byte[] container = Files.readAllBytes(getArtifactFile().get().getAsFile().toPath());
        byte[] key = Base64.getDecoder().decode(Files.readString(getKeyFile().get().getAsFile().toPath(), StandardCharsets.UTF_8).trim());
        try {
            URI endpoint = URI.create(getEndpoint().get());
            PublishResult result = new ArtifactPublisher(endpoint).publish(container, key, getPublishToken().get());
            getLogger().lifecycle("modStreaming: published {} to {} (artifactId {}{})",
                    getArtifactFile().get().getAsFile().getName(), endpoint.getHost(), result.artifactId(),
                    result.replaced() ? ", replaced previous artifact" : "");
        } catch (VerificationException e) {
            throw new GradleException("modStreaming: " + e.getMessage(), e);
        } finally {
            AesGcm.wipe(key);
        }
    }
}
