package dev.modstreaming.api.auth;

/**
 * Fetches the encrypted artifact container after a successful verification.
 */
@FunctionalInterface
public interface ArtifactDownloader {

    /**
     * Downloads the container granted by {@code grant}.
     *
     * @return the raw container bytes (still signed and encrypted)
     */
    byte[] download(VerificationResult grant) throws VerificationException;
}
