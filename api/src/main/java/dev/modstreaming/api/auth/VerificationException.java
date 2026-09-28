package dev.modstreaming.api.auth;

/** Signals that verification or download could not be completed (network, protocol, server error). */
public class VerificationException extends Exception {

    private final boolean retryable;

    public VerificationException(String message) {
        this(message, null, true);
    }

    public VerificationException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public VerificationException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** {@code true} when a later attempt might succeed (timeouts, 5xx, connection refused). */
    public boolean isRetryable() {
        return retryable;
    }
}
