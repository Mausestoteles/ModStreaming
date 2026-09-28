package dev.modstreaming.gradle;

import dev.modstreaming.gradle.tasks.GenerateStreamedKeyPairTask;
import dev.modstreaming.gradle.tasks.PackageStreamedTask;
import dev.modstreaming.gradle.tasks.PublishStreamedTask;
import dev.modstreaming.gradle.tasks.SplitStreamedTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;

import java.lang.reflect.Method;

/**
 * Wires the ModStreaming tasks into a Java (or Fabric Loom) build.
 *
 * <pre>
 * jar ──▶ splitStreamed ──▶ public jar (+ embedded stream config)   ──▶ assemble / remapJar
 *                     └──▶ streamed jar (+ services.json)             ──▶ [remapStreamedJar] ──▶ packageStreamed ──▶ publishStreamed
 * </pre>
 *
 * <p>{@code build} never uploads anything; {@code publishStreamed} must be invoked explicitly.</p>
 */
public final class ModStreamingPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "modStreaming";
    public static final String SPLIT_TASK = "splitStreamed";
    public static final String PACKAGE_TASK = "packageStreamed";
    public static final String PUBLISH_TASK = "publishStreamed";
    public static final String KEYPAIR_TASK = "generateStreamedKeyPair";
    public static final String REMAP_STREAMED_TASK = "remapStreamedJar";
    public static final String TASK_GROUP = "modstreaming";

    private static final String LOOM_PLUGIN_ID = "fabric-loom";
    private static final String LOOM_REMAP_TASK = "remapJar";
    private static final String LOOM_REMAP_TASK_CLASS = "net.fabricmc.loom.task.RemapJarTask";

    @Override
    public void apply(Project project) {
        ModStreamingExtension ext = project.getExtensions().create(EXTENSION_NAME, ModStreamingExtension.class);
        ext.getModVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));
        ext.getLoader().convention("fabric");
        ext.getLoadMode().convention("ASYNC");
        ext.getStrict().convention(true);
        ext.getAllowInsecureEndpoint().convention(false);
        ext.getOutputDirectory().convention(project.getLayout().getBuildDirectory().dir("modstreaming"));
        ext.getSigningKey().convention(project.getProviders().environmentVariable("MODSTREAMING_SIGNING_KEY"));
        ext.getSigningKeyFile().convention(project.getLayout().file(
                project.getProviders().environmentVariable("MODSTREAMING_SIGNING_KEY_FILE").map(project::file)));
        ext.getPublishToken().convention(project.getProviders().environmentVariable("MODSTREAMING_PUBLISH_TOKEN"));

        project.getTasks().register(KEYPAIR_TASK, GenerateStreamedKeyPairTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Generates an Ed25519 key pair for signing streamed artifacts.");
            task.getOutputDirectory().convention(project.getLayout().getProjectDirectory().dir("modstreaming-keys"));
            task.getForce().convention(false);
        });

        project.getPluginManager().withPlugin("java", applied -> configureJava(project, ext));
    }

    private void configureJava(Project project, ModStreamingExtension ext) {
        TaskContainer tasks = project.getTasks();
        TaskProvider<Jar> jar = tasks.named("jar", Jar.class);

        TaskProvider<SplitStreamedTask> split = tasks.register(SPLIT_TASK, SplitStreamedTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Removes @Streamed classes from the public jar and writes the streamed jar.");
            task.getInputJar().convention(ext.getInputJar().orElse(jar.flatMap(Jar::getArchiveFile)));
            task.getEndpoint().convention(ext.getEndpoint());
            task.getProjectId().convention(ext.getProjectId());
            task.getPublicKey().convention(ext.getPublicKey());
            task.getModVersion().convention(ext.getModVersion());
            task.getMinecraftVersion().convention(ext.getMinecraftVersion());
            task.getLoader().convention(ext.getLoader());
            task.getLoadMode().convention(ext.getLoadMode());
            task.getStrict().convention(ext.getStrict());
            task.getAllowInsecureEndpoint().convention(ext.getAllowInsecureEndpoint());
            task.getStreamedResources().convention(ext.getStreamedResources());
            Provider<String> baseName = jar.flatMap(Jar::getArchiveBaseName).zip(ext.getModVersion(), (b, v) -> b + "-" + v);
            task.getPublicJar().convention(ext.getOutputDirectory().file(baseName.map(n -> n + "-public.jar")));
            task.getStreamedJar().convention(ext.getOutputDirectory().file(baseName.map(n -> n + "-streamed.jar")));
            task.getServicesFile().convention(ext.getOutputDirectory().file("services.json"));
            task.getReportFile().convention(ext.getOutputDirectory().file("split-report.json"));
        });

        TaskProvider<PackageStreamedTask> packageTask = tasks.register(PACKAGE_TASK, PackageStreamedTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Signs and encrypts the streamed jar into a .msa artifact container.");
            task.getStreamedJar().convention(split.flatMap(SplitStreamedTask::getStreamedJar));
            task.getProjectId().convention(ext.getProjectId());
            task.getModVersion().convention(ext.getModVersion());
            task.getMinecraftVersion().convention(ext.getMinecraftVersion());
            task.getLoader().convention(ext.getLoader());
            task.getPublicKey().convention(ext.getPublicKey());
            task.getSigningKey().convention(ext.getSigningKey());
            task.getSigningKeyFile().convention(ext.getSigningKeyFile());
            Provider<String> fileName = ext.getProjectId().zip(ext.getModVersion(), (p, v) -> p + "-" + v)
                    .zip(ext.getMinecraftVersion(), (a, mc) -> a + "-" + mc)
                    .zip(ext.getLoader(), (a, l) -> a + "-" + l);
            task.getArtifactFile().convention(ext.getOutputDirectory().file(fileName.map(n -> n + ".msa")));
            task.getKeyFile().convention(ext.getOutputDirectory().file(fileName.map(n -> n + ".msa.key")));
            task.getManifestFile().convention(ext.getOutputDirectory().file(fileName.map(n -> n + ".manifest.json")));
        });

        tasks.register(PUBLISH_TASK, PublishStreamedTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Uploads the packaged streamed artifact to the backend (never part of build).");
            task.getArtifactFile().convention(packageTask.flatMap(PackageStreamedTask::getArtifactFile));
            task.getKeyFile().convention(packageTask.flatMap(PackageStreamedTask::getKeyFile));
            task.getEndpoint().convention(ext.getEndpoint());
            task.getPublishToken().convention(ext.getPublishToken());
        });

        // Fabric Loom: split the dev jar before remapping, remap the streamed jar separately.
        project.getPluginManager().withPlugin(LOOM_PLUGIN_ID, applied -> configureLoom(project, ext, split, packageTask));

        // Plain Java builds: the public jar takes the canonical archive path, the unsplit jar gets a classifier.
        project.afterEvaluate(p -> {
            if (!p.getPluginManager().hasPlugin(LOOM_PLUGIN_ID)) {
                configurePlainJava(p, ext, jar, split);
            }
        });
    }

    private void configurePlainJava(Project project, ModStreamingExtension ext, TaskProvider<Jar> jar,
                                    TaskProvider<SplitStreamedTask> split) {
        if (ext.getInputJar().isPresent()) {
            // A custom input jar (e.g. shadow) is split into the output directory; the build owner decides how to ship it.
            project.getTasks().named("assemble", t -> t.dependsOn(split));
            return;
        }
        jar.configure(j -> {
            String current = j.getArchiveClassifier().getOrElse("");
            if (current.isEmpty()) {
                j.getArchiveClassifier().set("unsplit");
            }
        });
        split.configure(task -> {
            Provider<RegularFile> canonical = jar.flatMap(j -> {
                Provider<String> name = j.getArchiveBaseName()
                        .zip(j.getArchiveAppendix().orElse(""), (b, a) -> a.isEmpty() ? b : b + "-" + a)
                        .zip(j.getArchiveVersion().orElse(""), (b, v) -> v.isEmpty() ? b : b + "-" + v)
                        .zip(j.getArchiveExtension().orElse("jar"), (b, e) -> b + "." + e);
                return j.getDestinationDirectory().file(name);
            });
            task.getPublicJar().set(canonical);
        });
        project.getTasks().named("assemble", t -> t.dependsOn(split));
    }

    @SuppressWarnings("unchecked")
    private void configureLoom(Project project, ModStreamingExtension ext, TaskProvider<SplitStreamedTask> split,
                               TaskProvider<PackageStreamedTask> packageTask) {
        TaskContainer tasks = project.getTasks();
        tasks.named(LOOM_REMAP_TASK, task -> {
            task.dependsOn(split);
            regularFileProperty(task, "getInputFile").set(split.flatMap(SplitStreamedTask::getPublicJar));
        });

        Class<Task> remapType;
        try {
            remapType = (Class<Task>) Class.forName(LOOM_REMAP_TASK_CLASS, false, project.getBuildscript().getClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                remapType = (Class<Task>) Class.forName(LOOM_REMAP_TASK_CLASS, false, ModStreamingPlugin.class.getClassLoader());
            } catch (ClassNotFoundException e2) {
                project.getLogger().warn("modStreaming: fabric-loom is applied but {} was not found; "
                        + "the streamed jar will not be remapped", LOOM_REMAP_TASK_CLASS);
                return;
            }
        }
        TaskProvider<Task> remapStreamed = tasks.register(REMAP_STREAMED_TASK, remapType, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Remaps the streamed jar to the runtime (intermediary) namespace.");
            task.dependsOn(split);
            regularFileProperty(task, "getInputFile").set(split.flatMap(SplitStreamedTask::getStreamedJar));
            booleanProperty(task, "getAddNestedDependencies").set(false);
            // Loom's remap tasks extend org.gradle.jvm.tasks.Jar; AbstractArchiveTask is the common base.
            if (task instanceof AbstractArchiveTask archive) {
                archive.getDestinationDirectory().set(ext.getOutputDirectory());
                archive.getArchiveClassifier().set("streamed-remapped");
            }
        });
        packageTask.configure(task -> task.getStreamedJar().set(
                remapStreamed.flatMap(t -> ((AbstractArchiveTask) t).getArchiveFile())));
        // Like the plain-Java wiring: a normal build always produces both halves, but never uploads.
        tasks.named("assemble", t -> t.dependsOn(remapStreamed));
    }

    private static RegularFileProperty regularFileProperty(Task task, String getter) {
        return (RegularFileProperty) invoke(task, getter);
    }

    @SuppressWarnings("unchecked")
    private static Property<Boolean> booleanProperty(Task task, String getter) {
        return (Property<Boolean>) invoke(task, getter);
    }

    private static Object invoke(Task task, String getter) {
        try {
            Method method = task.getClass().getMethod(getter);
            return method.invoke(task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("modStreaming: cannot access " + getter + " on " + task.getClass().getName()
                    + "; this Loom version is not supported", e);
        }
    }
}
