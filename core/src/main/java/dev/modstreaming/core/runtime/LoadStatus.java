package dev.modstreaming.core.runtime;

/** Outcome categories of a streaming attempt. None of them crashes the game. */
public enum LoadStatus {
    /** Artifact verified, decrypted, loaded and services registered. */
    LOADED,
    /** The backend denied the request. Hooks stay no-ops. */
    DENIED,
    /** Backend unreachable or answered with an error; may succeed later. */
    UNAVAILABLE,
    /** Signature, metadata or decryption checks failed. The artifact was rejected. */
    INVALID_ARTIFACT,
    /** The embedded configuration is unusable. */
    INVALID_CONFIG,
    /** An unexpected error occurred while loading or registering services. */
    FAILED;

    public boolean isLoaded() {
        return this == LOADED;
    }

    /** {@code true} if retrying later makes sense. */
    public boolean isRetryable() {
        return this == UNAVAILABLE;
    }
}
