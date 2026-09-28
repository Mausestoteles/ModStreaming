package dev.modstreaming.api.auth;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The backend's answer to a {@link VerificationRequest}.
 *
 * <p>On {@link VerificationDecision#ALLOW} it carries a short-lived download token and the
 * artifact key. The key is only ever kept in memory and is wiped after decryption.</p>
 */
public final class VerificationResult {

    private final VerificationDecision decision;
    private final String reason;
    private final String downloadToken;
    private final byte[] artifactKey;
    private final String artifactId;
    private final Instant expiresAt;
    private final Map<String, String> attributes;

    private VerificationResult(VerificationDecision decision, String reason, String downloadToken,
                               byte[] artifactKey, String artifactId, Instant expiresAt,
                               Map<String, String> attributes) {
        this.decision = Objects.requireNonNull(decision, "decision");
        this.reason = reason;
        this.downloadToken = downloadToken;
        this.artifactKey = artifactKey == null ? null : artifactKey.clone();
        this.artifactId = artifactId;
        this.expiresAt = expiresAt;
        this.attributes = attributes == null || attributes.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static VerificationResult allow(String downloadToken, byte[] artifactKey, String artifactId,
                                           Instant expiresAt, Map<String, String> attributes) {
        Objects.requireNonNull(downloadToken, "downloadToken");
        Objects.requireNonNull(artifactKey, "artifactKey");
        if (downloadToken.isBlank()) {
            throw new IllegalArgumentException("downloadToken must not be blank");
        }
        if (artifactKey.length == 0) {
            throw new IllegalArgumentException("artifactKey must not be empty");
        }
        return new VerificationResult(VerificationDecision.ALLOW, null, downloadToken, artifactKey,
                artifactId, expiresAt, attributes);
    }

    public static VerificationResult allow(String downloadToken, byte[] artifactKey) {
        return allow(downloadToken, artifactKey, null, null, Map.of());
    }

    public static VerificationResult deny(String reason, Map<String, String> attributes) {
        return new VerificationResult(VerificationDecision.DENY, reason == null ? "denied" : reason,
                null, null, null, null, attributes);
    }

    public static VerificationResult deny(String reason) {
        return deny(reason, Map.of());
    }

    public VerificationDecision decision() {
        return decision;
    }

    public boolean isAllowed() {
        return decision == VerificationDecision.ALLOW;
    }

    /** Human readable reason, mostly present on DENY. May be {@code null}. */
    public String reason() {
        return reason;
    }

    /** Token to present when downloading the artifact. {@code null} on DENY. */
    public String downloadToken() {
        return downloadToken;
    }

    /** Copy of the artifact key. {@code null} on DENY. Callers should wipe their copy after use. */
    public byte[] artifactKey() {
        return artifactKey == null ? null : artifactKey.clone();
    }

    /** Identifier of the artifact the backend granted. May be {@code null}. */
    public String artifactId() {
        return artifactId;
    }

    /** When the download token expires. May be {@code null}. */
    public Instant expiresAt() {
        return expiresAt;
    }

    /** Backend-defined, non-sensitive extra fields (for example a display message). */
    public Map<String, String> attributes() {
        return attributes;
    }

    /** Overwrites the internal key copy. Safe to call more than once. */
    public void wipe() {
        if (artifactKey != null) {
            Arrays.fill(artifactKey, (byte) 0);
        }
    }

    /** Never prints the token or key. */
    @Override
    public String toString() {
        return "VerificationResult[" + decision + (reason != null ? ", reason=" + reason : "")
                + (artifactId != null ? ", artifactId=" + artifactId : "") + "]";
    }
}
