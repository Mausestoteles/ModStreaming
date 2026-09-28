package dev.modstreaming.api.auth;

/**
 * Client-side verification contract: sends a {@link VerificationRequest} to a backend and returns
 * its decision. The reference implementation talks HTTP/JSON, but any transport may be used.
 */
@FunctionalInterface
public interface VerificationService {

    /**
     * Asks the backend whether the requesting client may load the artifact.
     *
     * @throws VerificationException if the backend could not be reached or answered unexpectedly.
     *                               A regular {@link VerificationDecision#DENY} is not an exception.
     */
    VerificationResult verify(VerificationRequest request) throws VerificationException;
}
