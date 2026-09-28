package dev.modstreaming.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * {@code modStreaming { ... }} configuration block.
 *
 * <pre>{@code
 * modStreaming {
 *     endpoint.set("https://stream.example.com")
 *     projectId.set("example-mod")
 *     publicKey.set("<base64 Ed25519 public key>")
 *     minecraftVersion.set("1.21.1")
 *     // modVersion defaults to project.version, loader to "fabric"
 *     // signingKey / publishToken default to the MODSTREAMING_SIGNING_KEY / MODSTREAMING_PUBLISH_TOKEN env vars
 * }
 * }</pre>
 */
public abstract class ModStreamingExtension {

    /** Backend base URL (https). Embedded into the public jar. */
    public abstract Property<String> getEndpoint();

    /** Project identifier known to the backend. */
    public abstract Property<String> getProjectId();

    /** Base64 Ed25519 public key used by clients to verify artifact signatures. Embedded into the public jar. */
    public abstract Property<String> getPublicKey();

    /** Mod version part of the artifact coordinates. Defaults to {@code project.version}. */
    public abstract Property<String> getModVersion();

    /** Minecraft version part of the artifact coordinates. */
    public abstract Property<String> getMinecraftVersion();

    /** Loader part of the artifact coordinates. Defaults to {@code fabric}. */
    public abstract Property<String> getLoader();

    /** {@code ASYNC} (default) or {@code BLOCKING}; how the runtime loads during startup. */
    public abstract Property<String> getLoadMode();

    /** Fail the build if public classes reference streamed classes directly. Default {@code true}. */
    public abstract Property<Boolean> getStrict();

    /** Allow a plain {@code http} endpoint (only for local testing). Default {@code false}. */
    public abstract Property<Boolean> getAllowInsecureEndpoint();

    /** Ant-style patterns of resources that should move into the streamed jar (e.g. {@code assets/x/secret/**}). */
    public abstract ListProperty<String> getStreamedResources();

    /** Jar to split. Defaults to the {@code jar} task output. */
    public abstract RegularFileProperty getInputJar();

    /** Directory for split/packaged outputs. Defaults to {@code build/modstreaming}. */
    public abstract DirectoryProperty getOutputDirectory();

    /** Base64 Ed25519 private key (raw seed or PKCS#8). Secret: prefer the env var or {@link #getSigningKeyFile()}. */
    public abstract Property<String> getSigningKey();

    /** File containing the Base64 private key. */
    public abstract RegularFileProperty getSigningKeyFile();

    /** Bearer token for {@code publishStreamed}. Secret: prefer the env var. */
    public abstract Property<String> getPublishToken();
}
