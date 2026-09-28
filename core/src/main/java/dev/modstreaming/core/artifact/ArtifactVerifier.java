package dev.modstreaming.core.artifact;

import dev.modstreaming.api.auth.ArtifactCoordinates;
import dev.modstreaming.api.verify.ArtifactValidator;
import dev.modstreaming.api.verify.SignatureVerifier;
import dev.modstreaming.core.crypto.Ed25519;
import dev.modstreaming.core.crypto.Ed25519SignatureVerifier;
import dev.modstreaming.core.crypto.Sha256;

import java.security.PublicKey;
import java.util.Objects;

/**
 * Verifies a downloaded container <em>before</em> anything is decrypted or loaded:
 * <ol>
 *   <li>the Ed25519 signature over the manifest bytes,</li>
 *   <li>the manifest format and algorithms,</li>
 *   <li>project id and artifact coordinates against what the client expects,</li>
 *   <li>the public key fingerprint,</li>
 *   <li>the SHA-256 of the encrypted payload,</li>
 *   <li>any additional {@link ArtifactValidator} rules.</li>
 * </ol>
 */
public final class ArtifactVerifier {

    private final SignatureVerifier signatureVerifier;
    private final String expectedFingerprint;
    private final ArtifactValidator validator;

    public ArtifactVerifier(SignatureVerifier signatureVerifier, String expectedFingerprint, ArtifactValidator validator) {
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.expectedFingerprint = expectedFingerprint;
        this.validator = validator == null ? ArtifactValidator.none() : validator;
    }

    public static ArtifactVerifier forPublicKey(PublicKey publicKey) {
        return forPublicKey(publicKey, ArtifactValidator.none());
    }

    public static ArtifactVerifier forPublicKey(PublicKey publicKey, ArtifactValidator validator) {
        return new ArtifactVerifier(new Ed25519SignatureVerifier(publicKey), Ed25519.fingerprint(publicKey), validator);
    }

    /**
     * @param container downloaded container
     * @param expected  coordinates the client requested; {@code null} skips the coordinate check
     */
    public VerifiedArtifact verify(ArtifactContainer container, ArtifactCoordinates expected) throws ArtifactException {
        Objects.requireNonNull(container, "container");
        if (!signatureVerifier.verify(container.manifestBytes(), container.signature())) {
            throw new ArtifactException("artifact signature is invalid (" + signatureVerifier.algorithm() + ")");
        }
        ArtifactManifest manifest = container.parseManifest();
        if (expectedFingerprint != null && !Sha256.hexEquals(expectedFingerprint, manifest.publicKeyFingerprint())) {
            throw new ArtifactException("artifact was signed with an unexpected key");
        }
        if (expected != null) {
            if (!expected.projectId().equals(manifest.projectId())) {
                throw new ArtifactException("artifact belongs to project '" + manifest.projectId()
                        + "' but '" + expected.projectId() + "' was requested");
            }
            if (!expected.matches(manifest.coordinates())) {
                throw new ArtifactException("artifact coordinates " + manifest.coordinates().key()
                        + " do not match the requested " + expected.key());
            }
        }
        if (!Sha256.hexEquals(Sha256.hex(container.payload()), manifest.payloadSha256())) {
            throw new ArtifactException("encrypted payload hash does not match the signed manifest");
        }
        if (manifest.contentSize() <= 0) {
            throw new ArtifactException("manifest declares an empty artifact");
        }
        try {
            validator.validate(manifest.asMap());
        } catch (RuntimeException e) {
            throw new ArtifactException("artifact rejected by validator: " + e.getMessage());
        }
        return new VerifiedArtifact(container, manifest);
    }
}
