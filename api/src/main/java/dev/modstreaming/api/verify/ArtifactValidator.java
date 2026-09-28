package dev.modstreaming.api.verify;

import java.util.Map;

/**
 * Additional metadata validation hook applied after signature verification and before decryption.
 * Implementations can enforce operator-specific rules (for example a minimum build timestamp).
 */
@FunctionalInterface
public interface ArtifactValidator {

    /**
     * @param manifest the verified manifest fields (read-only)
     * @throws IllegalStateException if the artifact must not be loaded
     */
    void validate(Map<String, Object> manifest);

    static ArtifactValidator none() {
        return manifest -> { };
    }
}
