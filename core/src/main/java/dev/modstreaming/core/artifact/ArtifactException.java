package dev.modstreaming.core.artifact;

/** Thrown when an artifact container is malformed, tampered with or does not match expectations. */
public class ArtifactException extends Exception {

    public ArtifactException(String message) {
        super(message);
    }

    public ArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
