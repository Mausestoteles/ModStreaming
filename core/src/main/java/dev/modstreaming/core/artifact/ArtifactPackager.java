package dev.modstreaming.core.artifact;

import dev.modstreaming.api.ServiceBinding;
import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.core.ModStreaming;
import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.crypto.Sha256;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds a signed and encrypted {@link ArtifactContainer} from a streamed jar.
 *
 * <p>Runs on the developer's machine (Gradle {@code packageStreamed}). The Ed25519 private key
 * never leaves it; the generated AES key is uploaded to the backend, which hands it to
 * authorized clients only.</p>
 */
public final class ArtifactPackager {

    /** Result of packaging. {@code key} is secret and must be wiped after upload. */
    public record Packaged(ArtifactContainer container, ArtifactManifest manifest, byte[] key) {
        public byte[] containerBytes() {
            return container.toBytes();
        }
    }

    private final SecureRandom random;
    private final Clock clock;

    public ArtifactPackager() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    public ArtifactPackager(SecureRandom random, Clock clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param streamedJar the plaintext streamed jar bytes
     * @param coordinates artifact identity
     * @param services    service bindings (usually read from services.json inside the jar)
     * @param signingKey  Ed25519 private key
     * @param publicKey   the matching public key; embedded as fingerprint and used to self-check
     */
    public Packaged pack(byte[] streamedJar, ArtifactCoordinates coordinates, List<ServiceBinding> services,
                         PrivateKey signingKey, PublicKey publicKey) {
        Objects.requireNonNull(streamedJar, "streamedJar");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(signingKey, "signingKey");
        Objects.requireNonNull(publicKey, "publicKey");
        if (streamedJar.length == 0) {
            throw new IllegalArgumentException("streamed jar is empty");
        }
        byte[] probe = Ed25519.sign(signingKey, new byte[] {1, 2, 3});
        if (!Ed25519.verify(publicKey, new byte[] {1, 2, 3}, probe)) {
            throw new IllegalArgumentException("publicKey does not belong to the signing key");
        }

        byte[] key = AesGcm.generateKey();
        String artifactId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        // The signed manifest binds the ciphertext through payloadSha256; the AAD additionally binds
        // the ciphertext to the artifact identity so a payload cannot be replayed under other
        // coordinates even if a manifest were swapped.
        byte[] aad = aadFor(artifactId, coordinates);
        byte[] payload = AesGcm.encrypt(key, streamedJar, aad, random);

        ArtifactManifest manifest = new ArtifactManifest(
                ArtifactManifest.FORMAT_VERSION,
                artifactId,
                coordinates,
                createdAt,
                Sha256.hex(payload),
                Sha256.hex(streamedJar),
                streamedJar.length,
                Ed25519.fingerprint(publicKey),
                services == null ? List.of() : services,
                ModStreaming.VERSION);
        byte[] manifestBytes = manifest.toJsonBytes();
        byte[] signature = Ed25519.sign(signingKey, manifestBytes);
        return new Packaged(new ArtifactContainer(manifestBytes, signature, payload), manifest, key);
    }

    /** AES-GCM additional authenticated data: binds the ciphertext to the artifact identity. */
    public static byte[] aadFor(String artifactId, ArtifactCoordinates coordinates) {
        return ("modstreaming/v1|" + artifactId + "|" + coordinates.key())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
