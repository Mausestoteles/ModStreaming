package dev.modstreaming.gradle.tasks;

import dev.modstreaming.core.crypto.Ed25519;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;

/**
 * Generates an Ed25519 key pair. The public key goes into {@code modStreaming.publicKey}, the
 * private key stays on the developer's machine / CI secret store.
 */
@UntrackedTask(because = "generates fresh key material")
public abstract class GenerateStreamedKeyPairTask extends DefaultTask {

    public static final String PRIVATE_KEY_FILE = "modstreaming.private.key";
    public static final String PUBLIC_KEY_FILE = "modstreaming.public.key";

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    @Internal
    @Option(option = "force", description = "Overwrite existing key files")
    public abstract Property<Boolean> getForce();

    @TaskAction
    public void generate() throws IOException {
        Path dir = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(dir);
        Path priv = dir.resolve(PRIVATE_KEY_FILE);
        Path pub = dir.resolve(PUBLIC_KEY_FILE);
        if ((Files.exists(priv) || Files.exists(pub)) && !getForce().getOrElse(false)) {
            throw new GradleException("modStreaming: key files already exist in " + dir + " (use --force to overwrite)");
        }
        KeyPair keys = Ed25519.generateKeyPair();
        Files.writeString(priv, Ed25519.encodePrivateKey(keys.getPrivate()) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.writeString(pub, Ed25519.encodePublicKey(keys.getPublic()) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(priv, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
        getLogger().lifecycle("modStreaming: generated Ed25519 key pair in {}", dir);
        getLogger().lifecycle("  public key (modStreaming.publicKey): {}", Ed25519.encodePublicKey(keys.getPublic()));
        getLogger().lifecycle("  private key written to {} - keep it secret, never commit it", priv.getFileName());
    }
}
