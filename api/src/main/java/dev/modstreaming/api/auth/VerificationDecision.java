package dev.modstreaming.api.auth;

/** Outcome of a verification request as decided by the backend. */
public enum VerificationDecision {
    /** The backend authorized the client; a download token and key material were issued. */
    ALLOW,
    /** The backend refused the request. The client must not load the artifact. */
    DENY
}
