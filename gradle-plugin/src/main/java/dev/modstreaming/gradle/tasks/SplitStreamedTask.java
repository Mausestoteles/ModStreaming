package dev.modstreaming.gradle.tasks;

import dev.modstreaming.core.config.LoadMode;
import dev.modstreaming.core.config.StreamConfig;
import dev.modstreaming.gradle.split.JarSplitter;
import dev.modstreaming.gradle.split.ReferenceChecker;
import dev.modstreaming.gradle.split.SplitResult;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Splits the input jar into the cleaned public jar (with the embedded stream config) and the
 * streamed jar (with generated service metadata).
 */
@CacheableTask
public abstract class SplitStreamedTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @Input
    public abstract Property<String> getEndpoint();

    @Input
    public abstract Property<String> getProjectId();

    @Input
    public abstract Property<String> getPublicKey();

    @Input
    public abstract Property<String> getModVersion();

    @Input
    public abstract Property<String> getMinecraftVersion();

    @Input
    public abstract Property<String> getLoader();

    @Input
    public abstract Property<String> getLoadMode();

    @Input
    public abstract Property<Boolean> getStrict();

    @Input
    public abstract Property<Boolean> getAllowInsecureEndpoint();

    @Input
    public abstract ListProperty<String> getStreamedResources();

    @OutputFile
    public abstract RegularFileProperty getPublicJar();

    @OutputFile
    public abstract RegularFileProperty getStreamedJar();

    @OutputFile
    public abstract RegularFileProperty getServicesFile();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void split() throws IOException {
        Path input = getInputJar().get().getAsFile().toPath();
        Path publicJar = getPublicJar().get().getAsFile().toPath();
        Path streamedJar = getStreamedJar().get().getAsFile().toPath();

        // First pass: learn the service interfaces so they can be listed in the embedded config.
        StreamConfig.Builder config = StreamConfig.builder()
                .endpoint(getEndpoint().get())
                .projectId(getProjectId().get())
                .publicKey(getPublicKey().get())
                .modVersion(getModVersion().get())
                .minecraftVersion(getMinecraftVersion().get())
                .loader(getLoader().get())
                .loadMode(LoadMode.valueOf(getLoadMode().get().toUpperCase(Locale.ROOT)))
                .allowInsecureEndpoint(getAllowInsecureEndpoint().get());
        StreamConfig preliminary;
        try {
            preliminary = config.build();
        } catch (IllegalArgumentException e) {
            throw new GradleException("modStreaming configuration is invalid: " + e.getMessage(), e);
        }

        SplitResult result = JarSplitter.split(input, publicJar, streamedJar,
                new JarSplitter.Options(preliminary.toJson(), getStreamedResources().get()));
        if (!result.bindings().isEmpty()) {
            // Second pass with the discovered services embedded (cheap: jars are small).
            java.util.List<String> services = result.bindings().stream().map(b -> b.service()).distinct().toList();
            StreamConfig complete = config.services(services).build();
            result = JarSplitter.split(input, publicJar, streamedJar,
                    new JarSplitter.Options(complete.toJson(), getStreamedResources().get()));
        }

        Files.createDirectories(getReportFile().get().getAsFile().toPath().getParent());
        Files.writeString(getReportFile().get().getAsFile().toPath(), result.toJson(), StandardCharsets.UTF_8);
        Files.writeString(getServicesFile().get().getAsFile().toPath(),
                dev.modstreaming.core.artifact.ServiceMetadata.toJson(result.bindings()), StandardCharsets.UTF_8);

        for (String warning : result.warnings()) {
            getLogger().warn("modStreaming: {}", warning);
        }
        if (!result.leaks().isEmpty()) {
            StringBuilder sb = new StringBuilder("public classes reference @Streamed classes directly; use Bridge.get(Service.class) instead:");
            for (ReferenceChecker.Leak leak : result.leaks()) {
                sb.append(System.lineSeparator()).append("  ").append(leak);
            }
            if (getStrict().get()) {
                throw new GradleException("modStreaming: " + sb + System.lineSeparator()
                        + "Set modStreaming.strict = false to turn this into a warning.");
            }
            getLogger().warn("modStreaming: {}", sb);
        }
        if (!result.hasStreamedContent()) {
            getLogger().warn("modStreaming: no @Streamed classes found in {}; the public jar only received the stream config",
                    input.getFileName());
        } else {
            getLogger().lifecycle("modStreaming: moved {} class(es) and {} resource(s) to {} ({} service binding(s))",
                    result.streamedClasses().size(), result.streamedResources().size(), streamedJar.getFileName(),
                    result.bindings().size());
        }
    }
}
