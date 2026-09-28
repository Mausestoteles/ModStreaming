package dev.modstreaming.core.artifact;

import dev.modstreaming.core.crypto.AesGcm;
import dev.modstreaming.core.crypto.CryptoException;
import dev.modstreaming.core.crypto.Sha256;

import java.util.Objects;

/**
 * A container whose signature and metadata have been verified. Only a verified artifact can be
 * decrypted, which enforces the order <em>download → signature check → decryption → load</em>.
 */
public final class VerifiedArtifact {

    private final ArtifactContainer container;
    private final ArtifactManifest manifest;

    VerifiedArtifact(ArtifactContainer container, ArtifactManifest manifest) {
        this.container = Objects.requireNonNull(container, "container");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
    }

    public ArtifactManifest manifest() {
        return manifest;
    }

    public ArtifactContainer container() {
        return container;
    }

    /**
     * Decrypts the payload with {@code key} and checks the plaintext hash and size declared in the
     * signed manifest. The caller owns {@code key} and should wipe it afterwards.
     */
    public byte[] decrypt(byte[] key) throws ArtifactException {
        byte[] plaintext;
        try {
            plaintext = AesGcm.decrypt(key, container.payload(),
                    ArtifactPackager.aadFor(manifest.artifactId(), manifest.coordinates()));
        } catch (CryptoException e) {
            throw new ArtifactException(e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ArtifactException("artifact key has the wrong size");
        }
        if (plaintext.length != manifest.contentSize()) {
            throw new ArtifactException("decrypted content size does not match the manifest");
        }
        if (!Sha256.hexEquals(Sha256.hex(plaintext), manifest.contentSha256())) {
            throw new ArtifactException("decrypted content hash does not match the manifest");
        }
        return plaintext;
    }
}
